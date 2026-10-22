# CK-004 库存调拨：金蝶调拨单与米塔移库 API 口径分析

日期：2026-10-22
来源：
- 金蝶云星空测试环境实测（`http://219.139.47.222:2021/k3cloud`，账套 `6a9aaaadaf7ee6`，集成用户 `WMS`）
- 米塔 `D:\资料\米塔机器人资料\RMS任务接口文档-V1.6.pdf`
- 金蝶开放接口文档：https://openapi.open.kingdee.com/ApiHome
状态：**接口口径已核实；CK-004 设计前仍有 4 项需金蝶/米塔确认**

## 1. 结论速览

| 项 | 结论 |
|---|---|
| 金蝶调拨单 FormId | **`STK_TransferDirect`（直接调拨单）**，标准单据类型 **`ZJDB01_SYS`**；`STK_TransferApply` 是调拨申请单，不作为执行单据 |
| 金蝶调拨单能力 | 支持调出/调入仓库 + 仓位、批号、数量、货主/保管者；实测样本 `ZJDB000001` 已审核（C） |
| 101 同组织跨仓 | `FTransferBizType=InnerOrgTransfer` 可用；D.01→D.02 **不传仓位**可 Save（A）并 Submit（B） |
| 金蝶审批 | `ZJDB01_SYS` 关联工作流，WebAPI `Audit` 被拒；Submit 后停在 B（审核中），需 ERP 工作流审批后才变 C 并移动库存 |
| 米塔移库 API | **`POST /api/v2/task/transfer`（搬运任务下发）**，只能指定目标**区域**（`zoneCode`），不能指定目标库位；**米塔只同仓使用** |
| 关键口径 | ERP/WMS 调拨是**跨仓仓库级**；米塔只负责**同仓区域级**搬运；跨仓段由业务/人工/其他物流完成，不经过米塔 |
| 建议 | 金蝶统一 `STK_TransferDirect`；WMS 记录“调出仓/调入仓 + 区域”，执行时再分配库位；同仓区域搬运用 `task/transfer`，跨仓段走 WMS 在途/人工确认 |

## 2. 金蝶调拨单（实测）

### 2.1 候选 FormId 探测结果

用 `ExecuteBillQuery` 对候选表单逐个探测（`FBillNo,FDocumentStatus`，Top=1）：

| FormId | 结果 | 名称（QueryBusinessInfo） |
|---|---|---|
| `STK_TransferDirect` | **成功**，返回 `[["ZJDB000001","C"]]` | 直接调拨单 |
| `STK_TransferApply` | 成功（空表） | 调拨申请单 |
| `STK_TransferOut` | 成功（空表） | 分步式调出单 |
| `STK_TransferIn` | 成功（空表） | 分步式调入单 |
| `STK_Transfer` | 失败：业务对象不存在 | — |

**CK-004 执行单据选 `STK_TransferDirect`**。`STK_TransferApply` 只用于“申请”，若客户流程要求先申请再调拨，需要额外建申请单；本期建议只做直接调拨。

### 2.2 单据类型

`BOS_BillType` 模糊查询 `ZJDB%`：

| 编码 | 名称 |
|---|---|
| **`ZJDB01_SYS`** | **标准直接调拨单** |
| `ZJDB02_SYS` | 寄售直接调拨单 |
| `ZJDB03_SYS` | 委外直接调拨单 |
| `ZJDB04_SYS` | 分销直接调拨单 |
| `ZJDB05_SYS` | VMI直接调拨单 |
| `ZJDB06_SYS` | 跨部门直接调拨单 |

### 2.3 必填字段（QueryBusinessInfo）

**表头 `FBillHead`（9 个必填）**

