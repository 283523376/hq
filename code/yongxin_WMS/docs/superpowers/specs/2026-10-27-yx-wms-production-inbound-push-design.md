# 半成品生产入库回写改造：改用「生产订单下推」带出 BOM 版本

- 日期：2026-10-27
- 关联：RK-002 生产入库、BD-002 工单同步
- 状态：**待批准**
- 实测依据：`~/.claude/.../memory/kingdee-prd-instock-bom-push.md`（含 12 种传值形态的证伪记录）

## 1. 问题

物料启用 BOM 版本管理时（半成品普遍如此），`WMS → PRD_INSTOCK Save` 一律失败：

```
第1行分录，物料启用BOM版本管理，BOM版本不能为空
```

**根因**：`PRD_INSTOCK.FEntity.FBomId` 的元数据是 `IsEditVisible=false / IsNewVisible=false`，
Save 时服务端直接忽略该字段——实测 12 种传值形态（`FId`/`FNumber`/组合/内码当编码/调整字段顺序/裸值）全部报同一句；
**传一个根本不存在的 BOM 编码也报"不能为空"**，证明取值压根没被读取。

**影响面**：半成品（立体仓主场景）全部无法回写；成品（物料未启用 BOM 版本管理）不受影响，链路已验证通过。

## 2. 已验证的解法

改走金蝶的**下推（Push）**，BOM 由转换规则从生产订单带出。2026-10-27 在测试账套实测：

| 步骤 | 结果 |
|---|---|
| `Push(源单=MO1012616523, TargetFormId=PRD_INSTOCK, TargetBillTypeId=SCRKD01_SYS)` | ✅ 生成目标单草稿（FID=657213，`FDocumentStatus=Z`、无单号） |
| 用目标单 FID 做 `Save` | ✅ **不再报 BOM**；只剩「批号为空」「领料套数不足」两个正常业务前置 |

下推的硬前置（金蝶原文）：

1. 生产订单已审核；2. 单据类型可直接入库；3. **分录状态=开工**；4. 入库上限 > 已选单数；
5. **业务流程必须是「空值」或「生产直接入库流程」**；6. 物料在入库组织下存在且允许库存。

> 第 5 条最容易漏：它不出现在报错正文，而是埋在 `取数失败原因分析` 第 2 条里。

## 3. 范围

**做**

- 半成品生产入库回写改为 `Push → Save（补批号/数量/仓库）→ Submit`
- 业务流程做成**配置项**，默认「生产直接入库流程」
- 预留 **「业务流程 → 目标单据类型」映射**，新增流程只改配置
- 未知/不允许的业务流程：**直接把金蝶原文报给用户**，不自动改工单流程、不静默重试
- 清理前端 `[BOM_PENDING]` 专用标记（该墙已绕过），改为按金蝶原文展示失败原因

**不做**

- 领料出库回写金蝶（`PRD_PickMtrl`）——另立 spec，但它会天然满足"先领料才能入库"的前置
- 自动修改生产订单的业务流程
- 成品路径的切换（现网验证过、保持不动，见 §6 开关）

## 4. 设计

### 4.1 配置项（`wms.sync.erp.kingdee.*`）

```yaml
        # 半成品生产入库是否走「下推」；false 时退回旧 Save 路径（回滚开关）
        push-inbound-enabled: "${KINGDEE_PUSH_INBOUND:true}"
        # 允许下推的业务流程 → 目标单据类型。键 __empty__ 表示"业务流程为空"
        push-flow-bill-type-map:
          __empty__: "SCRKD01_SYS"
          "生产直接入库流程": "SCRKD01_SYS"
        # 默认业务流程：现网是否统一尚未确认，后续可能扩展；仅用于提示与将来 WMS 建单
        default-business-flow: "${KINGDEE_DEFAULT_BUSINESS_FLOW:生产直接入库流程}"
```

- 用 `__empty__` 这个哨兵而不是空串键：空串键会让 Spring 的 `Map` 绑定抛 `BindException`（此前踩过）
- 映射表里**没有**的流程 ⇒ 拒绝下推并原样抛出金蝶原文

### 4.2 代码路径（`WmsKingdeeInboundWriterImpl`）

```
1. 查 PRD_MO（现有查询补 FBFLowId.FName）
2. 流程不在映射表 → fail("金蝶返回：业务流程字段的值，必须是如下值之一：…（原样）")
3. 取映射得到 targetBillType
4. formClient.push("PRD_MO", {Numbers, TargetFormId:PRD_INSTOCK, TargetBillTypeId, IsEnableDefaultRule:true})
   → 得目标单 FID
5. Save 目标单：FID + FEntity[i]{FEntryID, FLot, FRealQty, FStockId, FStockLocId}
   （数量/仓库按现有逻辑计算；批号必填场景取 WMS 批次号）
6. Submit 目标单
```

- `WmsKingdeeFormClient` 新增 `push(formId, data)`，报文是裸 `{FormId, Data}`（与 Save 同族）；
  `Submit/Audit/Delete` 仍是 `{FormId, data:{Numbers:[...]}}`——两套报文不要混
- 成品走原 Save 路径（`push-inbound-enabled` 只作用于「物料启用 BOM 版本管理」的半成品，或按 §6 备选统一）

### 4.3 失败处理与前端

| 场景 | 后端行为 | 前端展示 |
|---|---|---|
| 业务流程不允许 | 原样返回金蝶 message（含允许值清单） | 同步失败 + 可展开的完整原文 |
| 分录未开工 / 领料套数不足 / 批号为空 | 原样返回金蝶 message | 同上（文案里已有明确原因） |
| 下推接口本身报错 | 返回「金蝶下推失败：<原文>」 | 同上 |

- 移除 `WmsConstants.SYNC_BLOCK_BOM_PENDING` 与前端 `isBomBlocked` 的 BOM 语义；
  保留「同步失败可重试」的常规按钮（这些失败是修完数据后可重试的）
- 长文本：`last_sync_error` 已是 varchar(500)，需截断保留**首段原因**（`truncate` 现有逻辑）

## 5. 测试计划

单测（`WmsKingdeeInboundWriterImplTest`）：

1. 流程在映射表 → 调用顺序为 `push` → `Save` → `Submit`，且 Save 里带 FID 与批号
2. 流程不在映射表 → 不调用 push，错误信息含金蝶原文与允许值
3. `push` 失败 → 错误透传，不继续 Save/Submit
4. `push-inbound-enabled=false` → 走旧 Save 路径（回归保护）

真机联调（需业务配合）：

5. 101 组织造一张**开工 + 已领料**的生产订单 → 触发 WMS 半成品入库 → 校验金蝶单据已生成且可查
6. 联调后清理测试单据（反执行 → 反审核 → 删除；下推草稿直接 Delete）

## 6. 待决策 / 风险

- **成品是否也统一走 Push**：现网成品走 Save 已验证通过，改 Push 属额外风险；建议先只改半成品，
  待半成品跑稳一个迭代后再评估统一。
- **现网生产订单业务流程是否统一**：未确认。若现场存在「生产汇报入库流程」的订单，
  它们会被本条改造**明确拒绝**并提示原文（而不是像现在这样报一个看不懂的 BOM 错误）——这本身是改善，
  但会暴露存量工单的流程差异，需要业务侧跟进。
- **领料前置**：未领料就入库会被金蝶拒绝。领料回写（`PRD_PickMtrl`）落地后可自然满足。

## 7. 回滚

`push-inbound-enabled=false` 即回到现有 Save 路径（半成品仍会因 BOM 失败，但行为可预期）。
