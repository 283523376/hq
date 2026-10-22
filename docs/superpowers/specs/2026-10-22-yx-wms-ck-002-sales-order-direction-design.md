# 永鑫WMS CK-002 销售订单方向反转设计（ERP → WMS）

- 日期：2026-10-22
- 依据：`specs/2026-10-21-yx-wms-v22-impact-analysis.md` §0 决策 #4、§2.5
- 现状：`POST /wms/outbound/sales` 手工录入销售订单，创建后立即回写金蝶 `SAL_SaleOrder`，
  并有 `saleSyncStatus` / `/sync-sale` / `overrideSaleOrderSync` 一整套「WMS 造单」逻辑
- 状态：评审中（待实施）

---

## 1. 要解决什么

v22 决策 #4 已确认：**销售订单方向改为 ERP → WMS**。WMS 不再创建、修改、回写销售订单，
只做「读取已审核的 ERP 销售订单 → 生成出库任务 → 执行出库 → 回写 `SAL_OUTSTOCK`」。

收益：客户/物料/数量以 ERP 为准，避免双写冲突；WMS 侧出现「销售订单与 ERP 不一致」的可能性归零。

## 2. 已确认事实（金蝶测试环境实测 2026-10-22）

`QueryBusinessInfo(SAL_SaleOrder)` 实测结构：

| 层 | 实体 | 可用字段（节选） |
|---|---|---|
| 表头 | `FBillHead` | `FBillNo`、`FDocumentStatus`、`FDate`、`FCustId`、`FSaleOrgId`、`FSalerId`、`FNote` |
| 分录 | `FSaleOrderEntry` | `FMaterialId`、`FUnitID`、`FQty`、`FDeliveryDate` |

`ExecuteBillQuery` 实测可通过（按 `FDocumentStatus='C'` 取 10 行）：

```
["XSDD1000002318","C","2026-10-20","01.001","湖南屹华科技有限公司","100",
 "WA.2205.2201.03000550.A003","UW0.30*5.5-C",100000.0,"2026-10-30","zhi"]
```

即：字段键 `FCustId.FNumber/FCustId.FName/FSaleOrgId.FNumber/FMaterialId.FNumber/FMaterialId.FName/FQty/FDeliveryDate/FUnitID.FNumber` 全部可用。

## 3. 设计

### 3.1 数据模型（新表，快照）

**`wms_sales_order`（销售订单快照）**

| 字段 | 说明 |
|---|---|
| `sales_order_no` | 金蝶单号，唯一（含 `deleted_at`） |
| `customer_code` / `customer_name` | 客户（`FCustId`） |
| `sale_org_number` | 销售组织（`FSaleOrgId.FNumber`） |
| `order_date` | 订单日期（`FDate`） |
| `erp_document_status` | 金蝶原始状态 A/B/C/D/Z |
| `delivery_date` | 最早要货日期（分录 `FDeliveryDate` 最小值，展示用） |
| `fetch_status` / `fetch_error` / `fetch_time` | 拉取状态：`待拉取/成功/失败`、失败原因、最近拉取时间 |
| `fetch_fail_count` | 连续失败次数（≥3 告警） |
| `remark`（`FNote`）、审计列、`deleted_at` | — |

**`wms_sales_order_detail`（订单分录快照）**

| 字段 | 说明 |
|---|---|
| `sales_order_id` / `line_no` | 关联与行号 |
| `material_code` / `material_name` / `material_id` | 物料（`material_id` 由 WMS 物料表按编码反查，可为空=未建档） |
| `unit` | 单位（`FUnitID.FNumber`，如 `zhi`） |
| `order_qty` | 订单数量 |
| `delivery_date` | 分录要货日期 |
| `shipped_qty` | 已出库数量（WMS 侧累计：已确认出库的销售出库单按物料汇总） |

**`wms_outbound_order`**：保留现有字段（含 `sales_order_no`）；**不删除历史列** `sale_sync_*` / `override_*`
（已应用迁移不可逆 + 历史留痕），代码不再读写，文档标注 deprecated。

