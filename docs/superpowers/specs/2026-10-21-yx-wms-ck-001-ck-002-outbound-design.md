# 永鑫WMS CK-001（半成品领料出库）与 CK-002（成品销售出库）设计

日期：2026-10-21
依据：`wms-blueprint/flows.json` 中 `YX-WMS-CK-001` / `YX-WMS-CK-002`；RMS任务接口文档 V1.6；金蝶测试环境实测元数据
状态：**设计待批准**（批准前不写业务代码）

## 0. 用户已确认的约束

1. 出库对接金蝶 ERP，参考金蝶出库单据形式；找不到可参照对象时按入库的对称格式设计，并保留 mock 打通。
2. CK-002 的销售订单需同步到金蝶 **供应链 → 销售管理 → 订单处理 → 销售订单（`SAL_SaleOrder`）**。
3. CK-001 直接 `Save PRD_PickMtrl`，不走生产订单下推。
4. 复核不一致时，**支持页面上改数量 / 重扫后再确认出库**。
5. 销售订单同步失败：**告警但不阻断**，提示出库操作员，操作员确认后才能出库，否则不能出库（§5.1 步 6）。
6. **容器盘点/数据迁移不纳入本期**；上线前必须提示并完成一次（已记入项目记忆）。
7. 金蝶不可用或 PPBOM 无数据时，**允许页面手工录入领料明细**并标记 `requirement_source=手工`。

## 1. 已确认事实（实测，非推测）

### 1.1 金蝶表单与必填字段（`QueryBusinessInfo`）

**`PRD_PickMtrl` 生产领料单**

| 实体 | 必填字段 |
|---|---|
| `FBillHead` | `FDate`、`FPrdOrgId`、`FStockOrgId`、`FBillType` |
| `FEntity` | `FMaterialId`、`FStockId`、`FStockStatusId`、`FMoBillNo`、`FOwnerTypeId`、`FUnitID`、`FBaseUnitId`、`FStockUnitId`、`FKeeperTypeId`、`FKeeperId`、`FOwnerId`、`FParentOwnerTypeId`、`FParentOwnerId` |
| `FSerialSubEntity` | `FIsAppInspect` |

**`SAL_SaleOrder` 销售订单**

| 实体 | 必填字段 |
|---|---|
| `FBillHead` | `FSaleOrgId`、`FDate`、`FCustId`、`FSalerId`、`FBillTypeID` |
| `FSaleOrderEntry` | `FMaterialId`、`FUnitID`、`FPriceUnitId`、`FSettleOrgIds`、`FDeliveryDate`、`FReserveType`、`FStockUnitID`、`FOUTLMTUNIT` |
| `FSaleOrderFinance` | `FSettleCurrId` |

**`SAL_OUTSTOCK` 销售出库单**

| 实体 | 必填字段 |
|---|---|
| `FBillHead` | `FSaleOrgId`、`FDate`、`FStockOrgId`、`FCustomerID`、`FDeliveryDeptID`、`FStockerID`、`FBillTypeID` |
| `SubHeadEntity` | `FSettleOrgID`、`FSettleCurrID` |
| `FEntity` | `FMaterialID`、`FUnitID`、`FOwnerTypeID`、`FOwnerID` |

**单据类型实测（`BOS_BillType`）：**

| 表单 | 编码 | 名称 |
|---|---|---|
| `PRD_PickMtrl` | `SCLLD01_SYS` | 普通生产领料（`SCLLD02_SYS` 为倒冲领料） |
| `SAL_SaleOrder` | `XSDD01_SYS` | 标准销售订单 |
| `SAL_OUTSTOCK` | `XSCKD01_SYS` | 标准销售出库单 |

**基础资料实测（组织 101）：** `BD_Customer` 有客户数据（如 `01.001 湖南屹华科技有限公司`）；`BD_Saler` 有销售员（如 `李传东`）；`BD_Department` 有部门；`BD_Empinfo` 有员工（可作仓管员候选）。

### 1.2 生产用料清单 `PRD_PPBOM`（CK-001 需求来源）

