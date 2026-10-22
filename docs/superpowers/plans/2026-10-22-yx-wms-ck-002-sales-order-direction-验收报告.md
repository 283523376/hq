# CK-002 销售订单方向反转 —— 验收报告（ERP → WMS）

- 日期：2026-10-22
- 设计：`docs/superpowers/specs/2026-10-22-yx-wms-ck-002-sales-order-direction-design.md`
- 计划：`docs/superpowers/plans/2026-10-22-yx-wms-ck-002-sales-order-direction.md`
- 状态：**已实施并验收通过**

---

## 1. 交付范围

| 层 | 内容 |
|---|---|
| 数据库 | `V3.9.5_23__wms_sales_order.sql`：`wms_sales_order`、`wms_sales_order_detail`、按钮 `wms:outbound:sales-sync`、Job 种子 `1920000000000000360` |
| 金蝶读取 | `IWmsKingdeeSaleOrderReader` + `WmsKingdeeSaleOrderReaderImpl`（`SAL_SaleOrder` 表头+分录，按 `FBillNo` 聚合） |
| 快照服务 | `IWmsSalesOrderService` + 实现：按单号/时间窗拉取、幂等 upsert（分录先删后插）、剩余可出量、`shipped_qty` 刷新 |
| 定时任务 | `WmsSalesOrderSyncJob` + Registrar（cron `0 29/15 * * * ? *`，近 7 天 + 仅 C） |
| 接口 | `GET /wms/outbound/sales-order?no=`（预览）、`POST /wms/outbound/sales-order/sync`（按单号/时间段）、`POST /wms/outbound/sales`（改为选择 ERP 订单） |
| 拆除 | `IWmsKingdeeSaleOrderWriter` + 实现（删除）、`syncSaleOrder`/`persistSaleResult`/`claimSaleForSync`/`selectSaleSyncCandidates`（删除）、`POST /{id}/sync-sale`（删除）、`overrideSaleOrderSync/overrideReason/overrideBy/overrideTime`（DTO 与实体字段删除，DB 列保留停用）、确认出库的「销售订单未同步」门禁（删除） |
| 前端 | 销售模式改为「输入单号 → 从 ERP 拉取 → 带出分录」；删除「销售单同步」列、销售单告警条、越权确认弹窗与「重新同步销售订单」按钮 |

## 2. 数据库变更

`V3.9.5_23`（`success=1`）：

- `wms_sales_order`：`sales_order_no`（唯一，含 `deleted_at`）、客户/销售组织/订单日期/金蝶状态/最早要货日期、拉取状态与失败计数
- `wms_sales_order_detail`：`material_id/code/name`、`unit`、`order_qty`、`delivery_date`、`shipped_qty`
- 按钮：`1910000000000000321`（出库-销售订单同步 → `wms:outbound:sales-sync`）
- Job：`1920000000000000360` → `WmsSalesOrderSyncJob` / `0 29/15 * * * ? *`
- **历史列保留**：`wms_outbound_order.sale_sync_*`、`override_*` 不删（已应用迁移不可逆 + 历史留痕），代码不再读写

## 3. 验证证据

### 3.1 自动化

| 项 | 结果 |
|---|---|
| 后端全模块单测 | **748/748 pass**（新增 reader 5 例、快照服务 4 例、Job 2 例；删除 sale writer 测试；改造 create/confirm/controller/job 既有用例） |
| 前端 | `pnpm build` 成功；`npx eslint src/views/wms/outbound` 无 error |
| 迁移 | `3.9.5.23 success=1`；启动日志「flyway执行sql脚本失败」= 0 |

### 3.2 真实金蝶联调（测试环境，2026-10-22）

| 步 | 命令/动作 | 结果 |
|---|---|---|
| 1 | `GET /wms/outbound/sales-order?no=XSDD1010003821` | 200；快照落库，客户「深圳市三德冠精密电路科技有限公司（03.081）」、销售组织 101、状态 C、要货日期 2026-08-06；分录 `WA.2205.2200.03000550.B003` 订单量 2000、已出 0、剩余 2000；**物料经反向映射解析到 WMS 物料 FG001** |
| 2 | `POST /wms/outbound/sales`（数量 1） | 200；出库单 `XS2113174369357070337`，客户/日期取自快照，状态「待分配」 |
| 3 | 超量（99999 > 剩余 2000） | 500「物料[WA.…]申请数量 99999 超过订单剩余可出数量 2000」 |
| 4 | 订单中不存在的物料（PBC001） | 500「物料[PBC001]不在销售订单 XSDD1010003821 中」 |
| 5 | 不存在的订单号 | 500「金蝶未找到销售订单：XSDD-NOT-EXIST」 |
| 6 | **方向验证**：金蝶 `SAL_SaleOrder`（C 状态）数量 | 操作前后均 **1999**，**未新增任何销售订单** |

> 测试出库单已清理（`wms_outbound_order` 该订单号 0 行）。快照保留（真实 ERP 数据，后续出库可直接用）。

### 3.3 拉取任务

- Job 已注册（`sys_quartz_job.1920000000000000360`，cron `0 29/15 * * * ? *`）
- 首轮运行后快照表出现 7 条近期「已审核」订单（`XSDD1000002313~2318`、`XSDD1010003821`），`fetch_status=成功`

## 4. 与计划的偏差

1. **快照服务与出库服务解耦**：`createSales` 通过 `IWmsSalesOrderService.requireApprovedDetails()` 取快照，不再手工录入客户/明细；
2. **物料编码双向映射**：快照存金蝶编码，落出库明细前经 `material-map` 反向映射回 WMS 编码（`WA.…B003 → FG001`），否则 FK 校验必失败；
3. **历史列不删**：`sale_sync_*` / `override_*` 保留停用（迁移不可逆 + 历史留痕），已在 spec 记录；
4. **`announceFailureIfNeeded` 签名简化**：去掉 `saleOrder` 参数（销售订单不再有同步状态）。

## 5. 遗留

| 项 | 说明 |
|---|---|
| 物料未建档 | 订单物料若不在 WMS `wms_material` 且 `material-map` 未配置，将拒绝出库并提示；处理方式：补建物料或加映射（属 BD-001 范畴） |
| 单位差异 | 快照记录金蝶单位（如 `zhi`），WMS 出库按物料单位；若两者不一致需在物料映射层统一 |
| 订单变更 | 下次拉取会覆盖快照（分录先删后插），已出库量由 `shipped_qty` 抵消，不会重复出库 |
| 前端联调 | 已 `pnpm build` 通过；**未做真实浏览器逐页点击**（无前端 dev server 常驻） |
| 反向拉取频率 | 近 7 天窗口、每 15 分钟；历史订单需要时用「按单号拉取」补 |

## 6. 回滚

见 `V3.9.5_23__wms_sales_order.sql` 脚本头注释（删两表 + 删按钮/Job 行）。代码按提交逆序 revert；`wms_outbound_order` 未改结构，无需回滚。
