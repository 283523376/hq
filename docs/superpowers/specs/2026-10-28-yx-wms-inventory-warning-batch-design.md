# 永鑫WMS — KC-001 库存预警与批次管理 设计文档

- 日期：2026-10-28
- 状态：已定稿（用户授权「按你的判断做」）
- 关联：KC-001 库存预警（P3 未开工项）、`2026-10-28-yx-wms-erp-source-of-truth-billno-design.md`

## 1. 现状与结论

`wms_inventory` **已存在**（`V3.9.5_11` 建表，`V3.9.5_17` 扩 `container_code` / `locked_qty`），
唯一键 `(material_id, location_id, batch_no, container_code)`，**已有 `batch_no`**。
所以本次任务 1 不需要新建库存表，只需：

1. 给 `wms_inventory` 补 `first_inbound_time`（超期预警的起算点）；
2. 新建 `wms_warning_rule`（规则）与 `wms_inventory_warning`（预警）。

`batch_no` 已经贯穿入库明细、出库明细、生产入库、调拨明细、盘点明细 5 张表，
批次流转记录可以**直接联合查询**，不需要新增流水表。

## 2. 设计决策（本次的判断）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 超期起算用**新增列** `first_inbound_time`，不用 `create_time` | `create_time` 是库存**行**的创建时间：调拨会新建目标库位行、盘盈也会新建行，用它会重置超期 |
| D2 | `upsertInventory` 只在 INSERT 时写 `NOW()`，`ON DUPLICATE` **不覆盖** | 同批次再次入库不能把「首次入库时间」往后推 |
| D3 | 调拨建目标行时**显式继承源行的 `first_inbound_time`** | 移库不是新入库，货龄应连续 |
| D4 | 盘盈新建行用 `NOW()` | 盘盈的实物入库时点无从得知，取发现时间最保守 |
| D5 | 规则分 `LOW_STOCK`（库存下限）/ `OVERDUE`（存放超期），作用域 `MATERIAL` / `CATEGORY` / `ALL`，仓库可选 | 仓管实际只会「盯几个关键物料」或「盯整类」，全局限定仓库可细分 |
| D6 | 合并提醒 = 同 `dedup_key` 且 `PENDING` 只保留一条，命中时累加 `hit_count`、刷新 `current_qty` | 每小时扫一次不能刷出几百条重复预警 |
| D7 | 预警**不自动解除**，必须人工处理留痕 | 用户明确要求「记录结果并解除预警」；自动解除会绕过留痕 |
| D8 | 处理动作 `REPLENISH`（补料）/ `CLEAR`（清理）/ `OTHER`，`handle_remark` **必填** | 留痕审计；不填理由的「解除」等于关掉告警 |
| D9 | 处理**不联动改库存** | 补料/清理应走各自的入库/出库/盘点流程，预警模块越权改数会绕过单据与金蝶回写 |
| D10 | 扫描任务默认**开启**（`wms.warning.scan.enabled=true`，每天 08:00） | 纯 WMS 内部计算，不调 ERP、无外部副作用；与金蝶同步任务默认关闭的取舍不同 |
| D11 | 批次流转查询用 **UNION ALL** 跨 5 张明细表，不落流水表 | 现有单据已完整记录批次，另立流水表会带来双写不一致风险 |

## 3. 数据模型

### 3.1 `wms_inventory` 扩列

```sql
ALTER TABLE wms_inventory ADD COLUMN first_inbound_time datetime DEFAULT NULL
  COMMENT '批次首次入库时间（超期预警起算点）';
-- 存量回填
UPDATE wms_inventory SET first_inbound_time = create_time WHERE first_inbound_time IS NULL;
```

### 3.2 `wms_warning_rule`

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) | 主键 |
| rule_name | varchar(64) | 规则名称，未删除内唯一 |
| rule_type | varchar(20) | `LOW_STOCK` / `OVERDUE` |
| scope_type | varchar(20) | `MATERIAL` / `CATEGORY` / `ALL` |
| material_id | varchar(32) | scope=MATERIAL 时必填 |
| category_id | varchar(32) | scope=CATEGORY 时必填 |
| warehouse_id | varchar(32) | 可空，空=全部库区 |
| threshold_qty | decimal(12,3) | `LOW_STOCK` 必填且 > 0 |
| overdue_days | int | `OVERDUE` 必填 1..3650 |
| enabled | tinyint | 1 启用 / 0 停用 |
| remark | varchar(255) | |
| 标准审计列 | | create_by/create_time/update_by/update_time/deleted_at |