- 单据头必填：`FMaterialID`（产品）、`FMOBillNO`（生产订单号）、`FUnitID`、`FPrdOrgId`。
- 子项实体 `FEntity` 关键字段：`FMaterialID2`（子项物料）、`FNeedQty2`（需求数量）、`FMustQty`（应发数量）、`FPickedQty`（已领数量）、`FStockID`（仓库）、`FLot`、`FUnitID2`。
- **实测查询成功**：

```
FormId=PRD_PPBOM
FieldKeys=FID,FBillNo,FMOBillNO,FMaterialID.FNumber,FMaterialID2.FNumber,FMaterialID2.FName,FNeedQty2,FMustQty,FPickedQty,FStockID.FNumber,FUnitID2.FNumber
FilterString=FMOBillNO='FG001173'
→ [261486,"PPBOM00161447","FG001173","WA.2205.2200.03000550.B003",...,10000,10000,0.0,"02.011","zhi"]
```

### 1.3 RMS 出库接口（V1.6 §3.1）

- `POST /api/v2/task/outbound`
- 请求：`{ "priority": 1~5（可选）, "details": [{ "taskNo": "必填", "containerCode": "必填", "priority": "可选", "stationCodes": ["OUT_01","OUT_02"]（可选，最多 10） }] }`
- 响应：`{ "code": 0, "msg": "success" }`
- 出库错误码：`300001 任务已存在`、`300003 工作站编码不存在`、`300004 工作站作业模式不匹配`、`300007 容器编码不存在库内`、`300008 容器已被其他出库任务锁定`；`300000` 可重试。
- 任务反馈（RMS→WMS）与入库共用：`RUNNING` / `OUTSTATION` / `FINISHED`，可带 `stationCode` / `stackCode`。

### 1.4 现有代码可复用点

| 能力 | 现状 | 复用方式 |
|---|---|---|
| 出库单/明细/分配 | 无 | 新建 3 张表 |
| 库存锁定 | 无（`wms_inventory` 只有数量） | 增列 `locked_qty`、`container_code` |
| 设备任务下发 | `WmsProductionTaskServiceImpl`（入库） | 照抄「设备故障检查 → 网关分流 → 条件回写 → 转人工告警」 |
| WCS 网关 | 只有 `dispatchInbound/cancelInbound` | 加 `dispatchOutbound/cancelOutbound` |
| RMS 网关 | 只有入库/强制入库/取消 | 加出库 `dispatchOutbound` + 优先级调整（可选） |
| 同步重试/告警 | `sync_status/retry_count/last_sync_error` + Quartz Job | 照 RK-002 模式新建出库同步 Job |
| 金蝶回写 | `WmsKingdeeInboundWriterImpl` + `WmsKingdeeFormClient` | 三个新 Writer 共用 FormClient/Properties |

## 2. 数据模型

### 2.1 新增表

**`wms_outbound_order` 出库单**

| 字段 | 类型 | 说明 |
|---|---|---|
| `outbound_no` | varchar(64) | 出库单号，唯一（含逻辑删除时间戳） |
| `outbound_type` | varchar(16) | `生产领料` / `销售出库` |
| `work_order_no` | varchar(64) | CK-001 生产工单号 |
| `sales_order_no` | varchar(64) | CK-002 客户销售订单号 |
| `customer_code` / `customer_name` | varchar(64)/(128) | 客户（CK-002） |
| `requirement_source` | varchar(16) | `金蝶用料清单` / `手工` |
| `status` | varchar(16) | 见 §3 状态机 |
| `sale_sync_status` / `sale_sync_error` / `sale_sync_time` | 同同步字段 | **销售订单**同步金蝶 `SAL_SaleOrder` 的状态（CK-002） |
| `sync_status` / `retry_count` / `last_sync_error` / `last_sync_time` / `sync_fail_count` | 同 RK-002 | **出库单**同步（CK-001 `PRD_PickMtrl` / CK-002 `SAL_OUTSTOCK`） |
| `override_by` / `override_time` / `override_reason` | varchar/datetime/varchar | 销售订单未同步时操作员「确认继续出库」的痕迹 |
| `close_reason` / `close_by` / `close_time` | 同 RK-002 | 关单 |
| `operator` / `submit_time` / `complete_time` | 同 RK-002 | 操作痕迹 |
| `remark` | varchar(255) | 备注 |
| `deleted_at` | bigint | `@TableLogic` 时间戳 |

