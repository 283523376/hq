# 永鑫WMS 蓝图 V2.2 影响面分析（代码 / 设计 / 计划）

日期：2026-10-21
依据：`wms-blueprint/out/永鑫WMS软件蓝图V2.2（评审中）-20261021.docx`、`wms-blueprint/flows.json`（V2.2）、后端/前端全量只读审计
状态：**分析已完成，5 项关键决策已确认；待按优先级进入设计与实施**

## 0. 已确认决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | BD-002 工单状态 | 采用双字段：`erp_document_status`（金蝶原始状态，只读留痕）+ `work_order_status`（WMS 可执行状态，同步成功置「已下达」） |
| 2 | MES 推送端点 `/wms/work-order/push` | **保留**，作为兼容入口；来源记 `MES`，不得覆盖 ERP 来源 |
| 3 | RK-005 形态 | **保持由生产入库承载 + 只读不良品台账**，不新建独立不良品入库单据 |
| 4 | CK-002 销售订单 | **新建 `wms_sales_order` / `wms_sales_order_detail` 快照表**，销售订单方向改为 ERP → WMS |
| 5 | CK-004 外部接口 | 金蝶调拨单 FormId/字段由**金蝶**确认；WCS/RMS 移库任务 API 由**米塔机器人 RMS**确认 |

---

## 1. 审计范围与方法