| 字段 | 含义 | 备注 |
|---|---|---|
| `FStockOrgId` | 调入库存组织 | 组织编码，如 `101` |
| `FDate` | 日期 | `yyyy-MM-dd` |
| `FBillTypeID` | 单据类型 | `{"FNumber":"ZJDB01_SYS"}` |
| `FOwnerTypeIdHead` | 调入货主类型 | 样本 `BD_OwnerOrg` |
| `FTransferBizType` | 调拨类型 | 样本 `InnerOrgTransfer`（同组织调拨） |
| `FStockOutOrgId` | 调出库存组织 | 组织编码 |
| `FOwnerTypeOutIdHead` | 调出货主类型 | 样本 `BD_OwnerOrg` |
| `FTransferDirect` | 调拨方向 | 样本 `GENERAL` |
| `FStockerId` | 仓管员 | `BD_Empinfo`，需配置 |

**明细 `FBillEntry`（11 个必填）**

| 字段 | 含义 |
|---|---|
| `FMaterialId` | 物料编码 |
| `FUnitID` / `FBaseUnitId` | 单位 / 基本单位 |
| `FOwnerTypeId` / `FOwnerId` | 调入货主类型 / 调入货主 |
| `FOwnerTypeOutId` / `FOwnerOutId` | 调出货主类型 / 调出 |
| `FKeeperTypeId` / `FKeeperId` | 调入保管者类型 / 调入保管者 |
| `FKeeperTypeOutId` / `FKeeperOutId` | 调出保管者类型 / 调出保管者 |

**非必填但业务必需**：`FSrcStockId`（调出仓库）、`FDestStockId`（调入仓库）、`FSrcStockLocId`（调出仓位）、`FDestStockLocId`（调入仓位）、`FLot`（批号）、`FQty`（调拨数量）。

### 2.4 ExecuteBillQuery 字段键（已实测）

**可用**：

```
FBillNo,FDocumentStatus,FDate,
FStockOrgId.FNumber,FStockOutOrgId.FNumber,
FTransferBizType,FTransferDirect,FStockerId.FNumber,
FMaterialId.FNumber,FLot,FQty,
FSrcStockId.FNumber,FDestStockId.FNumber
```

**仓位字段特殊**：

- `FSrcStockLocId` / `FDestStockLocId` **只能查内码**（如 `100003`、`100002`）；
- `FSrcStockLocId.FNumber` 会报「元数据中标识为 FSrcStockLocId 的字段不存在」；
- 因此 ERP→WMS 同步仓位时拿到的是**金蝶仓位内码**，WMS 必须维护“WMS 库位 ↔ 金蝶仓位内码”的映射；若目标仓库未启用仓位管理（101 的 D.01/D.02 属于此类），该字段为空，ERP 侧只到仓库级。

实测样本 `ZJDB000001` 明细片段：

```
["ZJDB000001","WA.2206.2201.02000430.A003",155677,51.0,"02.001","02.001"]
```

即：物料、批号内码 155677、数量 51、调出仓 `02.001`、调入仓 `02.001`；View 中 `SrcStockLocId_Id=100003`、`DestStockLocId_Id=100002`。

### 2.5 WMS→ERP 保存模型（建议）

```json
{
  "FBillNo": "<WMS调拨单号>",
  "FBillTypeID": { "FNumber": "ZJDB01_SYS" },
  "FStockOrgId": { "FNumber": "101" },
  "FStockOutOrgId": { "FNumber": "101" },
  "FOwnerTypeIdHead": "BD_OwnerOrg",
  "FOwnerIdHead": { "FNumber": "101" },
  "FOwnerTypeOutIdHead": "BD_OwnerOrg",
  "FOwnerOutIdHead": { "FNumber": "101" },
  "FTransferBizType": "InnerOrgTransfer",
  "FTransferDirect": "GENERAL",
  "FStockerId": { "FNumber": "<仓管员编码>" },
  "FDate": "2026-10-22",
  "FBillEntry": [{
    "FMaterialId": { "FNumber": "<物料编码>" },
    "FUnitID": { "FNumber": "PCS" },
    "FBaseUnitId": { "FNumber": "PCS" },
    "FLot": "<批号>",
    "FQty": 10,
    "FSrcStockId": { "FNumber": "<调出仓>" },
    "FSrcStockLocId": 100003,
    "FDestStockId": { "FNumber": "<调入仓>" },
    "FDestStockLocId": 100002,
    "FOwnerTypeId": "BD_OwnerOrg", "FOwnerId": { "FNumber": "101" },
    "FOwnerTypeOutId": "BD_OwnerOrg", "FOwnerOutId": { "FNumber": "101" },
    "FKeeperTypeId": "BD_KeeperOrg", "FKeeperId": { "FNumber": "101" },
    "FKeeperTypeOutId": "BD_KeeperOrg", "FKeeperOutId": { "FNumber": "101" }
  }]
}
```