**`wms_outbound_detail` 需求明细**

| 字段 | 说明 |
|---|---|
| `outbound_id` / `line_no` | 所属出库单、行号 |
| `material_id` / `material_code` / `material_name` | 物料 |
| `batch_no` | 批次（可空） |
| `required_qty` | 需求数量（金蝶 `FNeedQty2 - FPickedQty`，或手工录入） |
| `allocated_qty` | 已分配数量 |
| `actual_qty` | 复核/取料确认数量（默认 = allocated，可改） |
| `unit` | 单位 |
| `sync_detail` | 金蝶子项行号/来源内码（回写 `PRD_PickMtrl` 用） |
| `deleted_at` | 逻辑删除 |

**`wms_outbound_allocation` 分配明细**

| 字段 | 说明 |
|---|---|
| `detail_id` / `outbound_id` | 关联 |
| `inventory_id` | 锁定/扣减的库存行 |
| `location_id` / `location_code` | 库位（立体库） |
| `container_code` | 料箱/托盘（CK-002 RMS 出库依据） |
| `allocated_qty` | 本次分配数量 |
| `task_no` | WCS/RMS 任务号 |
| `task_status` / `task_error` | 未下发/已受理/执行中/已完成/失败 |
| `review_status` | 待复核/一致/不一致/已放行 |
| `scan_code` | 复核扫描码 |
| `review_note` | 不一致原因 |
| `deleted_at` | 逻辑删除 |

索引：`uk_wms_outbound_no(outbound_no, deleted_at)`、`idx_wms_outbound_status(status)`、`idx_wms_out_alloc_out(outbound_id)`、`idx_wms_out_alloc_task(task_no)`。

### 2.2 既有表变更：`wms_inventory`

```sql
ALTER TABLE wms_inventory
  ADD COLUMN container_code varchar(64) NOT NULL DEFAULT '' COMMENT '料箱/托盘编码（敏捷仓按箱记库存；立体库为空串）',
  ADD COLUMN locked_qty decimal(12,3) NOT NULL DEFAULT 0 COMMENT '出库分配锁定数量',
  DROP INDEX uk_wms_inv_mat_loc_batch,
  ADD UNIQUE INDEX uk_wms_inv_mat_loc_batch_container (material_id, location_id, batch_no, container_code);
```

- `upsertInventory` 的插入列与冲突键同步增加 `container_code`；既有行默认空串，CK-001 行为不变。
- 成品敏捷仓的入库改为**按容器一行**：`container_code = carrierCode`，库存与 RMS 出库一一对应。
- 所有出库分配以 `locked_qty` 锁定，避免并发出库超发；可用量 = `quantity - locked_qty`。

## 3. 状态机

**出库单 `status`**

```
待分配 ──分配成功──► 已分配 ──下发成功──► 执行中 ──设备完成──► 待复核 ──确认出库──► 已完成
   │                    │                    │                  │
   │库存不足/库位异常    │设备故障            │设备失败           │复核不一致（可改数量/重扫后再次确认）
   ▼                    ▼                    ▼                  ▼
异常待人工 ◄─────────────┴────────────────────┴────────────── 复核异常
   │
   └──关单──► 已关闭（释放 locked_qty 与库位）
```

- CK-001 的「待复核」等价于「待取料确认」——WCS 出库回执 `FINISHED` 后，领料人在页面点「取料确认」。
- `复核异常` 与 `异常待人工` 都可见、可恢复；只有「已关闭」「已完成」是终态。

**分配明细 `task_status`** 沿用现有 `未下发/已受理/执行中/已完成/失败/已关闭`。

**同步 `sync_status`** 沿用 `待同步/同步中/同步成功/同步失败/无需同步`。

