# 永鑫WMS ↔ 金蝶云星空 业务同步设计（工单查询 + 生产入库回写）

日期：2026-10-20
依据：`2026-10-20-yx-wms-kingdee-erp-integration-analysis.md`；测试环境实测元数据
结论：工单查询 `PRD_MO`、生产入库回写 `PRD_INSTOCK` 均已在测试环境确认可读写；本设计落地这两条链路。

## 1. 已确认事实

- 登录：`LoginByAppSecret` 实测成功（测试环境已授权 HTTP）。
- 工单：`PRD_MO`（生产订单），样例 `WORK00072`，字段 `FBillNo/FDate/FDocumentStatus/FProductType/FMaterialId.FNumber/FQty/FLot`。
- 生产入库：`PRD_INSTOCK`（生产入库单），样例 `SCRK00000022`，字段见 §3。
- 仓库映射（测试环境）：`02.001 产成品仓(101932)`、`02.002 自制半成品仓(101933)`、`02.005 废品仓(101936)`。
- `PRD_INSTOCK` **没有独立不良数量字段**；不良数量只能放备注/自定义字段，或后续另立单据。本设计先写入 `FNote`。
- 金蝶物料主数据与 WMS 物料编码不一定一致（测试环境物料为 `WA.*`），需要 `material-map` 映射。
- 测试组织：**101 宜昌永鑫精工科技股份有限公司**（FOrgID=`209624`，FNumber=`101`），不是 100 江西兆鑫。

**基础资料字段键（实测）：**

| 表单 | 字段键 |
|---|---|
| `BD_MATERIAL` | `FMATERIALID,FNumber,FName,FBaseUnitId,FCreateOrgId,FUseOrgId` |
| `BD_STOCK` | `FStockId,FNumber,FName,FUseOrgId,FCreateOrgId` |
| `BD_UNIT` | `FUnitId,FNumber,FName` |
| `BD_StockStatus` | `FStockStatusId,FNumber,FName` |
| `BOS_BillType` | `FBillTypeID,FNumber,FName` |
| `STK_Inventory` | `FMaterialId,FStockId,FStockLocId,FStockOrgId,FLot,FQty,FBaseQty,FStockStatusId,FOwnerId,FKeeperId`（均非必填） |
| `PRD_MO` 分录 | `FID,FBillNo,FWorkShopID,FWorkShopID.FNumber,FQty,FProductType,FTreeEntity_FEntryId,FTreeEntity_FSeq` |

- 组织 101 下 `WA.2206.2201.02000430.A003` 的物料记录为 `FMATERIALID=575219`；`02.002` 只属于组织 100，101 下暂用 `D.02/D.01/F.03`（待确认）。
- `WORK00072`：FID=`100007`，分录=`100006`，行号=`1`，车间=`201011`。

## 2. 工单查询（ERP → WMS）

**接口**：`POST /wms/work-order/sync-kingdee?billNo=&top=50`（权限复用 `wms:work-order:edit`）

**查询**：
```
FormId=PRD_MO
FieldKeys=FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty
FilterString=FBillNo='...'   （billNo 为空时不加）
TopRowCount=top
```

**映射规则**：

| 金蝶 | WMS |
|---|---|
| `FBillNo` | `work_order_no` |
| `FMaterialId.FNumber` | 经 `material-map` 反向映射为 WMS 物料编码；找不到物料则跳过并记录原因 |
| `FMaterialId.FName` | 物料名称（仅日志/校验） |
| `FQty` | 同一 `FBillNo` 多行求和 → `plan_qty` |
| `FLot` | `batch_no`；为空/0 时用 `WORK-<FBillNo>` 兜底 |
| `FProductType` | 金蝶语义是 `1主产品/2联产品/3副产品`，**不是半成品/成品**；WMS 产品类型取 `material-product-type` 的物料映射 |
| `FDocumentStatus` | `C→生产中`；`A/B→待生产`；其他→`已完成`（可按 `status-map` 覆盖） |

- 同一工单多物料：跳过并返回原因（WMS 工单是单物料模型）。
- Upsert 复用 `IWmsWorkOrderService.push`（按工单号 upsert、source=MES）。
- 返回 `{queried, imported, skipped, messages[]}`。

## 3. 生产入库回写（WMS → ERP）

**触发**：现有 `POST /wms/production/{id}/sync` 或补偿任务；当 `wms.sync.erp.kingdee.enabled=true` 时，ERP 目标改为金蝶 `PRD_INSTOCK`，不再走通用 HTTP URL。

**幂等**：以 WMS `inbound_no` 作为 `PRD_INSTOCK.FBillNo`；写入前先按 `FBillNo` 查询：
- 已存在且 `FDocumentStatus=C` → 视为成功；
- 已存在且 `A/B` → 直接 Submit；
- 不存在 → Save + Submit。

**报文（Model）**：