### 3.3 `wms_inventory_warning`

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) | 主键 |
| warning_no | varchar(64) | `WRN` + yyyyMMddHHmmss + 3 位序号 |
| rule_id / rule_name / rule_type | | 规则快照（规则改名不影响历史） |
| material_id / material_code / material_name | | 物料快照 |
| warehouse_id / location_id / location_code | | 超期预警到库位级；下限预警只到物料级 |
| batch_no / container_code | | 超期预警带批次与容器 |
| current_qty | decimal(12,3) | 当前数量（下限=可用量，超期=库存量），每次命中刷新 |
| threshold_qty | decimal(12,3) | 触发时的阈值快照 |
| overdue_days | int | 触发时的超期天数快照 |
| first_inbound_time | datetime | 触发时的首次入库时间快照 |
| status | varchar(20) | `PENDING` / `RESOLVED` |
| dedup_key | varchar(255) | `ruleId\|materialId\|locationId\|batchNo\|containerCode`；解除后追加 `#R#时间戳` |
| hit_count | int | 命中次数 |
| last_hit_time | datetime | 最近命中时间 |
| handle_type / handle_remark / handled_by / handle_time | | 处理留痕 |
| 标准审计列 | | |

索引：`uk_wms_warn_no(warning_no, deleted_at)`、`idx_wms_warn_dedup(dedup_key, deleted_at)`、
`idx_wms_warn_status(status, rule_type)`、`idx_wms_warn_material(material_id)`、`idx_wms_warn_batch(batch_no)`。

## 4. 规则校验（非法一律拒绝保存）

按顺序校验，命中即抛 `RuntimeException(可读中文原因)`，控制器 `try/catch → Result.error`：

1. `rule_name` 非空、≤64；未删除内不重名。
2. `rule_type` ∈ {LOW_STOCK, OVERDUE}；`scope_type` ∈ {MATERIAL, CATEGORY, ALL}。
3. `LOW_STOCK`：`threshold_qty` 必填、> 0、≤3 位小数；`overdue_days` 必须为空。
4. `OVERDUE`：`overdue_days` 必填、1..3650；`threshold_qty` 必须为空。
5. `scope_type=MATERIAL` → `material_id` 必填且物料存在未删除；`CATEGORY` → `category_id` 同理；`ALL` → 两者都必须为空。
6. `warehouse_id` 若填必须存在未删除。
7. 业务唯一：`(rule_type, scope_type, material_id, category_id, warehouse_id)` 未删除内唯一
   —— 同一目标不能挂两条同类型规则，否则同一事实会生成两条预警。

## 5. 定时扫描

`WmsInventoryWarningScanJob` + `WmsInventoryWarningScanJobRegistrar`
（照 `WmsMasterDataSyncJobRegistrar` 同构：`ApplicationReadyEvent` 注册、关闭时主动摘除，
`@DisallowConcurrentExecution`，执行体吞异常）。

配置：`wms.warning.scan.enabled`（默认 **true**）、`wms.warning.scan.cron`（默认 `0 0 8 * * ?`）。

### 5.1 LOW_STOCK

1. 解析作用域 → 目标物料集合：`MATERIAL` 直接取；`CATEGORY` 取该分类下物料；
   `ALL` 取「当前有库存记录的物料」∪「已有下限规则的物料」。
2. 按 `(物料, 规则限定的仓库或无限制)` 汇总 `SUM(quantity - locked_qty)`。
3. 汇总值 `< threshold_qty` → 生成/合并预警（`location_id`、`batch_no` 为空）。
4. 汇总值为 0 也报——下限预警的意义就是提醒「没货了」。

### 5.2 OVERDUE

1. 取 `quantity > 0` 且 `first_inbound_time IS NOT NULL` 且
   `first_inbound_time <= NOW() - INTERVAL overdue_days DAY` 的库存行。
2. 按规则作用域（物料/分类/仓库）过滤。
3. 逐行（物料+库位+批次+容器）生成/合并预警。

### 5.3 合并与刷新

合并键含**容器**：`wms_inventory` 的唯一键是 `(material, location, batch, container)`，
同库位同批次可能有多箱，不含容器会把两箱合并成一条预警。

- 命中时先按 `dedup_key` 找 `PENDING` 预警：
  - 存在 → `hit_count+1`、`last_hit_time=NOW()`、刷新 `current_qty` / `overdue_days`，
    **不新建、不改单号**。
  - 不存在 → 新建一条 `PENDING`。