## 4. CK-001 生产领料出库

### 4.1 流程与接口

| 步 | 动作 | 接口 | 关键规则 |
|---|---|---|---|
| 1 | 扫码生产工单 | `GET /wms/outbound/pick/scan?workOrderNo=` | WMS 工单不存在 → 400「工单号无效」；存在则回填工单物料/批次 |
| 1.1 | 拉取需求物料 | 同接口内部调 `PRD_PPBOM` | `FNeedQty2 - FPickedQty` > 0 的子项；金蝶未启用/查不到 → 返回空明细 + `requirementSource=手工` |
| 2 | 创建领料出库单 | `POST /wms/outbound/pick` | 明细可为空则要求前端手工补录；单号前缀 `LL` |
| 3 | 检查库存并分配 | `POST /wms/outbound/{id}/allocate` | 按 §4.2 策略；不足 → 整单不分配、状态 `异常待人工`、库存不足告警 |
| 4 | 下发堆垛机 | `POST /wms/outbound/{id}/dispatch` | 每行分配按库位生成 `WCS` 任务；设备故障 → 告警转人工 |
| 5 | WCS 回执 | 复用 `POST /wms/wcs/task-feedback` | 新增按 `wms_outbound_allocation.task_no` 认领；`FINISHED` → 单状态 `待复核` |
| 6 | 取料确认 | `POST /wms/outbound/{id}/confirm-pick` | 条件更新：扣 `quantity` 与 `locked_qty`，写领用记录；→ `已完成` + `待同步` |
| 7 | 同步 ERP/MES | 自动 + `POST /wms/outbound/{id}/sync` | 见 §6 |

### 4.2 立库货位分配策略

1. 只取 `warehouse_type = 立库` 且 `status = 空闲` 或「占用但库存属于同一物料且可用量足够」的库位。
2. 排序：**批次先进先出（`wms_inventory.create_time` 升序）→ 同一批次的库存行优先 → 库位编码升序**。
3. 单行需求跨库位拆分；每拆一段生成一条 `wms_outbound_allocation`。
4. 分配动作在事务内以条件更新锁定：`UPDATE wms_inventory SET locked_qty = locked_qty + ? WHERE id = ? AND quantity - locked_qty >= ?`，影响 0 行即视为并发抢料，回滚整单并告警。
5. 分配完成后把库位状态置 `占用`；取料确认/关单时释放回 `空闲`（仅当该库位已无库存且无其它锁定）。

### 4.3 回写金蝶 `PRD_PickMtrl`

- 幂等键：`FBillNo = WMS 出库单号`；已存在 `B/C` 直接成功，`A` 提交。
- 组织跟随生产工单：查 `PRD_MO` 的 `FPrdOrgId/FPrdOrgId.FNumber`（复用 RK-002 已实现的逻辑）。
- 单据头：`FPrdOrgId` / `FStockOrgId` = 工单组织；`FBillType` 读配置 `wms.sync.erp.kingdee.pick-bill-type`（**实测可用：`SCLLD01_SYS` 普通生产领料**）。
- 分录：`FMaterialId`（经 `material-map`）、`FStockId`（按组织 + 库区类型）、`FStockStatusId`、`FMoBillNo`、`FUnitID/FBaseUnitId/FStockUnitId`、`FOwnerTypeId/FOwnerId`、`FKeeperTypeId/FKeeperId`（= 生产组织）、`FParentOwnerTypeId/FParentOwnerId`（= 产品货主，取工单产品行的货主，默认生产组织）、`FRealQty = 本次实发数量`、`FLot`（批次非空时）。
- `FStockLocId`：若仓库启用仓位管理，复用 RK-002 的 `stock-loc` 解析；未配置明确报错。
- Save → Submit；失败落 `sync_status/last_sync_error` 并告警。
- **mock 通道**：`wms.sync.erp.kingdee.enabled=false` 时返回 `WmsOrderSyncResult.okMock()`，与 RK-002 一致。

## 5. CK-002 成品销售出库

### 5.1 流程与接口

