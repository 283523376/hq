# 永鑫WMS CK-002 销售订单方向反转 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers/subagent-driven-development 或 executing-plans，按 Task 推进，步骤 `- [ ]`。

**Goal:** 销售订单改为 ERP → WMS：金蝶 `SAL_SaleOrder` 只读入 WMS 快照，出库单由快照生成；删除 WMS 造单与回写链路；确认出库仍回写 `SAL_OUTSTOCK`。

**Spec:** `docs/superpowers/specs/2026-10-22-yx-wms-ck-002-sales-order-direction-design.md`

## Global Constraints

- 金蝶单据门槛：只有 `FDocumentStatus=C` 可出库；`A/B/D/Z` 拒绝并给出明确文案。
- 拉取幂等：按 `FBillNo` upsert，明细整体替换（先删后插），不产生重复行。
- 可出数量 = `order_qty - shipped_qty`，超出即整单拒绝。
- 历史列 `wms_outbound_order.sale_sync_*` / `override_*` **不删**（已应用迁移不可逆），代码不再读写。
- 所有接口 `Result<T>`；拒绝文案逐字 `Result.error(e.getMessage())`。
- 构建：`bash /d/tools/m.sh -P dev ...`；改完 wms 模块必须 `install` + 重启 JVM。
- 迁移改动后 `bash /d/tools/m.sh -P dev clean process-resources -pl jeecg-module-system/jeecg-system-start`，并 grep 启动日志「flyway执行sql脚本失败」=0。

## Task 1: 迁移 `V3.9.5_23__wms_sales_order.sql`

**Files:** `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_23__wms_sales_order.sql`

- [ ] **Step 1**：建 `wms_sales_order`、`wms_sales_order_detail`（字段见 spec §3.1）；索引 `uk_wms_sales_order_no(sales_order_no, deleted_at)`、`idx_wms_so_detail(sales_order_id)`、`idx_wms_so_status(erp_document_status)`；按钮 `wms:outbound:sales-sync`（`1910000000000000321`，挂出库页面 `1910000000000000302`）；Job 种子 `1920000000000000360`（`WmsSalesOrderSyncJob`，`0 29/15 * * * ? *`）；脚本头写回滚 SQL。
- [ ] **Step 2**：启动验证 `flyway_schema_history` `3.9.5.23 success=1`、flyway 失败计数 0、按钮与 Job 行存在。
- [ ] **Step 3**：Commit `feat(wms): CK-002 销售订单快照表与菜单任务迁移`

## Task 2: 常量 / 实体 / Mapper

- [ ] 常量：`SALES_ORDER_SYNC_JOB_ID/CLASS/CRON`、`SALES_FETCH_STATUS_PENDING/SUCCESS/FAILED`、`SALES_ORDER_STATUS_APPROVED="C"`、`SALES_ORDER_SYNC_ALERT_TITLE`、`SALES_ORDER_SYNC_DAYS=7`。
- [ ] 实体 `WmsSalesOrder`、`WmsSalesOrderDetail`（`@TableName`、`@TableLogic`、`@Accessors`）；Mapper 两个。
- [ ] Commit `feat(wms): CK-002 销售订单快照实体与常量`

## Task 3: 金蝶销售订单读取

**Files:** `sync/IWmsKingdeeSaleOrderReader.java`、`sync/WmsKingdeeSaleOrderReaderImpl.java`、`sync/WmsKingdeeSaleOrder.java`（头+行）

- [ ] `queryByBillNo(no)` / `queryApproved(beginDate, endDate, limit)`：`ExecuteBillQuery` 字段
      `FBillNo,FDocumentStatus,FDate,FCustId.FNumber,FCustId.FName,FSaleOrgId.FNumber,FNote,FMaterialId.FNumber,FMaterialId.FName,FQty,FDeliveryDate,FUnitID.FNumber`；
      按 `FBillNo` 聚合为「头 + 行」结构；`enabled=false` 返回 `mocked` 空结果。
- [ ] 单测：字段映射、按单号聚合、状态过滤、未配置分支。
- [ ] Commit `feat(wms): CK-002 金蝶销售订单读取`

## Task 4: 快照服务

**Files:** `service/IWmsSalesOrderService.java` + `impl/WmsSalesOrderServiceImpl.java`

