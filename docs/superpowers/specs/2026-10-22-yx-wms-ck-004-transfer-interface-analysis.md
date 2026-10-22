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
| 金蝶调拨单能力 | 支持调出/调入**仓库 + 仓位**、批号、数量、货主/保管者；实测样本 `ZJDB000001` 已审核（C） |
| 米塔移库 API | **`POST /api/v2/task/transfer`（搬运任务下发）**，但只支持“把容器搬到指定**区域**（`zoneCode`：`EMERGENCY_OUT`/`PRE_ORDER`）”，**不能指定目标库位 `stackCode`** |
| 关键缺口 | 金蝶可精确到“仓位”，米塔 V1.6 只能到“区域”；CK-004 若要求“调入库位”精确落位，现有 RMS 接口不足 |
| 建议 | 同仓/区域级搬运用 `task/transfer`；跨仓或精确到库位的调拨用“源出库 + 目标入库”组合，或由米塔提供新接口；金蝶侧统一用 `STK_TransferDirect` |

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

### 2.6 待金蝶确认

1. **跨组织调拨**：样本是 `InnerOrgTransfer`（同组织）。100/101 跨组织时 `FTransferBizType`/`FTransferBizTypeId` 取什么值？
2. **仓管员**：`FStockerId` 必填，需给一个测试/生产仓管员编码；建议加配置 `stocker-number`。
3. **仓位映射**：101 的 D.01/D.02 未启用仓位管理，WMS 的库位是否只做本地记录、不写 ERP 仓位？100 的 02.001/02.002 启用仓位，需提供“WMS 库位 ↔ 金蝶仓位内码”映射表。
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

### 3.3 CK-004 可选方案

| 方案 | 做法 | 适用 | 风险 |
|---|---|---|---|
| **A. 区域搬运（现成）** | 调 `task/transfer`，`zoneCode` 指向目标区域 | 同仓、目标只到区域级（如“预出库区/紧急出库区”） | 不能精确到库位；区域编码需米塔配置 |
| **B. 源出库 + 目标入库（组合）** | 源仓 `task/outbound` → 工作站；目标仓 `task/inbound` | 跨仓、需要精确目标库位 | 两段任务、跨 RMS 交接未定义；中间状态需 WMS 编排 |
| **C. 米塔新增移库接口** | 要求 `task/transfer` 支持 `warehouse + stackCode` 或新增 `task/move` | 精确库位调拨 | 依赖米塔排期 |

**建议**：CK-004 先按 B 做跨仓/精确库位，A 做同仓区域搬运；同时向米塔提 C 的需求。若业务只要求“调拨到目标仓/区域”，A 即可，不必等新接口。

## 4. CK-004 落地口径建议

### 4.1 WMS 本地模型

`wms_transfer_order`：

- `transfer_no`（唯一）、`source`（WMS/ERP）、`erp_bill_no`、`erp_document_status`
- `out_warehouse_id/out_location_id`、`in_warehouse_id/in_location_id`
- `status`：待分配/已分配/执行中/已完成/异常/已关闭
- `sync_status/retry_count/sync_fail_count/last_sync_error/last_sync_time`
- `operator/complete_time`、审计列

`wms_transfer_detail`：物料/批次/容器/数量/已分配/实发。

### 4.2 同步方向

| 方向 | 动作 |
|---|---|
| ERP→WMS | 查 `STK_TransferDirect`（`FDocumentStatus=C`），按 `FBillNo` upsert；映射组织/仓库/物料/批号/数量；仓位内码按映射表转 WMS 库位 |
| WMS→ERP | 完成移库后 `Save + Submit` `STK_TransferDirect`；幂等键 `FBillNo`；失败重试/告警 |
| WMS→RMS | 按 §3.3 选 A 或 B；任务号写 `wms_transfer_order`/明细；`task-feedback` 更新执行状态 |
| RMS→WMS | `料箱位置更新` 回调更新 `wms_inventory` 位置；`task-feedback` 更新任务状态 |

### 4.3 需要新增的配置

- `wms.sync.erp.kingdee.stocker-number`：仓管员编码（`FStockerId` 必填）。
- `wms.sync.erp.kingdee.transfer-biz-type`：默认 `InnerOrgTransfer`；跨组织另配。
- `wms.sync.erp.kingdee.transfer-bill-type`：默认 `ZJDB01_SYS`。
- WMS 库位 ↔ 金蝶仓位内码映射（仅启用仓位管理的仓库需要）。
- WMS 仓库 ↔ 金蝶仓库编码映射（可复用 `stock-code-by-org`）。

## 5. 待确认清单

| # | 问题 | 责任方 | 影响 |
|---|---|---|---|
| 1 | 跨组织调拨的 `FTransferBizType`/`FTransferBizTypeId` 取值 | 金蝶 | WMS→ERP 保存能否成功 |
| 2 | `FStockerId` 用哪个仓管员（测试/生产） | 金蝶/业务 | 必填字段 |
| 3 | 101 的 D.01/D.02 是否启用仓位；WMS 库位是否写 ERP 仓位 | 金蝶/业务 | 仓位映射与精度 |
| 4 | RMS `zoneCode` 是否可配置业务区域，是否支持目标 `stackCode` 或跨仓 | 米塔 | CK-004 用 A/B/C 哪种方案 |
| 5 | 跨仓两段任务（outbound+inbound）的容器交接与中间状态 | 米塔/业务 | 方案 B 可行性 |

## 6. 证据与复现

- 金蝶登录：`LoginByAppSecret`，账套 `6a9aaaadaf7ee6`、用户 `WMS`。
- 候选表单探测：`ExecuteBillQuery`（`STK_TransferDirect` 返回 `ZJDB000001`/`C`）。
- 必填字段：`QueryBusinessInfo`（`STK_TransferDirect`）。
- 真实样本：`View` `STK_TransferDirect/ZJDB000001`（113 条明细；`TransferBizType=InnerOrgTransfer`、`TransferDirect=GENERAL`、`SrcStockLocId_Id=100003`、`DestStockLocId_Id=100002`）。
- 米塔：`RMS任务接口文档-V1.6.pdf` §4.3（搬运任务下发）、§4.4（库位解绑）、§4.5（料箱位置更新）。