| 步 | 动作 | 接口 | 关键规则 |
|---|---|---|---|
| 1 | 录入销售订单 | `POST /wms/outbound/sales` | 必填：客户、订单号、明细（物料+数量+要货日期）；单号前缀 `XS`；同时创建「待分配」出库单 |
| 1.1 | 同步金蝶销售订单 | 创建时同步调用，失败可重试 | `SAL_SaleOrder` Save + Submit；幂等键 `FBillNo = WMS 销售订单号`；失败 `sync_status=同步失败` 并告警 |
| 2 | 分配料箱库位 | `POST /wms/outbound/{id}/allocate` | 只取敏捷仓库存行（`container_code` 非空）；库存不足 → 告警转人工；销售订单同步失败**不阻断分配**，但页面常驻提示 |
| 3 | 下发出库任务 | `POST /wms/outbound/{id}/dispatch` | 按容器聚合调用 `POST /api/v2/task/outbound`；批次内单容器失败即整批失败（RMS 契约）；销售订单未同步时照常下发（设备不空等） |
| 4 | 设备执行 | RMS 回执 → 现有 `/wms/rms/task-feedback` | `FINISHED` → 单状态 `待复核` |
| 5 | 复核 | 页面扫码，`POST /wms/outbound/{id}/review` | 比对物料与订单；不一致 → `复核异常` + 告警 + 暂停；允许改数量/重扫后再次提交 |
| 6 | 确认出库 | `POST /wms/outbound/{id}/confirm` | 扣库存、释放剩余锁定、写 `SAL_OUTSTOCK`；→ `已完成` + `待同步`。**销售订单未同步时**：返回「需确认」标记，操作员在页面确认（带 `overrideSaleOrderSync=true` + 原因）后才放行，否则不能完成出库；确认痕迹落 `override_*` |
| 7 | 同步 ERP/MES | 自动 + `POST /wms/outbound/{id}/sync` | 见 §6 |

### 5.2 料箱分配策略

1. 只取敏捷仓（`warehouse_type=敏捷仓`）中 `container_code != ''`、`quantity - locked_qty > 0`、物料匹配的库存行。
2. 排序：`create_time` 升序（先进先出）→ 容器编码升序。
3. 按容器整箱分配：允许一箱拆给多张出库单（按数量锁定）；一张出库单可占多箱。
4. RMS 出库明细按容器聚合：同一容器多个物料需求合并为一个 `details` 项；`stationCodes` 取配置 `wms.sync.rms.outbound-stations`（默认空 = 由 RMS 决定）。

### 5.3 复核规则（用户第 4 条）

- 复核输入：每一行分配显示期望物料编码、期望数量、料箱号；仓管员扫码（物料码或料箱码）。
- 一致：`review_status=一致`，可「确认出库」。
- 不一致：`review_status=不一致`，`review_note` 记录「扫描物料 X ≠ 期望 Y」；单据转 `复核异常`，发**复核不一致告警**，暂停出库。
- 恢复：页面允许
  1. **改实际数量**（`actual_qty`，0 ≤ actual ≤ allocated）；
  2. **重扫**（覆盖 `scan_code`，重新判定）；
  3. 「放行并确认」——仅在复核人显式填写原因后允许，原因落 `review_note`。
- `actual_qty < allocated_qty` 时，差额部分回滚 `locked_qty`，不扣库存。

### 5.4 回写金蝶

**销售订单 `SAL_SaleOrder`（录入后同步）**

- 幂等键 `FBillNo = WMS 销售订单号`。
- 单据头：`FSaleOrgId/FSettleOrgId` = 配置默认组织；`FCustId`（按客户编码查 `BD_Customer`，找不到明确失败）；`FSalerId`（配置默认销售员）；`FBillTypeID`（配置，测试 `XSDD01_SYS`）。
- 分录：`FMaterialId`、`FUnitID`、`FPriceUnitId`、`FStockUnitID`、`FOUTLMTUNIT`（均取物料基本单位）、`FSettleOrgIds`、`FDeliveryDate`（要货日期）、`FReserveType`（配置，默认 `1`）、`FQty`、`FPrice`（可空）。
- 财务实体：`FSettleCurrId`（配置，测试 `PRE001` 人民币）。
- 同步时机：销售订单保存时**同步调用**；失败则单据保留（`sale_sync_status=同步失败`）并告警，**分配与下发不阻断**；操作员在「确认出库」时看到明确提示，必须显式确认（记录 `override_by/override_time/override_reason`）才能完成出库，未确认则单据停在待确认状态。后台补偿 Job 持续重试销售订单同步。