保存后调用 `Submit`（`FormId=STK_TransferDirect`，`data.Numbers=[单号]`），状态变为 `C`。

### 2.6 101 实测：不传仓位的 Save / Submit / Audit（2026-10-22）

测试单据：`WMS-TEST-TR-20261022-02`（Id 108907），组织 101，`D.01 → D.02`，物料 `E.008.0065`，批号 `JD`，数量 1。

| 步骤 | 请求 | 结果 |
|---|---|---|
| Save | `STK_TransferDirect` + `ZJDB01_SYS` + `InnerOrgTransfer` + `GENERAL`；**不传 `FSrcStockLocId/FDestStockLocId`** | **成功**，状态 `A` |
| Submit | `data.Numbers=[单号]` | **成功**，状态 `B`（审核中） |
| Audit | `DynamicFormService.Audit` | **失败**：`单据编码为…已关联工作流实例，不支持通过WebAPI接口进行传统审批` |
| 库存 | 查 `STK_Inventory` | Submit 后 D.01 仍 14000、D.02 仍 0；未审批，库存未移动 |

**结论**：

1. **101 的 D.01/D.02 不需要仓位**，Save 可以省略 `FSrcStockLocId/FDestStockLocId`，因此 **CK-004 不需要“WMS 库位 ↔ 金蝶仓位内码”映射**。
2. `Submit` 后是 **B（审核中）**，不是 C；`ZJDB01_SYS` 关联工作流，WebAPI 不能直接 Audit。WMS 只能 Save + Submit，**C（已审核）与库存移动由 ERP 工作流审批完成**；WMS 需轮询 `FDocumentStatus` 或接受 B 作为“已提交”。
3. `FStockerId` 引用的是 **`BD_WAREHOUSEWORKERS`（仓管员）**，不是 `BD_Empinfo`。测试用 `2040421005` 在 101 下是**禁用**（`FForbidStatus=B`、`ISUSE=false`），Save 报“仓管员必填”；改用 101 启用仓管员 `0050 熊丹妮`（FID 279082）后 Save 成功。因此配置 `stocker-number` 必须填 `BD_WAREHOUSEWORKERS` 的编码，且该记录对目标组织启用。

> **测试单残留**：`WMS-TEST-TR-20261022-02` 目前停在 B（审核中），因工作流无法通过 WebAPI 撤回/删除；需在金蝶 UI 或工作流中撤回后删除。该单未审批，库存未移动（D.02 仍为 0）。

### 2.7 待金蝶确认

1. **跨组织调拨**：101 内部 D.01↔D.02 已确认用 `InnerOrgTransfer`；若未来做 100↔101，再确认跨组织 `FTransferBizType`/`FTransferBizTypeId`。
2. **仓管员**：生产用哪个 `BD_WAREHOUSEWORKERS` 编码（必须对 101 启用）；测试用 `0050`。
3. **审批流**：`ZJDB01_SYS` 的工作流由谁审批、WMS 是否只到 B 即可，还是必须等 C；若要求 WMS 自动完成，需要无工作流单据类型或金蝶工作流接口。
4. **单据类型**：是否统一用 `ZJDB01_SYS`，还是按业务场景用其他直接调拨单类型。

## 3. 米塔 RMS 移库 API（V1.6）