### 3.2 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/wms/outbound/sales-order?no=` | `wms:outbound:sales` | 预览：先查快照，未命中则实时拉一次金蝶；返回客户/明细/剩余可出数量 |
| POST | `/wms/outbound/sales-order/sync` | `wms:outbound:sales` | 手工拉取：`{no}` 或 `{beginDate,endDate}`，幂等 upsert |
| POST | `/wms/outbound/sales` | `wms:outbound:sales` | **改为**：只传 `salesOrderNo` + 明细数量（物料必须属于该订单） |
| ~~POST~~ | ~~`/wms/outbound/{id}/sync-sale`~~ | — | **删除**（不再回写销售订单） |

### 3.3 业务规则

1. **门槛**：只有 `erp_document_status='C'` 的销售订单可出库；`A/B/D/Z` 一律拒绝并给出明确文案。
2. **明细来源**：出库明细必须来自订单分录；数量 `0 < qty ≤ order_qty - shipped_qty`（剩余量），
   超出时整单拒绝并报出剩余量。
3. **幂等**：拉取按 `FBillNo` upsert（明细整体替换，避免脏行）；出库单创建仍是一单一号，可对同一订单多次出库直到剩余量为 0。
4. **删除旧的造单链路**：`IWmsKingdeeSaleOrderWriter`/实现类、`syncSaleOrder`、`persistSaleResult`、
   `/sync-sale`、`overrideSaleOrderSync` 门禁与 `saleSyncStatus/saleSyncError/saleSyncTime` 的读写全部移除。
5. **保留**：确认出库时回写 `SAL_OUTSTOCK`（这是「WMS → ERP 出库结果」，方向不变）。
6. **拉取 Job**：`WmsSalesOrderSyncJob`（cron `0 29/15 * * * ? *`）：按「近 N 天 + 状态 C」拉取并 upsert；
   失败计数与告警（≥3 次）；未配置金蝶时跳过（与工单 Job 同构）。

### 3.4 前端

- `OutboundCreateModal.vue`（销售模式）：**删除**客户/明细手工录入，改为「输入/选择 ERP 销售订单号 → 预览 → 勾选分录 + 调整数量」；
- `OutboundDetailDrawer.vue`、`outbound.data.ts`：删除 `saleSyncStatus` 展示与「重新同步销售订单」按钮；
- 新增「销售订单同步」按钮（列表页），调 `/sales-order/sync`（按单号/时间段）。

### 3.5 迁移与回滚

`V3.9.5_23__wms_sales_order.sql`：

- 建 `wms_sales_order` / `wms_sales_order_detail`；索引 `uk_wms_sales_order_no(sales_order_no, deleted_at)`、
  `idx_wms_so_detail(sales_order_id)`、`idx_wms_so_status(erp_document_status)`；
- 按钮权限 `wms:outbound:sales-sync`（挂出库管理页面）；
- Job 种子 `1920000000000000360`（`WmsSalesOrderSyncJob`，cron `0 29/15 * * * ? *`）；
- 回滚：删两张表 + 删该按钮/Job 行（脚本头注释给出 SQL）；`wms_outbound_order` 不改结构，无需回滚。

## 4. 测试

- 单测：reader 字段映射与状态过滤；快照 upsert 幂等（重复拉取不产生重复明细）；剩余量计算；
  `createSales` 门槛（非 C 拒绝、超量拒绝、物料不在订单拒绝）；Job 失败计数与告警。
- E2E：真实拉取 `XSDD1000002318`（客户 湖南屹华 / 物料 `WA.2205...` / 数量 100000）→ 建出库单 → 分配（敏捷仓按容器）→ mock 下发 → 复核 → 确认出库 → 回写 `SAL_OUTSTOCK`（金蝶侧核对单据）。
- 回归：确认 **不再** 产生新的 `SAL_SaleOrder`（金蝶侧按时间段查数量不变）。

## 5. 风险与待确认

| 项 | 说明 |
|---|---|
| 物料未建档 | 订单物料可能不在 WMS `wms_material`（如成品编码未同步）→ 出库时按 `material_code` 现场反查，查不到直接拒绝并提示「先在物料管理建档（或同步物料）」 |
| 数量单位差异 | 金蝶基本单位（如 `zhi`）与 WMS 出库单位需一致；WMS 物料单位若是 `PCS` 需在物料映射中确认（本设计按订单单位落库） |
| 历史数据 | `wms_outbound_order.sale_sync_*` / `override_*` 列保留但停用；旧单据页面不再展示其值 |
| 订单变更 | ERP 改单后快照按下次拉取覆盖；已出库部分通过 `shipped_qty` 抵消，不会重复出库 |