**销售出库单 `SAL_OUTSTOCK`（确认出库后同步）**

- 幂等键 `FBillNo = WMS 出库单号`。
- 单据头：`FSaleOrgId/FStockOrgId/FSettleOrgID`、`FCustomerID`、`FDeliveryDeptID`/`FStockerID`（配置默认部门/仓管员）、`FBillTypeID`（测试 `XSCKD01_SYS`）。
- 分录：`FMaterialID`、`FUnitID`、`FOwnerTypeID/FOwnerID`、`FStockId`（敏捷仓映射仓）、`FRealQty`、`FLot`、`FStockLocId`（如启用仓位）。
- 来源关联：`FSrcBillNo = 金蝶销售订单号`，`FSrcBillType = SAL_SaleOrder`（若金蝶要求来源必填；实测确认后再决定是否启用 `SAL_SALEORDER2OUTSTOCK` 下推）。
- Save → Submit；失败按补偿 Job 重试。

## 6. 同步与重试架构

```
出库确认 ──► IWmsOutboundSyncService.sync(orderId)
                ├─ 金蝶 Writer（enabled=true）:
                │    生产领料 → PRD_PickMtrl
                │    销售出库 → SAL_OUTSTOCK（+ 销售订单 SAL_SaleOrder 单独同步）
                └─ MES HTTP Webhook（配置了 wms.sync.mes.outbound-url 时）
```

- 沿用 `WmsOrderSyncAttempt` + 指数退避（1s/2s/4s，最多 3 次）。
- 新增 `WmsOutboundSyncRetryJob`，cron `0 17/5 * * * ? *`（与订单 :03、设备 :07、采购入库 :09、生产入库 :13 错峰），任务 ID `1920000000000000300`。
- `sync_status=无需同步`：单据在确认出库前关闭。
- 同步失败告警标题 `WMS出库同步失败告警`；同一单据连续失败 ≥3 次才告警，避免噪音（沿用 RK-002 的 `sync_fail_count`）。

## 7. 告警（用户约束：四类异常必须中断并告警）

| 场景 | 单据状态 | 告警标题 | 恢复方式 |
|---|---|---|---|
| 库存不足 | 异常待人工 | `WMS出库库存不足告警` | 补库存后重新分配 / 关单 |
| 库位/料箱异常（库位不存在、故障、非目标库区、容器不在库） | 异常待人工 | `WMS出库库位异常告警` | 修复基础数据后重新分配 |
| 设备故障（WCS/RMS 报错或设备状态=故障） | 异常待人工 | `WMS出库设备异常告警` | 修复设备后重新下发 / 转人工完成 |
| 复核不一致 | 复核异常 | `WMS出库复核不一致告警` | 改数量 / 重扫 / 放行 |
| 销售订单同步失败（CK-002） | 单据继续流转，`sale_sync_status=同步失败` | `WMS销售订单同步失败告警` | **不阻断分配/下发**；确认出库时操作员显式确认后方可出库；后台自动重试 |
| 同步失败（出库单） | 同步失败（单据仍已完成） | `WMS出库同步失败告警` | 自动重试 + 手工重新同步 |

所有告警经 `ISysBaseAPI.sendSysAnnouncement` 投递到 `wms.sync.alert.to-user`（默认 admin），并写入单据 `last_error` 字段供页面展示。