### 3.1 接口定义

| 项 | 内容 |
|---|---|
| 名称 | 搬运任务下发 |
| 方向 | 上游系统 → RMS |
| 方法/URL | `POST /api/v2/task/transfer` |
| 批量语义 | **批量下发；单个任务失败则整体任务失败** |

**请求参数**

| 参数 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `zoneCode` | 是 | string(63) | 目标区域编码；文档示例：`EMERGENCY_OUT`（紧急出库区）、`PRE_ORDER`（预出库区） |
| `details` | 是 | list | 搬运明细 |
| `warehouse` | 否 | string(63) | 仓库名（默认主仓库） |
| `priority` | 否 | int | 1~5 |
| `details[].taskNo` | 是 | string(127) | 任务号 |
| `details[].containerCode` | 是 | string(127) | 容器编码 |
| `details[].zoneCode` | 否 | string(63) | 明细级区域编码 |
| `details[].priority` | 否 | int | 1~5 |

**响应**：`{"code":0,"msg":"success"}`；错误码 `300001 任务已存在`、`300007 容器编码不存在`、`300013 区域编码不存在`。

### 3.2 能力边界

- **只能指定目标区域**（`zoneCode`），不能指定目标仓库内的具体库位（`stackCode`）。
- 文档明确“区域编码以系统对接时为准”，示例只有 `EMERGENCY_OUT`/`PRE_ORDER`；是否可配置“成品区/半成品区”等业务区域，需要米塔确认。
- `warehouse` 可选，说明支持指定仓库；但跨仓库/跨 RMS 实例的搬运是否由同一 RMS 完成，文档未定义。
- 另有 `POST /api/v2/stack/unbind`（库位解绑）只用于人工出库，不是移库。
- 位置回调 `料箱位置更新`（RMS→WMS，`containerCode + stackCode`）可用于理货/移库后的库存位置更新。

### 3.3 CK-004 可选方案（按“米塔只同仓”修订）

| 方案 | 做法 | 适用 | 风险 |
|---|---|---|---|
| **A. 同仓区域搬运（现成）** | 调 `task/transfer`，`warehouse=本仓`、`zoneCode=目标区域` | 同仓、目标只到区域级 | 不能精确到库位；区域编码需米塔配置 |
| **B. 跨仓（不经过米塔）** | 跨仓段由人工/叉车/其他物流完成，WMS 记调出→在途→调入 | 跨仓业务 | 需定义在途/到达确认；WMS 不能依赖 RMS 完成跨仓 |
| **C. 米塔新增接口（不需要）** | 仅在米塔未来要承接跨仓/精确库位时才需要 | — | 当前业务口径不需要 |

**结论**：米塔只同仓使用，**CK-004 不向 RMS 下跨仓任务**；同仓区域搬运才用 `task/transfer`。跨仓段是 WMS/ERP 业务调拨 + 人工/其他物流，WMS 用“在途/待入库”状态闭环。

## 4. CK-004 落地口径建议

### 4.1 WMS 本地模型（按“跨仓、区域级、执行时分配库位”收敛）

`wms_transfer_order`：

- `transfer_no`（唯一）、`source`（WMS/ERP）、`erp_bill_no`、`erp_document_status`
- `out_warehouse_id/out_zone_code`、`in_warehouse_id/in_zone_code`：**单据只到仓库/区域**，不预分配库位
- `status`：待执行/在途/待入库/已完成/异常/已关闭
- `sync_status/retry_count/sync_fail_count/last_sync_error/last_sync_time`
- `operator/complete_time`、审计列

`wms_transfer_detail`：物料/批次/容器/数量/已分配/实发；执行时由 WMS 记录实际库位（不写 ERP）。

### 4.2 执行模型（跨仓 vs 同仓区域）