- `RESOLVED` 的预警永不参与合并（其 `dedup_key` 已追加 `#R#` 后缀）。
- 不满足条件的 `PENDING` 预警**不自动关闭**（D7），但下次命中会刷新数量；
  扫描结束返回 `scanned / created / merged` 计数，写 `wms_sync_log`（方向 WMS 内部）。

## 6. 预警处理

`POST /wms/inventoryWarning/handle`，body：

```json
{ "id": "...", "handleType": "REPLENISH", "handleRemark": "已从 C.04 调拨补 500" }
```

校验：预警存在 → 状态为 `PENDING` → `handleType` ∈ {REPLENISH, CLEAR, OTHER} → `handleRemark` 非空且 ≤500。

成功后：`status=RESOLVED`、写 `handled_by`（当前登录用户 realname，取不到则 username）、
`handle_time=NOW()`、`dedup_key` 追加 `#R#<时间戳>`。**不改库存**（D9）。

## 7. 批次查询

两个只读接口：

| 接口 | 说明 |
|---|---|
| `GET /wms/inventory/batch/stock?batchNo=` | 该批次当前库存明细（物料、库区、库位、容器、数量、锁定、可用、首次入库时间） |
| `GET /wms/inventory/batch/flow?batchNo=` | 该批次流转记录（时间倒序，UNION ALL 5 类单据） |

流转记录统一 VO：`eventTime`、`eventType`（INBOUND / PROD_INBOUND / OUTBOUND / TRANSFER / STOCKTAKE）、
`billNo`、`materialCode/Name`、`locationCode`、`containerCode`、`qty`（正数入库、负数出库）、
`counterparty`（供应商/客户/目标库位/**盘盈盘亏**）、`operator`、`remark`。

数据源：

| 类型 | 主表 | 单号来源 | 数量 | 时间 |
|---|---|---|---|---|
| INBOUND | `wms_inbound_detail` | `wms_inbound_order.order_no` | `actual_quantity`（为空取 `quantity`） | order.create_time |
| PROD_INBOUND | `wms_production_inbound` | `inbound_no` | `good_qty` | `complete_time`（为空取 create_time） |
| OUTBOUND | `wms_outbound_detail` | `wms_outbound_order.outbound_no` | `-actual_qty` | order.create_time |
| TRANSFER | `wms_transfer_detail` | `wms_transfer_order.transfer_no` | `actual_qty`（源库位负、目标库位正，拆两条） | order.create_time |
| STOCKTAKE | `wms_stocktake_detail` | `wms_stocktake_order.stocktake_no` | `diff_qty` | order.adjust_time（为空取 create_time） |

## 8. 改动清单

**Flyway**：`V3.9.5_37__wms_inventory_warning.sql`（扩列 + 回填 + 2 表 + 菜单权限）。

**后端**：
- entity：`WmsWarningRule`、`WmsInventoryWarning`
- mapper：`WmsWarningRuleMapper`、`WmsInventoryWarningMapper`（+ XML，批流转 UNION 查询）
- service：`IWmsWarningRuleService(Impl)`、`IWmsInventoryWarningService(Impl)`、`IWmsBatchTraceService(Impl)`
- controller：`WmsWarningRuleController`、`WmsInventoryWarningController`、`WmsBatchTraceController`
- job：`WmsInventoryWarningScanJob`、`WmsInventoryWarningScanJobRegistrar`
- config：`WmsWarningProperties`（`wms.warning.*`）
- 改：`WmsInventory`(+firstInboundTime)、`WmsInventoryMapper.xml`(upsert 带首次入库时间)、
  `WmsTransferInventoryServiceImpl`（继承源行时间）、`WmsStocktakeServiceImpl`（盘盈写 NOW）、`WmsConstants`（任务常量）

**前端**：
- `views/wms/warning/`（预警列表 + 规则配置两个 tab）
- `views/wms/inventory/`（批次追溯）
- 菜单 sort 95 / 96

**权限码**：`wms:warning:list|add|edit|delete|handle|scan`、`wms:inventory:batch`。

## 9. 非目标

- 预警消息推送（短信/企业微信/钉钉）——不在本次范围
- 预警升级/多级阈值——YAGNI，需要时再加 `warning_level`
- 安全库存与 ERP 联动（金蝶 `BD_MATERIAL` 无对应字段，且不覆盖 ERP 信息）
- 批次有效期/保质期（金蝶批号主档有到期日，但当前 101 未维护，先不做）