## 8. 接口清单（`org.jeecg.modules.wms.controller.WmsOutboundController`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/wms/outbound/pick/scan?workOrderNo=` | `wms:outbound:scan` | CK-001 扫码校验 + 需求明细 |
| POST | `/wms/outbound/pick` | `wms:outbound:add` | 创建领料出库单 |
| POST | `/wms/outbound/sales` | `wms:outbound:sales` | 创建销售订单 + 出库单 |
| GET | `/wms/outbound/list` | `wms:outbound:list` | 分页 |
| GET | `/wms/outbound/{id}` | `wms:outbound:list` | 详情（含分配与复核） |
| POST | `/wms/outbound/{id}/allocate` | `wms:outbound:allocate` | 分配库位/料箱 |
| POST | `/wms/outbound/{id}/dispatch` | `wms:outbound:dispatch` | 下发 WCS/RMS |
| POST | `/wms/outbound/{id}/review` | `wms:outbound:review` | 复核（CK-002） |
| POST | `/wms/outbound/{id}/confirm-pick` | `wms:outbound:confirm` | 取料确认（CK-001） |
| POST | `/wms/outbound/{id}/confirm` | `wms:outbound:confirm` | 确认出库（CK-002） |
| POST | `/wms/outbound/{id}/sync` | `wms:outbound:sync` | 手工重新同步出库单（`PRD_PickMtrl`/`SAL_OUTSTOCK`） |
| POST | `/wms/outbound/{id}/sync-sale` | `wms:outbound:sync` | 手工重新同步销售订单（`SAL_SaleOrder`） |
| POST | `/wms/outbound/{id}/close` | `wms:outbound:close` | 关单（释放锁定） |

设备回执复用现有免登录回调：`/wms/wcs/task-feedback`、`/wms/rms/task-feedback`，按任务号查 `wms_outbound_allocation`（先查入库表，未命中再查出库表）。

## 9. 前端

新增 `src/views/wms/outbound/`：

- `index.vue`：出库单列表（类型、单号、工单/销售订单、状态、同步状态、操作）。
- `pick.vue` / `sales.vue`：新建表单。
- `detail.vue`：详情 + 分配明细 + 任务状态 + 复核面板（扫码输入、实际数量、放行原因）+ 操作按钮。
- 菜单挂在「出库管理」目录（`sort=92`，与入库管理 91 平级），按钮权限与 §8 一一对应。

## 10. 配置新增（`application-dev.yml`）

```yaml
wms:
  sync:
    wcs:
      outbound-url: "${WMS_WCS_OUTBOUND_URL:}"   # 未配置 = mock 受理
    rms:
      outbound-stations: "${WMS_RMS_OUTBOUND_STATIONS:}"  # 逗号分隔，如 OUT_01,OUT_02
      outbound-priority: 1
    mes:
      outbound-url: "${WMS_MES_OUTBOUND_URL:}"   # 未配置则只同步金蝶
    erp:
      kingdee:
        pick-bill-type: "${KINGDEE_PICK_BILL_TYPE:SCLLD01_SYS}"
        sale-order-bill-type: "${KINGDEE_SALE_ORDER_BILL_TYPE:XSDD01_SYS}"
        out-stock-bill-type: "${KINGDEE_OUT_STOCK_BILL_TYPE:XSCKD01_SYS}"
        sale-org-id: "${KINGDEE_SALE_ORG_ID:209624}"
        sale-org-number: "${KINGDEE_SALE_ORG_NUMBER:101}"
        saler-id: "${KINGDEE_SALER_ID:}"
        settle-currency: "${KINGDEE_SETTLE_CURRENCY:PRE001}"
        delivery-dept-id: "${KINGDEE_DELIVERY_DEPT_ID:}"
        stocker-id: "${KINGDEE_STOCKER_ID:}"
```

> 单据类型均已实测存在：`SCLLD01_SYS` 普通生产领料、`XSDD01_SYS` 标准销售订单、`XSCKD01_SYS` 标准销售出库单。
> `FSaleOrgId`/`FSettleOrgId` 等组织默认 101（内码 209624）；凭据仍只从环境变量读取。

## 11. 测试与验收

### 11.1 单测（目标 ≥ 30 个新用例）