```json
{
  "FBillNo": "<wms.inboundNo>",
  "FDate": "<yyyy-MM-dd>",
  "FPrdOrgId": "<default-org-id>",
  "FStockOrgId": "<default-org-id>",
  "FBillType": "<bill-type>",
  "FOwnerId0": "<owner-id>",
  "FOwnerTypeId": "<owner-type>",
  "FOwnerId": "<owner-id>",
  "FEntity": [{
    "FMaterialId": "<BD_MATERIAL.FMATERIALID>",
    "FInStockType": "<in-stock-type>",
    "FUnitID": "<BD_MATERIAL.FBaseUnitId>",
    "FBaseUnitId": "<BD_MATERIAL.FBaseUnitId>",
    "FOwnerTypeId": "<owner-type>",
    "FOwnerId": "<owner-id>",
    "FStockId": "<BD_STOCK.FStockId>",
    "FMoBillNo": "<wms.workOrderNo>",
    "FStockStatusId": "<stock-status-id>",
    "FKeeperTypeId": "<keeper-type>",
    "FKeeperId": "<keeper-id>",
    "FRealQty": "<good_qty>",
    "FNote": "不良数量=<bad_qty>；原因=<defect_remark>"
  }]
}
```

- 物料：`material-map` 把 WMS 编码映射为金蝶编码，再查 `BD_MATERIAL`（`FMATERIALID/FNumber/FName/FBaseUnitId`）；同编号多条时取第一条并告警。
- 仓库：按产品类型取 `stock-code.stereo/agile`，再查 `BD_STOCK.FStockId`。
- 批号：`FLot` 是批号主数据 ID；本期不自动创建批号，若无法解析则留空并在 `FNote` 记录批次号。
- 不良品：`PRD_INSTOCK` 无不良字段，`bad_qty` 只进 `FNote`；良品为 0 的记录不自动回写（返回明确失败，转人工）。
- Save 响应取 `Result.ResponseStatus.IsSuccess` 与 `Result.Number/Id`；Submit 按 `Numbers` 提交。
- 失败原因落 `sync_status/last_sync_error`（沿用现有重试与告警）。

## 4. 配置（`application-dev.yml`）

```yaml
wms:
  sync:
    erp:
      kingdee:
        enabled: "${KINGDEE_ERP_ENABLED:false}"
        base-url: "${KINGDEE_ERP_BASE_URL:}"
        acct-id: "${KINGDEE_ERP_ACCT_ID:}"
        username: "${KINGDEE_ERP_USERNAME:}"
        app-id: "${KINGDEE_ERP_APP_ID:}"
        app-secret: "${KINGDEE_ERP_APP_SECRET:}"
        allow-http: "${KINGDEE_ERP_ALLOW_HTTP:false}"
        lcid: 2052
        timeout-ms: 30000
        default-org-id: "209624"     # 101 宜昌永鑫精工科技股份有限公司
        default-org-number: "101"
        bill-type: "SCRKD01_SYS"     # 汇报入库
        owner-type: "BD_OwnerOrg"
        owner-id: "209624"
        keeper-type: "BD_KeeperOrg"
        keeper-id: "209624"
        stock-status-id: "KCZT01_SYS" # 可用
        in-stock-type: "1"
        stock-code:
          stereo: "D.02"            # 101 下暂用，待顾问确认
          agile: "D.01"             # 101 下暂用，待顾问确认
          scrap: "F.03"             # 101 下暂用，待顾问确认
        material-map:
          PBC001: "WA.2206.2201.02000430.A003"
          FG001: "WA.2205.2200.03000550.B003"
        # 产品类型必须按物料配置；FProductType 是主产品/联产品/副产品，不能区分半成品/成品
        material-product-type:
          PBC001: "半成品"
          FG001: "成品"
```

> 上述组织/单据类型/库存状态/物料映射是**测试环境实测默认值**，生产需按顾问提供的值覆盖。
> 凭据只从环境变量读取，禁止写入仓库；测试环境 HTTP 需 `KINGDEE_ERP_ALLOW_HTTP=true`，生产必须 HTTPS。

## 5. 验证

1. 单测：表单客户端（查询/Save/Submit 报文与错误解析）、工单映射与跳过、入库幂等与 Model 字段、网关分流。
2. 实测：`POST /wms/work-order/sync-kingdee?billNo=WORK00072` → WMS 出现 `WORK00072`；
   创建该工单的生产入库并 `POST /wms/production/{id}/sync` → 金蝶新增 `PRD_INSTOCK`，`FBillNo=WMS 入库单号`；
   若测试产生脏单，用 `Delete` 接口清理（注意：批号主档 `BD_BatchMainFile` 无 `Delete` 接口，只能走 UI）。
3. 回归：未启用金蝶时原模拟/HTTP 同步不受影响；全量单测通过。

## 6. 风险

- 金蝶 `FProductType` 是 **1主产品/2联产品/3副产品**，不能区分半成品/成品；WMS 产品类型必须按物料配置 `material-product-type`，配置错误会把成品写入半成品仓。
- 金蝶物料同编号多组织记录（测试环境 3 条），需按组织取数；本期取第一条并告警。
- `PRD_INSTOCK` 无不良字段，不良数量只能进备注；如业务要求独立不良单据，另立需求。
- 测试环境 HTTP 与生产 HTTPS 的切换、凭据轮换仍是上线前必做项。