- [ ] `syncByBillNo(no)`、`syncByWindow(begin,end)`、`preview(no)`（未命中则实时拉一次）、`remainingQty(no, materialCode)`。
- [ ] upsert：头按单号更新/插入；明细「先删后插」；`shipped_qty` 从已确认出库单汇总（`wms_outbound_order.status=已完成` 且类型=销售出库，按 `sales_order_no + material_code` 汇总 `actual_qty`）。
- [ ] 单测：幂等（连续两次拉取明细不翻倍）、剩余量=0 时预览标记不可出、失败计数与告警。
- [ ] Commit `feat(wms): CK-002 销售订单快照服务`

## Task 5: 拉取 Job

- [ ] `job/WmsSalesOrderSyncJob.java` + `WmsSalesOrderSyncJobRegistrar.java`（与既有 Registrar 同构，`ApplicationReadyEvent` 注册）；
      每轮拉「近 `SALES_ORDER_SYNC_DAYS` 天 + 状态 C」，单条失败不中断，连续失败 ≥3 告警。
- [ ] 单测：成功/失败计数、未配置跳过。
- [ ] Commit `feat(wms): CK-002 销售订单拉取任务`

## Task 6: 出库接口改造 + 拆除造单链路

- [ ] `WmsOutboundController`：新增 `GET /sales-order`、`POST /sales-order/sync`；**删除** `POST /{id}/sync-sale`。
- [ ] `WmsOutboundServiceImpl.createSales`：改为「校验快照 + 门槛 C + 数量≤剩余量 + 客户取快照」，
      不再设置 `saleSyncStatus`；确认出库不再校验「销售订单是否已同步」、不再有 `override` 门禁。
- [ ] 删除：`IWmsKingdeeSaleOrderWriter`、`WmsKingdeeSaleOrderWriterImpl`、`IWmsOutboundSyncGateway.syncSaleOrder` 及实现、
      `IWmsOutboundSyncService.syncSaleOrder/persistSaleResult/claimSaleForSync/selectSaleSyncCandidates` 及实现、
      Mapper 的 sale 相关语句、实体 `saleSync*`/`override*` 字段、Job 中的 sale 补偿分支、常量 `SALE_ORDER_SYNC_ALERT_TITLE` 旧用法。
- [ ] Commit `feat(wms): CK-002 出库改为基于 ERP 销售订单，移除造单回写`

## Task 7: 前端

- [ ] `outbound.api.ts`：删除 `syncSale`，新增 `previewSalesOrder`、`syncSalesOrder`。
- [ ] `OutboundCreateModal.vue`：销售模式改为「输入单号 → 预览 → 勾选分录/数量」，去掉客户与明细手工录入、去掉 override。
- [ ] `OutboundDetailDrawer.vue` / `outbound.data.ts`：删除 `saleSyncStatus` 列与「重新同步销售订单」按钮；确认出库不再要 override 理由。
- [ ] `pnpm build` 通过；`npx eslint src/views/wms/outbound` 无 error。
- [ ] Commit `feat(wms): CK-002 前端改为选择 ERP 销售订单`

## Task 8: 全链路验证

- [ ] 真实拉取 `XSDD1000002318`（客户 湖南屹华 / 物料 `WA.2205.2201.03000550.A003` / 100000 / 单位 `zhi`）→ 快照落库。
- [ ] 创建销售出库单（数量 1）→ 分配（敏捷仓容器）→ mock 下发 → 复核 → 确认出库。
- [ ] 金蝶侧核对：**未新增** `SAL_SaleOrder`；产生 `SAL_OUTSTOCK`（若金蝶启用）。
- [ ] 回归：非 C 单据拒绝、超量拒绝、物料不在订单拒绝。
- [ ] 全量单测 + `pnpm build`；flyway 失败 0。

## Task 9: 验收报告

- [ ] `plans/2026-10-22-yx-wms-ck-002-sales-order-direction-验收报告.md`：范围、迁移、接口、测试证据、遗留（物料未建档、单位差异、历史列停用）。

## Out of scope

- 销售订单在 WMS 内新建/修改（永久移出）；
- ERP→WMS 其他单据（调拨已由 ERP 负责、采购入库已移出范围）；
- 物料主数据自动同步（属 BD-001/KC-001 范畴）。