- 分配：FIFO、跨库位拆分、并发条件更新 0 行回滚、库存不足告警。
- 状态机：非法迁移全部拒绝；关单释放锁定。
- 复核：一致/不一致/改数量/重扫/放行原因必填。
- 金蝶 Writer：三个表单的必填字段、幂等（C/B/A）、组织跟随、mock 分支。
- RMS 客户端：出库报文结构（含 stationCodes）、错误码映射（300001 幂等、300003/4/7/8 转人工、300000 重试）。

### 11.2 端到端

1. **CK-001（mock 设备 + 金蝶测试环境）**：扫码 `FG001173` → 拉取 PPBOM 子项 → 分配立库 → 下发（未配置 WCS 则 mock）→ 取料确认 → 库存扣减 → 金蝶出现 `PRD_PickMtrl`（`FBillNo=WMS 出库单号`）→ 反审核删除。
2. **CK-002（mock RMS + 金蝶）**：录入销售订单 → 金蝶出现 `SAL_SaleOrder` → 分配 `MITA_*` 容器 → `POST /api/v2/task/outbound`（mock）→ 模拟 FINISHED 回执 → 复核（先制造不一致，再改数量/重扫放行）→ 确认出库 → 库存扣减 → 金蝶出现 `SAL_OUTSTOCK`。
3. **异常回归**：库存不足、库位故障、设备故障、RMS 300007/300008、复核不一致、同步失败各触发一次，确认单据状态与告警。
4. 全量单测 + 前端 `pnpm build`；Flyway 启动日志无「flyway执行sql脚本失败」。

## 12. 风险与待确认

1. **单据类型** 已实测存在（§1.1），无需再确认；若生产环境账套不同，仍需按同编码核对。
2. **销售订单必填的 `FSalerId`/`FDeliveryDeptID`/`FStockerID`** 需要有效的业务员/部门/仓管员基础资料；测试期先配固定值（`BD_Saler`/`BD_Department`/`BD_Empinfo` 均有数据），生产期需映射。
3. **`PRD_PickMtrl` 的 `FParentOwnerTypeId/FParentOwnerId`（产品货主）** 取值需实测：默认生产组织，若金蝶校验货主必须等于产品库存货主，再调整。
4. **来源关联**：`SAL_OUTSTOCK` 是否必须从销售订单下推；本期先 Save + `FSrcBillNo`，若金蝶拒绝再改下推。
5. **容器库存**是新增维度：历史敏捷仓库存行 `container_code=''`，无法参与 CK-002 出库。**本期不做容器盘点/数据迁移**（用户 2026-10-21 明确），测试环境用新入库数据验证；**上线前必须提示并完成一次容器盘点/数据迁移**（已记入项目记忆）。
6. **MES 同步**：MES 是 ERP 子模块，本期以金蝶为准；HTTP webhook 仅作可选扩展，未配置时不产生失败。
7. **并发**：同一容器被两张出库单同时分配时，靠 `locked_qty` 条件更新保证不超发；关单/复核差额必须回滚锁定，需单测覆盖。

## 13. 设计自检

- [x] 四个用户约束全部落到具体章节（§0 → §4.3/§5.4/§5.3）。
- [x] 每类异常都有状态、告警标题、恢复路径（§7）。
- [x] 金蝶必填字段来自 `QueryBusinessInfo` 实测，非猜测。
- [x] RMS 出库报文与错误码来自 V1.6 文档原文。
- [x] 复用 RK-002 的组织跟随、仓位解析、同步重试、设备分流模式，不重复造轮子。
- [x] mock 通道明确：WCS/RMS 未配地址、金蝶未启用都能闭环演示。
- [x] 数据变更带迁移脚本与回滚影响说明（§2.2）。
- [x] 用户三条补充决策（§0 第 5~7 条）全部落入具体行为：不阻断同步 + 操作员确认出库、容器迁移移出本期、手工补录兜底。
- [x] 「容器盘点/数据迁移」作为上线前检查项写入项目记忆。
- [ ] 待用户批准后进入 plan，再写代码。