| 场景 | 物理动作 | WMS/RMS |
|---|---|---|
| **跨仓（D.01 ↔ D.02）** | 米塔只同仓，跨仓段由人工/叉车/其他物流完成 | WMS 记“调出 → 在途 → 调入”；源仓扣减/锁定，目标仓收货确认后增加；**不向 RMS 下跨仓任务** |
| **同仓区域搬运** | 米塔 `POST /api/v2/task/transfer`，`warehouse=本仓`、`zoneCode=目标区域` | 下发任务 → `task-feedback FINISHED` → `料箱位置更新` 回调实际 `stackCode` → WMS 更新库位/库存 |
| **同仓精确库位** | V1.6 不支持直接指定 `stackCode` | 由 RMS 在区域内自行分配；WMS 以位置回调为准，不在调拨单预指定 |

### 4.3 同步方向

| 方向 | 动作 |
|---|---|
| ERP→WMS | 查 `STK_TransferDirect`（建议只取 `FDocumentStatus=C`），按 `FBillNo` upsert；映射组织/仓库/物料/批号/数量；**不取仓位** |
| WMS→ERP | `Save + Submit` `STK_TransferDirect`（仓库级，不传 `FSrcStockLocId/FDestStockLocId`）；幂等键 `FBillNo`；Submit 后为 `B`，等待 ERP 工作流审批到 `C`；失败重试/告警 |
| WMS→RMS | 仅同仓区域搬运调用 `task/transfer`；跨仓不下发 RMS |
| RMS→WMS | `task-feedback` 更新任务状态；`料箱位置更新` 回调实际 `stackCode`，WMS 执行时落库位 |

### 4.4 需要新增的配置

- `wms.sync.erp.kingdee.stocker-number`：**`BD_WAREHOUSEWORKERS` 编码**（不是员工编码），且对 101 启用；测试用 `0050`（熊丹妮），生产由业务指定。
- `wms.sync.erp.kingdee.transfer-biz-type`：默认 `InnerOrgTransfer`（101 内部调拨已实测可用）。
- `wms.sync.erp.kingdee.transfer-bill-type`：默认 `ZJDB01_SYS`。
- WMS 仓库 ↔ 金蝶仓库编码映射（可复用 `stock-code-by-org`）。
- **不需要** WMS 库位 ↔ 金蝶仓位内码映射（101 不传仓位已实测通过）。

## 5. 待确认清单

| # | 问题 | 责任方 | 影响 |
|---|---|---|---|
| 1 | 生产用哪个 `BD_WAREHOUSEWORKERS` 编码（对 101 启用） | 业务/金蝶 | `FStockerId` 必填 |
| 2 | `ZJDB01_SYS` 工作流由谁审批；WMS 到 B 是否可接受，还是必须等 C | 业务/金蝶 | WMS→ERP 完成判定 |
| 3 | 跨仓段（D.01↔D.02）由谁/什么设备搬运，WMS 如何确认“在途/到达” | 业务 | 跨仓执行模型 |
| 4 | 同仓 `zoneCode` 的业务区域编码（成品区/半成品区等）与位置回调是否覆盖 transfer | 米塔 | 同仓区域搬运 |
| 5 | 测试单 `WMS-TEST-TR-20261022-02` 需在金蝶 UI/工作流撤回并删除 | 金蝶/业务 | 测试数据清理 |

## 6. 证据与复现

- 金蝶登录：`LoginByAppSecret`，账套 `6a9aaaadaf7ee6`、用户 `WMS`。
- 候选表单探测：`ExecuteBillQuery`（`STK_TransferDirect` 返回 `ZJDB000001`/`C`）。
- 必填字段：`QueryBusinessInfo`（`STK_TransferDirect`）。
- 真实样本：`View` `STK_TransferDirect/ZJDB000001`（113 条明细；`TransferBizType=InnerOrgTransfer`、`TransferDirect=GENERAL`、`SrcStockLocId_Id=100003`、`DestStockLocId_Id=100002`）。
- 米塔：`RMS任务接口文档-V1.6.pdf` §4.3（搬运任务下发）、§4.4（库位解绑）、§4.5（料箱位置更新）。