- 后端：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`（Controller / Service / Mapper / Sync / Job / DDL）。
- 前端：`jeecgboot-vue3/src/views/wms`（页面 / 组件 / api / data），菜单与权限以 Flyway 脚本为准。
- 文档：`docs/superpowers/specs` 11 份、`plans` 20 份，共 31 份。
- 逐条对照 `flows.json` 的 16 条流程；关键结论已抽查复核（工单端点、状态常量、RK-002 状态校验、CK-002 回写方向、调拨/库存模块缺失）。

### 1.1 状态总览

| 判定 | 数量 | 流程 |
|---|---|---|
| 已满足 | 2 | BD-001、BD-004 |
| 需改造 | 11 | BD-002、BD-003、RK-002、RK-005、CK-001、CK-002、KC-001、SB-001、SB-002、XT-001、XT-002 |
| 未实现 | 3 | CK-004、KC-002、KC-004 |

---

## 2. V2.2 直接引起的必改项（6 条）

### 2.1 BD-002 生产工单管理（P0，根节点）

**现状**：已有金蝶 `PRD_MO` 同步，但「同步」与「手工新建 / 审核 / 下达」并存；来源固定写 `MES`；状态只有 `待生产/生产中/已完成/已关闭`；同步为手工触发、无 Job、无同步状态字段。

**代码改动**

| 层 | 文件 / 动作 |
|---|---|
| 后端接口 | `WmsWorkOrderController`：删除 `/add`、`/edit`、`DELETE /{id}`；`/sync-kingdee` 增加 `billNo`、`startDate`、`endDate`、`top`；保留 `/push` 兼容 MES；新增独立权限 `wms:work-order:sync` |
| 后端服务 | `WmsWorkOrderServiceImpl`：删除手工写路径；`push()` 新建时按调用方写 `source`（ERP/MES），更新时不覆盖来源；`scan()` 的 MES 回源改为 ERP 回源；状态校验接受「已下达」 |
| 金蝶同步 | `WmsKingdeeWorkOrderSyncServiceImpl`：查询字段增加 `FDate`；支持单号 / 时间范围；保存 `erp_document_status`；同步成功置 `work_order_status=已下达`；去掉「未知状态默认已完成」兜底 |
| 常量 | `WmsConstants`：新增 `WORK_ORDER_STATUS_RELEASED`、`WORK_ORDER_SOURCE_ERP` |
| 定时任务 | 新增 `WmsWorkOrderSyncJob`（增量拉取 + 重试 + 连续失败告警），当前**无任何工单同步 Job** |
| 数据库 | `wms_work_order` 增加 `erp_document_status`、`sync_status`、`retry_count`、`last_sync_error`、`last_sync_time`、`sync_fail_count`；更新 `work_order_status` / `source` 注释与默认值 |
| 下游 | `WmsProductionInboundServiceImpl` 状态校验接受「已下达」；`WmsOutboundServiceImpl.createPick()` 增加状态校验 |

**前端改动**：`workOrder/index.vue` 去掉新增/编辑/删除与 `WorkOrderModal`；同步入口支持按单号/时间段；状态字典增加「已下达」；工单菜单建议从「入库管理」迁到「基础数据」。

**设计/计划**：新建 BD-002 生产工单设计、计划、验收报告；更新金蝶业务同步设计；归档旧 BD-002 供应商/订单三件套。

### 2.2 RK-002 生产入库

- 后端：`WmsProductionController` 首步增加「按单号/时间段从 ERP 同步」；`WmsWorkOrderServiceImpl.scan()` 回源改为 ERP；提交校验接受「已下达」。
- 前端：`production/index.vue` 首步由“输入/扫码工单号”改为“从 ERP 同步 + 选择/回查”，保留扫码枪作为按单号输入。
- 文档：更新 RK-002 设计、计划、验收报告，删除“手工工单维护 / MES 推送 / 扫码”口径。

### 2.3 RK-005 不良品入库

- 保持由 RK-002 承载；补只读不良品台账查询/导出（按物料、批次、工单、日期）。
- 首步同步能力与 RK-002 共用。
- 纯不良记录回写金蝶 `PRD_INSTOCK` 当前直接失败，需明确走 MES/HTTP 报文或金蝶允许纯不良单。
- 文档：并入 RK-002 设计新增 RK-005 章节；更新 RK-005 验收报告。

### 2.4 CK-001 生产领料出库

- `WmsOutboundServiceImpl.scanPick()` 目前只查本地工单，需先按单号从 ERP 同步再查。
- `createPick()` 增加状态校验（允许「已下达/生产中」，拒绝「已关闭」）。
- 前端 `OutboundCreateModal.vue` 首步改为“从 ERP 同步工单”；查询按钮补 `wms:outbound:scan` 权限。
- 文档：更新 CK-001/CK-002 设计的 CK-001 章节、计划 Task 3、验收报告。

### 2.5 CK-002 成品销售出库（方向反转）

**现状**：`POST /wms/outbound/sales` 手工录入销售订单，创建后立即回写金蝶 `SAL_SaleOrder`；存在 `saleSyncStatus`、`/sync-sale`、`overrideSaleOrderSync`。

**代码改动**

| 层 | 文件 / 动作 |
|---|---|
| 新增读链路 | `IWmsKingdeeSaleOrderReader` + 实现：查询 `SAL_SaleOrder`（单号/客户/日期/分录物料/数量/状态），复用 `WmsKingdeeFormClient.executeBillQuery` |
| 新增接口 | `GET /wms/outbound/sales-order?no=`（预览）、`POST /wms/outbound/sales-order/sync`（按单号/时间段拉取）、`POST /wms/outbound/sales` 改为“选择 ERP 销售订单 → 生成本地出库单” |
| 删除 / 废弃 | `WmsKingdeeSaleOrderWriterImpl`、`IWmsKingdeeSaleOrderWriter`、`syncSaleOrder`、`/sync-sale`、`overrideSaleOrderSync/overrideReason` 及相关门禁 |
| 数据库 | 新建 `wms_sales_order` / `wms_sales_order_detail` 快照表；`wms_outbound_order.sales_order_no` 加唯一索引（含 `deleted_at`）；`sale_sync_status` 改为拉取状态或新增 `erp_fetch_status` |
| 定时任务 | 新增销售订单拉取 Job（重试 + 告警 + 日志） |
| 前端 | `OutboundCreateModal.vue` 删除销售订单手工表单与手工明细，改为 ERP 销售订单选择器；删除“重新同步销售订单”按钮与 `saleSyncStatus` 列 |
| 文档 | CK-001/CK-002 设计重大改写；计划 Task 9/10/11 与 E2E 改；验收改为“不会新建 SAL_SaleOrder” |

### 2.6 CK-004 库存调拨（从零建设）

**现状**：后端无 Controller/Service/表/Job/权限码；前端无页面/API；WCS/RMS 无移库任务类型。

**需要新建**

- 表：`wms_transfer_order`、`wms_transfer_detail`、`wms_inventory_txn`。
- 后端：实体、Mapper、Service、Controller、调拨分配、WCS/RMS 下发/取消、ERP 调拨单同步 Job。
- 接口：`/wms/transfer`（list/detail/create/from-erp/allocate/dispatch/confirm/close/sync）。
- 前端：`transfer/index.vue` + 创建/详情弹窗 + API + 菜单/权限。
- 外部依赖：金蝶调拨单 FormId/字段、米塔 RMS 移库任务 API。
- 文档：设计、计划、验收全部新建。

---

## 3. 其余 10 条流程的既有缺口

| 流程 | 状态 | 主要缺口 |
|---|---|---|
| BD-001 物料与分类 | 已实现 | 分类类型仍含「辅料」；超期预警需批次级效期 |
| BD-003 库区/库位/设备 | 部分 | 无 WCS/RMS 接口参数配置；库区类型仍可选「线边柜」；无设备注册记录页 |
| BD-004 权限与系统配置 | 已实现 | 配置项残留 `defect.bin.allowed`、「柜内库存」；配置 Controller 缺 `@RequiresPermissions` |
| KC-001 库存预警与批次 | 部分 | 只有阈值配置和批次字段，无预警表/Job/接口/页面；无批次级效期；无批次流转查询 |
| KC-002 盘点与调整 | 未实现 | 无表、无服务、无页面；重盘标记需库存流水或最后变动时间 |
| KC-004 查询与报表 | 未实现 | 无 `WmsInventoryController`、无查询 VO、无导出、无查询日志 |
| SB-001 上位系统同步 | 部分 | 无同步日志表、无差异清单、无统一触发入口；`sync.scope/sync.auto` 未使用；不推库存汇总 |
| SB-002 WCS/RMS 调度 | 部分 | 无设备任务表/状态历史/监控页；无移库任务类型 |
| XT-001 不良品处理 | 部分 | 只有生产入库 `badQty/defectRemark`；无独立台账、处置状态、报废/退货出库 |
| XT-002 操作日志 | 部分 | 出库全部端点、工单同步、WCS/RMS 回调无 `@AutoLog`；无 WMS 专用日志查询/导出 |

> 这些不是 V2.2 改出来的，而是“拿全部 16 条流程图逐条对齐”后暴露的既有缺口；是否本轮补齐按项目范围取舍。

---

## 4. 跨流程底座改造

1. **库存流水 `wms_inventory_txn`**：调拨、盘点调整、报废/退货、库存查询流转共用；当前完全没有。
2. **销售订单快照 `wms_sales_order(_detail)`**：CK-002 改为 ERP→WMS 后保证幂等、重试、明细追溯。
3. **同步日志/差异 `wms_sync_log`、`wms_sync_diff`**：工单拉取、销售订单拉取、调拨同步、出入库回写统一落日志。
4. **状态与来源字典**：工单状态加「已下达」、来源加「ERP」；清理“辅料/线边柜/柜内库存”；补 `sys_dict` 种子，消除前后端双份硬编码。
5. **权限与菜单**：删除 `wms:work-order:add/edit/delete`；新增 `wms:work-order:sync`、`wms:transfer:*`、`wms:inventory:*`、`wms:stocktake:*`、`wms:defect:*`、`wms:sync-log:*`；新增「库存管理」顶层目录。
6. **前端共享组件**：`WmsErpWorkOrderSelect`（同步 + 远程搜索 + 回填）替换生产入库、领料两处原生输入框；`ScanInput` 统一回车/防抖/防重复提交。
7. **测试面**：既有工单/生产入库/领料/销售出库/同步测试要改；调拨/盘点/库存查询/预警/同步日志/设备任务需新增测试。

---

## 5. 设计与计划文档动作

### 5.1 新建

| 文档 | 原因 |
|---|---|
| `specs/2026-10-21-yx-wms-bd-002-production-work-order-design.md` | 旧 BD-002 是供应商/订单，V2.2 无当前设计 |
| `plans/2026-10-21-yx-wms-bd-002-production-work-order.md` + 验收报告 | 无当前计划/验收 |
| `specs/2026-10-21-yx-wms-ck-004-inventory-transfer-design.md` | CK-004 全缺 |
| `plans/2026-10-21-yx-wms-ck-004-inventory-transfer.md` + 验收报告 | 同上 |
| 可选：RK-005 轻量设计 | 当前只有验收报告；建议并入 RK-002 设计 |

### 5.2 更新

- RK-002 设计/计划/验收报告：删除扫码、手工工单维护、MES 推送；状态改「已下达」。
- CK-001/CK-002 设计/计划/验收报告：CK-001 改工单来源；CK-002 整段反转，删除 `SAL_SaleOrder` 回写、`sync-sale`、override。
- `2026-10-20-yx-wms-kingdee-business-sync-design.md`：`source=MES`、状态映射、upsert 规则。
- `2026-10-20-yx-wms-kingdee-erp-integration-analysis.md`：工单方向、字段映射、实施步骤。
- 范围收窄设计/验收：`flows.json 未重新生成`、`redirect=/wms/work-order`、`RK-002 扫码` 已过时。
- BD-001/BD-002 测试计划与缺口清单：BD-002 部分重写，BD-001 保留。

### 5.3 归档

- 旧 BD-002 供应商/订单三件套（设计、计划、验收）。
- RK-001 采购入库设计/计划（不在 V2.2 的 16 条流程内）。
- 旧 BD-002 测试计划中的供应商/订单部分。

---

## 6. 实施顺序

| 优先级 | 内容 | 理由 |
|---|---|---|
| **P0** | BD-002 双字段状态 + 同步 Job + 删除手工入口 + 下游状态校验 | RK-002/RK-005/CK-001 的上游；不先做会被「工单状态不允许入库」卡死 |
| **P1** | RK-002/RK-005/CK-001 首步改 ERP 同步；CK-002 销售订单方向反转 | 直接影响已上线页面与接口 |
| **P2** | CK-004 调拨从零建设 | 独立模块，依赖库存流水底座与外部接口确认 |
| **P3** | KC-001/KC-002/KC-004 + SB-001/SB-002 + XT-001/XT-002 | 既有缺口，按业务优先级取舍 |
| **P4** | 文档归档、菜单/权限清理、字典统一 | 防止旧口径继续被引用 |

---

## 7. 外部依赖（阻塞项）

| 依赖 | 责任方 | 影响 |
|---|---|---|
| 金蝶调拨单 FormId、必填字段、组织/仓库映射 | 金蝶 | CK-004 同步方向与字段 |
| 米塔 RMS 移库任务 API（请求/回执/错误码） | 米塔机器人 RMS | CK-004 设备下发 |
| 金蝶销售订单查询字段与状态语义 | 金蝶 | CK-002 拉取与状态展示 |
| 纯不良单能否写入 `PRD_INSTOCK` | 金蝶 / 业务 | RK-005 同步闭环 |

---

## 8. 验证记录

- 后端抽查：`WmsWorkOrderController` 暴露 list/sync/add/edit/delete/push；`WmsConstants` 仅 4 个工单状态、2 个来源；`WmsProductionInboundServiceImpl:86-89` 仅接受「生产中/已完成」；`WmsOutboundController:74-79` 创建销售单后立即 `syncSaleOrder`；`controller/` 下无 transfer/inventory/stocktake。
- 前端抽查：`workOrder/index.vue` 有新增/编辑/删除；`production/index.vue` 为工单号输入框；`OutboundCreateModal.vue` 销售订单/客户/明细均手工录入。
- 文档抽查：旧 BD-002 为供应商/订单；RK-002 设计与计划保留手工工单维护；CK-002 设计以 `SAL_SaleOrder` 回写为准；CK-004 无任何文档。
- 本次分析未修改代码或旧文档。