## 7. 实测记录（2026-10-20）

### 7.1 工单查询（通过）

`POST /wms/work-order/sync-kingdee?billNo=WORK00072` 返回 `imported=1`，WMS 落库：

| work_order_no | material_code | product_type | plan_qty | batch_no | status | source |
|---|---|---|---|---|---|---|
| WORK00072 | PBC001 | 半成品 | 2000 | KD-WORK00072 | 生产中 | MES |

- 登录从 `Map` 改为 `String + Jackson` 解析，解决金蝶登录响应 `Content-Type: text/plain` 的反序列化失败。
- `PRD_MO` 查询字段：`FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty`。

### 7.2 生产入库回写（已通过）

`POST /wms/production/2112433913765650433/sync` 返回「同步成功」，金蝶生成并提交了 `PRD_INSTOCK`：

| 字段 | 值 |
|---|---|
| FBillNo / FDocumentStatus | `SC2112433913757261825` / **B 审核中（已提交）** |
| FMoId / FMoEntryId | 261522 / 265430 |
| **FMOMAINENTRYID** | **265430** |
| 物料 / 仓库 / 批次 | `WA.2205.2200.03000550.B003` / `D.01` / `KD-FG001173B` |
| 实收数量 | 1 |

**根因与修复：**

1. 之前只填了 `FMoEntryId`，漏了 `FMOMAINENTRYID`（表 `T_PRD_INSTOCKENTRY_A`，中文名「生产订单主产品分录」）。主产品行必须两者都填，且都指向生产订单主产品分录（FG001173 为 265430），`FIsNew=false`。
2. Submit/Delete 的报文必须是 `{"FormId":"...","data":{"Numbers":["..."]}}`；只传顶层 `Numbers` 会报「接口参数data不能为空」。
3. 提交后状态为 `B 审核中`，幂等判断把 `B/C` 都视为完成，避免重复提交。
4. Save 增加 `IsEntryBatchFill=false`。

**测试工单**：`FG001173`（FID=261522，FStatus=5 完工）无需改状态即可回写。`WORK00072` 仍是 `FStatus=7`（结算，终态），无法开工；未做任何修改。

### 7.3 数据还原（2026-10-20）

- 未修改 `WORK00072`、`FG001173` 的业务状态、领料或库存。
- 验证用生产入库单 `SC2112433913757261825` 已执行 **UnAudit → Delete**，ERP 查询为空。
- WMS 本地记录保留为「同步成功」的验收痕迹（重试次数 0）。
- 为验证批号格式创建了 `KD-FG001173A`（物料 171689）与 `KD-FG001173B`（物料 312542），均未被业务单据引用。
- 批号主档 `BD_BatchMainFile` 的 `Delete` 与 `ExcuteOperation(Delete/Forbid)` 均返回「该单据没有此接口」，**API 无法删除**；需在金蝶 UI 或由顾问清理这两个测试批号。

### 7.4 结论

- 工单查询与生产入库回写两条链路均已端到端打通；wms 模块单测 **565/565 通过**。
- 生产上线前仍需：101 组织正式仓库编码（当前 `D.02/D.01/F.03` 为暂用）、生产环境 HTTPS、测试凭据轮换。
- `PRD_MO2INSTOCK` 下推仍被「源单所选单据类型限制」拦截；本期采用 Save 方式回写，不依赖下推。

### 7.5 证据（`JeecgBoot/.superpowers/sdd/2026-10-19-yx-wms-rk-002-production-inbound/`）

- `proof-wms-synced-list.png`：WMS 生产入库列表，「同步状态=同步成功」。
- `proof-wms-synced-detail.png`：WMS 入库详情，同步成功、重试次数 0。
- `proof-kingdee-evidence-full.png`：金蝶 WebAPI 实测数据汇总（单据状态、`FMOMAINENTRYID`、还原状态）。
- `proof-wms-detail.png` / `proof-wms-list.png`：修复前的失败截图。
- `proof-kd-current.png`：金蝶 UI（101 宜昌永鑫精工科技股份有限公司、生产订单列表）。
- `proof-wms-sync-success.mp4`（22.7s）：手工用 `agent-browser record` 录制的成功流程（列表「同步成功」→ 详情「同步成功、重试 0」）。
- `proofshot-patched-success.webm` + `proofshot-patched-SUMMARY.md`：给 proofshot 打 Windows 补丁后，用 `start → 操作 → stop` 正常产出的视频（8s）与摘要，Console/Server errors 均为 0。补丁说明见同目录 `proofshot-windows-patch.md`。
- 说明：原 `proofshot` 1.6.0 在 Windows 上有两个问题——`execSync` 等待 agent-browser 常驻进程的 stdio 管道导致 `start` 误判失败；`shellQuote` 的单引号被 cmd.exe 当成会话名的一部分，导致 `exec` 操作到空白会话。两处补丁后主流程可用；`proofshot exec` 仍可能挂 60s，交互暂用 `agent-browser --session`。
