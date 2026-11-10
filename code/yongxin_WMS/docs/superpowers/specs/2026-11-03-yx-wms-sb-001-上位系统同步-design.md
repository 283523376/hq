# SB-001 上位系统数据同步 设计方案

- 日期：2026-11-03
- 蓝图：`YX-WMS-SB-001 上位系统数据同步流程（ERP/MES/WMS）`
- 前置决策（项目记忆）：
  - `sb-001-erp-reconcile-not-post`：**只对账、不写账**；保留将来在金蝶落成单据的扩展点
  - `sb-001-reconcile-granularity-warehouse`：对账粒度 **仓库 + 物料 + 批次**

---

## 1. 需求与现状

### 1.1 蓝图原文（8 步）

1. 登录，进入系统管理
2. 选择同步范围或触发自动同步
3. 汇总柜内库存与出入库记录
4. 调用接口推送同步数据 → 上位系统接收报文
5. 同步结果判定（成功/失败）
6. 数据核对（WMS vs 上位）
7. 核对差异清单（管理员核对）
8. 记录同步日志（操作人/时间）

异常分支：推送失败 → 自动重试 → 仍失败 → 告警；存在差异 → 生成差异清单 → 管理员核对。

### 1.2 现状盘点（2026-11-03 实测）

| 已有 | 说明 |
| --- | --- |
| `wms_sync_log` | 实体注释写明「BD-002 引入，**SB-001 共用底座**」；方向/对象类型/单号/对端/结果/连续失败次数/请求响应报文（脱敏、8KB 截断）/耗时 |
| `WmsSyncLogServiceImpl` | `logSync`、`listFailedObjectNos`、`listExhaustedObjectNos`、`latestSuccessTimeMillis` |
| 失败告警设施 | `sysBaseAPI.sendSysAnnouncement(MessageDTO)`；每个业务有独立告警标题；收件人可由配置覆盖 |
| `WmsSyncAutoGate` | 读 `sync.auto`，关闭时定时任务跳过、只允许手工触发 |
| 任务注册器模式 | `XxxJob` + `XxxJobRegistrar` + `WmsJobRegistrarSupport` |
| 金蝶即时库存查询 | 已有 WebAPI 查询能力；**必须用 `FBaseQty`**（`FQty` 常为 0） |
| 库存行更新时间 | `WmsInventoryMapper.xml` 的全部写路径都显式 `update_time = NOW()`，可支撑「今日变动」 |

**缺口**：没有库存/流水汇总，没有 WMS→上位推送或对账链路，`wms_sync_log` 没有 controller 和页面。

### 1.3 本期范围（用户 2026-11-03 拍板）

1. 目标系统是**金蝶 ERP 本身**；MES 是金蝶「智慧车间MES」子模块，不单独对接。
2. **只对账、不写账**：不在金蝶生成单据、不改金蝶库存。
3. 对账粒度：**仓库 + 物料 + 批次**；WMS 侧先把库位/容器聚合掉。
4. **库存严格比对，流水只展示**，不逐单核对。
5. 「今日变动」边界 = **库存行的最后更新时间**（`wms_inventory.update_time`）。
6. 快照落表，作为**可导出/可回放的「待推送包」**；将来在 BOS 建自定义单据后再接推送端。
7. 差异清单支持**直接发起盘点/调整**，但需**风险弹框二次确认**，且走正常盘点流程，全程留痕。

---

## 2. 方案

### 2.1 总体数据流

```
管理员选范围 / 定时任务
        │
        ▼
[1] 建对账批次 wms_sync_run（记录操作人、范围、时间窗）
        │
        ├─► [2a] 汇总 WMS 库存（按 仓库+物料+批次 聚合）──► wms_sync_snapshot
        ├─► [2b] 汇总 WMS 流水（入库/出库/调拨/盘点，仅展示）─► wms_sync_flow
        │
        ▼
[3] 查询金蝶即时库存 STK_Inventory（分页，FBaseQty）——失败重试 3 次
        │
        ▼
[4] 比对 → 回填快照的 erp_qty/diff_qty/match_status
        │
        ├─► 有差异 → 写入 wms_sync_diff（待核对）
        └─► 差异推送告警？否（差异不是故障，只在页面提示）
        │
        ▼
[5] 全程写 wms_sync_log（WMS_TO_ERP / INVENTORY_RECONCILE / 批次号）
        │
        ▼
[6] 管理员在差异清单标记处理，或发起盘点（二次确认）
```

### 2.2 同步范围与触发

| 范围 | 汇总范围 |
| --- | --- |
| **全量库存** | `wms_inventory` 全部行，按 仓库+物料+批次 聚合 |
| **今日变动** | `wms_inventory.update_time >= 当天 00:00` 的行 |
| **指定仓库** | 先按所选仓库过滤，再聚合（可叠加"今日变动"？本期不做，见 §8） |

- **触发**：
  - 手工：页面选范围后点「执行同步」，立即建批次并异步执行；「今日变动」窗口 = 当天 00:00 → 当前。
  - 定时：每日 **23:00**（cron `0 0 23 * * ?`），范围取配置 `sync.auto.scope`（默认「今日变动」），受 `sync.auto` 开关控制；
    窗口 = **上次成功批次的 `window_end` → 当前**（水位线），首次无历史则取当天 00:00。
    > 用水位线而不是「日历日」，是为了不漏 23:00 之后的变动，也能在漏跑一天后自动补上。
- **流水时间窗**：与库存窗口一致（手工 = 当天 00:00 → 当前；定时 = 水位线 → 当前）。
- 金蝶侧固定只查组织 **101**（`FStockOrgId.FNumber = '101'`）。

### 2.2.1 对账仓库域与金蝶映射（2026-11-03 已拍板）

现状：WMS 有 198 个仓库（同步自金蝶），但只有 **D.01 / D.02 / F.03** 有真实库位，其余是同步进来的空仓。
`stock-code.stereo=D.02` 仍是占位值，真实半成品库存在金蝶 **Y.05 / W.01 / W.26**（根 `CLAUDE.md`「当前阻塞」第 1 条）。

**决策（用户 2026-11-03 选方案 1）**：

- 对账域 = **WMS 实际有库存行的仓库**（当前 D.01/D.02/F.03）；金蝶侧只回查这些仓库。
- 匹配键 = `COALESCE(erp_stock_code, warehouse_code)` 直接 1:1，**本期不做映射表**。
- 半成品区的伪差异（D.02 占位 vs 金蝶 Y.05/W.01/W.26）**已知且接受**，等业务口径定了再修，不阻塞本期上线。
- 将来加映射时：给 `WmsWarehouse` 增加映射关系（或配置项 `sync.warehouse-map`），只改汇总的仓库键，不改比对逻辑。

### 2.3 汇总口径

**库存（WMS 侧）**

```sql
SELECT COALESCE(NULLIF(w.erp_stock_code, ''), w.warehouse_code) AS erp_warehouse_code,
       w.warehouse_code,
       COALESCE(NULLIF(m.kingdee_code, ''), '') AS erp_material_code,
       m.material_code, m.material_name, m.unit,
       i.batch_no, SUM(i.quantity) AS qty
FROM wms_inventory i
JOIN wms_location  l ON l.id = i.location_id
JOIN wms_warehouse w ON w.id = l.warehouse_id
JOIN wms_material  m ON m.id = i.material_id
WHERE i.quantity <> 0            -- 或落库时保留 0，见下
  [AND i.update_time >= #{windowStart}]   -- 今日变动
  [AND w.warehouse_code IN (...)]         -- 指定仓库
GROUP BY erp_warehouse_code, w.warehouse_code, erp_material_code,
         m.material_code, m.material_name, m.unit, i.batch_no
```

- **匹配键**：仓库用 `COALESCE(erp_stock_code, warehouse_code)`（D.01/D.02/F.03 两列当前同值），物料用 `kingdee_code`
  （B2′ 解绑后引入，绝大多数与 `material_code` 同值）。`kingdee_code` 为空的物料视为**非 ERP 物料**，
  快照里 `match_status=仅WMS` 并标注「无金蝶编码」，不参与 ERP 侧匹配。
- `locked_qty`（出库锁定）**不计入**对比数量：金蝶账上没有"WMS 锁定"概念。锁定只在快照里附带展示。
- 数量精度按物料单位保留 6 位（与 `wms_inventory.quantity` 一致）。

**库存（金蝶侧）**

- 单据：`STK_Inventory`（即时库存）
- 字段：`FMaterialId.FNumber`、`FStockId.FNumber`、`FLot.FNumber`、`FBaseQty`
- 过滤：`FStockOrgId.FNumber = '101'`；指定仓库时加 `FStockId.FNumber IN (...)`。
- 分页：**`TopRowCount = 0` + `Limit`**（`TopRowCount` 会先截断结果集再翻页）。
- 批号为空：金蝶 `FLot.FNumber` 为空串时，WMS 侧同样按空批次聚合比对。

**流水（仅展示）**

| 类型 | 来源 | 方向 | 纳入状态 |
| --- | --- | --- | --- |
| 生产入库 | `wms_production_inbound`（当前真正的入库主入口） | 良品 IN / 不良品 IN（拆两行，仓库分别取 `location_id` 与 `scrap_location_id`） | 已入库 |
| 出库 | `wms_outbound_order` + `wms_outbound_detail`（销售/领料/报废/样品/外协统一走这里） | OUT | 已完成 |
| 调拨 | `wms_transfer_order` + `wms_transfer_detail` | 按调出/调入拆两行 | 已完成 / 已调整 |
| 盘点 | `wms_stocktake_order` + `wms_stocktake_detail` | 盘盈 IN / 盘亏 OUT | 已调整 |
| 采购入库 | `wms_inbound_order` | **排除**（采购入库已随范围收窄停用，库里有 24 行历史数据） | — |

流水行记录：单号、类型、方向、仓库、物料、批次、数量、业务时间。**不参与比对。**
时间字段统一取单据 `update_time`（盘点取 `adjust_time`）。

### 2.3.1 前置修复：盘点调整必须刷新库存行 `update_time`

`WmsStocktakeServiceImpl.applyToInventory` 走的是 `inventoryMapper.updateById(existing)` / `insert(created)`，
而本项目**没有 MetaObjectHandler**（`WmsUtils` 注释已确认）：`updateById` 会把读出来的旧 `update_time` 原样写回，
`insert` 则留空。后果是**盘点调整后「今日变动」抓不到这条变更**——恰好是 SB-001 最核心的闭环场景。

本期一并修掉：在 `WmsInventoryMapper` 增加带 `update_time = NOW()`、`update_by` 的定量更新方法
（与既有增减方法同构），`applyToInventory` 改用它；新增行时显式 `fillCreate` + `updateTime`。
**这是 SB-001 的依赖项，不单独排期。**

### 2.4 比对规则

两边的 Key = `仓库编码 + 物料编码 + 批次号`。

| 情况 | match_status | diff_type | 处理 |
| --- | --- | --- | --- |
| 两边都有，数量相等 | 一致 | — | 不入差异清单 |
| 两边都有，WMS > ERP | 数量差异 | **多账** | 入差异清单，`diff = wms - erp` |
| 两边都有，WMS < ERP | 数量差异 | **少账** | 入差异清单，`diff = wms - erp`（负数） |
| 仅 WMS 有 | 仅WMS | 仅WMS | 入差异清单 |
| 仅 ERP 有 | 仅ERP | 仅ERP | 入差异清单 |

- **非全量范围的「仅ERP」判定**（否则今天没动过的金蝶库存会全部变成伪差异）：
  - `TODAY`：比对域 = WMS 快照里出现的 key 集合；ERP 侧只回查这些 key。**不产生「仅ERP」**。
  - `WAREHOUSE`：比对域 = 所选仓库内的全部 key，**产生「仅ERP」**。
  - `FULL`：101 全组织，**产生「仅ERP」**。
- 数量比较用 `BigDecimal.compareTo`，不用 `equals`（1.0 vs 1.00）。
- 差异判定阈值：本期**零容忍**，任何非 0 差异都列出。

### 2.5 数据模型（Flyway `V3.9.5_46__wms_sb001_sync_reconcile.sql`）

**`wms_sync_run`（对账批次）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | varchar(36) | 主键 |
| run_no | varchar(64) | 批次号，`SB` + `yyyyMMddHHmmss` + 3 位序号，唯一 |
| scope_type | varchar(16) | `FULL` / `TODAY` / `WAREHOUSE` |
| warehouse_codes | varchar(500) | 指定仓库时逗号分隔 |
| window_start / window_end | datetime | 时间窗 |
| status | varchar(16) | 执行中 / 成功 / 失败 |
| snapshot_count / diff_count / flow_count | int | 计数 |
| retry_count | int | 本次执行的重试次数（0~3） |
| cost_ms | int | 耗时 |
| error_message | varchar(1000) | 失败原因 |
| operator | varchar(64) | **显式存操作人**（异步线程没有 Shiro 上下文） |

**`wms_sync_snapshot`（库存快照 / 待推送包）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id / run_id | varchar(36) | |
| warehouse_code | varchar(64) | 金蝶 `FStockId.FNumber`，WMS 与金蝶 1:1 |
| material_code / material_name / unit | varchar | |
| batch_no | varchar(64) | **NOT NULL DEFAULT ''**（2026-11-03 实现，按 §2.4 空批归一化口径：schema 层杜绝 NULL/空串分裂成两个 key 产生伪差异） |
| wms_qty / erp_qty / diff_qty | decimal(20,6) | |
| match_status | varchar(16) | 一致 / 数量差异 / 仅WMS / 仅ERP |
| snapshot_time | datetime | |

**`wms_sync_flow`（流水，仅展示）**

| 字段 | 说明 |
| --- | --- |
| id / run_id | |
| flow_type | 入库 / 出库 / 调拨 / 盘点 |
| direction | IN / OUT |
| bill_no / warehouse_code / material_code / material_name / batch_no | |
| qty | decimal(20,6) |
| biz_time | datetime |

**`wms_sync_diff`（差异清单）**

| 字段 | 说明 |
| --- | --- |
| id / run_id | |
| warehouse_code / material_code / material_name / batch_no | |
| wms_qty / erp_qty / diff_qty | |
| diff_type | 多账 / 少账 / 仅WMS / 仅ERP |
| status | 待核对 / 已核对 / 已忽略 |
| handle_by / handle_time / handle_remark | 处理留痕 |
| stocktake_id / stocktake_no | 发起盘点后回填 |

索引：`idx_run`（run_id）、`idx_diff_status`（status）、`uk_run_no`（run_no）、快照 `idx_snap_key`（run_id, warehouse_code, material_code, batch_no）。

> 审计字段由 `WmsUtils.fillCreate/fillUpdate` 显式写入——**本项目没有 MetaObjectHandler**，不要指望自动填充。

### 2.6 执行与重试

- `POST /wms/sync/run` 只在**同步段**建批次并返回 `runId`；汇总与比对交给专用线程池异步执行，前端轮询批次状态。
- 重试范围：**金蝶查询调用**。最多 3 次，退避 1s / 2s；每次尝试都写一条 `wms_sync_log`（`retryCount` 递增）。
- 3 次仍失败：批次 `status=失败`、`error_message` 落库、发告警；已汇总的 WMS 快照保留（可回放/可导出）。
- **不做跨批次补偿**：对账是时点快照，重跑旧批次没有意义；下一次定时任务会重新生成。
- 并发保护：同一时间只允许一个「执行中」批次（`@DisallowConcurrentExecution` + 服务层 Redis/DB 检查）。

### 2.7 告警

- 复用 `sysBaseAPI.sendSysAnnouncement(MessageDTO)`。
- 标题：`SB-001 上位系统对账失败告警`。
- 内容：批次号、范围、重试次数、失败原因、操作人。
- 收件人：`wms.sync.alert.to-user`（默认 `admin`）。
- **差异本身不发告警**——差异是正常业务结果，只在页面亮红灯。

### 2.8 差异清单与发起盘点

- 列表筛选：批次、仓库、物料、状态、差异类型；支持导出 Excel。
- 标记处理：`POST /wms/sync/diff/{id}/handle`，状态只能「待核对 → 已核对/已忽略」，必须填备注。
- **发起盘点**：`POST /wms/sync/diff/{id}/create-stocktake`
  1. 前端弹**风险确认框**，如实说明三点：① 只生成**盘点草稿**，不立即冻结；② 在盘点页「开始盘点」后才冻结库位，
     而冻结是**整片库位**的——同一库位上的其它物料也会一起被盘；③ 盘点产生的差异调整会回写金蝶 ERP。
  2. 后端按 仓库+物料+批次 查 `wms_inventory`（`quantity > 0` 或 `locked_qty > 0`）得到 `locationIds`。
  3. 调 `IWmsStocktakeService.create(WmsStocktakeCreateDTO{type=定期, locationIds, remark="SB-001 差异发起: <批次号>"})`；
     `scopeDesc` 由服务端按库位数生成（DTO 没有该字段），批次号写在 remark 里。
  4. 回填 `stocktake_id/stocktake_no`，写 `handle_by/time`，同时记 `@WmsAudit` 与 `wms_sync_log`。
  5. 前端跳转到盘点单详情，由操作员按正常流程「开始 → 录入实盘 → 差异确认 → 调整」。
- **WMS 无账面**（`仅ERP` / WMS=0）：拒绝发起盘点，提示「WMS 无账面库存，无法盘点；请走生产入库或其他入库修正」——不绕过既有流程硬造库存。
- **盘点能修正什么**：盘点只按「WMS 账面 vs 实盘」产生 QTRK/QTCK。若实盘恰好等于 WMS 账面，
  `多账/少账` 差异不会被消除，仍需管理员在金蝶侧人工处理；差异页要给出这句提示，不制造「点了就能平」的错觉。

### 2.9 日志留痕

| 事件 | 记录点 |
| --- | --- |
| 批次创建/执行/结果 | `wms_sync_run`（operator 显式字段）+ `wms_sync_log`（objectType=`INVENTORY_RECONCILE`，objectNo=run_no） |
| 每次重试 | `wms_sync_log` 一行（retryCount 1..3） |
| 差异标记/发起盘点 | `wms_sync_diff` 的 handle 字段 + `@WmsAudit` + `wms_sync_log` |
| 推送包导出 | `@WmsAudit`（导出动作）+ 导出任务表（复用 KC-004 异步导出） |

`wms_sync_log` 的报文：请求记「范围 + 时间窗 + 汇总行数 + 查询条件摘要」，响应记「金蝶返回行数 + 耗时 + 错误码」，**不落全量明细**（全量在快照表，日志 8KB 截断）。

### 2.9.1 保留期与清理

| 表 | 保留期 | 说明 |
| --- | --- | --- |
| `wms_sync_log` | 90 天 | 实体注释已声明「清理归 SB-001」，本期落实 |
| `wms_sync_run` / `wms_sync_snapshot` / `wms_sync_flow` | 180 天 | 快照随批次一起删 |
| `wms_sync_diff` | 365 天 | 对账证据，留久一点 |

- 新增 `WmsSyncRetentionJob` + `WmsSyncRetentionJobRegistrar`，每日 03:20 执行（与既有清理任务错峰）。
- 保留天数走 `WmsConfigKey`（`sync.retention.days` / `sync.retention.diff-days`），不写死。

### 2.10 待推送包

- 快照表本身就是"包"：`GET /wms/sync/snapshot/export?runId=&format=xlsx|json`。
- **JSON**：按批次输出 `{runNo, scope, window, snapshot:[...], flow:[...]}`,可用于将来接 BOS 接收端。
- **Excel**：库存与流水两个 sheet。
- 导出走 KC-004 的异步导出 + 7 天清理机制（大范围时），小范围直接同步返回。

---

## 3. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/wms/sync/run` | 建批次并异步执行；入参 `scopeType`、`warehouseCodes` |
| GET | `/wms/sync/run/page` | 批次列表 |
| GET | `/wms/sync/run/{id}` | 批次详情（状态/计数/错误） |
| GET | `/wms/sync/snapshot/page` | 快照明细（按 runId） |
| GET | `/wms/sync/snapshot/export` | 待推送包导出（xlsx/json） |
| GET | `/wms/sync/flow/page` | 流水明细（按 runId） |
| GET | `/wms/sync/diff/page` | 差异清单 |
| POST | `/wms/sync/diff/{id}/handle` | 标记已核对/已忽略（必填备注） |
| POST | `/wms/sync/diff/{id}/create-stocktake` | 发起盘点（二次确认由前端承载） |
| GET | `/wms/sync/log/page` | 同步日志查询 |

统一 `Result<T>`；DTO 的日期字段必须带 `@DateTimeFormat`（否则 400）。

---

## 4. 前端

`src/views/wms/sync/`：

- `index.vue` — 同步执行（范围选择、执行按钮、批次列表、状态轮询）
- `snapshot.vue` — 待推送包查看 + 导出
- `flow.vue` — 流水明细
- `diff.vue` — 差异清单（标记处理、发起盘点弹框）
- `log.vue` — 同步日志

风险弹框文案要点：只生成**盘点草稿**（在盘点页「开始」后才冻结，且冻结的是整片库位）、盘点差异会回写金蝶 ERP、操作会留痕。

---

## 5. 权限与菜单

| 权限码 | 用途 |
| --- | --- |
| `wms:sync:run` | 执行对账 |
| `wms:sync:log:view` | 查看同步日志 |
| `wms:sync:diff:view` | 查看差异清单 |
| `wms:sync:diff:handle` | 标记差异处理 |
| `wms:sync:diff:stocktake` | 由差异发起盘点 |

菜单：系统管理 → 上位系统同步（一个父菜单下 5 个子页面），Flyway 里一并插 `sys_permission`。

---

## 6. 验收标准

1. 单测：
   - 聚合口径（仓库+物料+批次，库位/容器被聚合掉）
   - 今日变动过滤（按 `update_time`）
   - 四种差异分类 + `BigDecimal` 比较
   - `TODAY` 范围不产生「仅ERP」；`FULL`/`WAREHOUSE` 产生
   - 查询失败重试恰好 3 次 → 失败 + 告警
   - 差异发起盘点：有账面成功、无账面拒绝；建的是草稿不是冻结
   - **盘点调整后库存行 `update_time` 被刷新**（§2.3.1 回归用例）
   - 权限码存在
2. 真账套：对 WMS 有库存的仓库跑一次，**成品区（D.01/F.03）应为 0 差异**；半成品区（D.02 占位）的伪差异单独说明，不作为失败项。
3. Flyway `V3.9.5_46` 在 `flyway_schema_history` 中 `success=1`，启动日志无「flyway执行sql脚本失败」。
4. 后端 `install` + 重启；前端 `pnpm build` 通过。
5. 菜单与 5 个权限码可在角色授权中看到。

---

## 7. 风险

| 风险 | 应对 |
| --- | --- |
| 盘点调整不刷新 `update_time`（已确认） | 见 §2.3.1，本期一并修 |
| **仓库映射未定**：`stock-code.stereo=D.02` 仍是占位值，真实半成品库存在金蝶 Y.05/W.01/W.26 | 见 §2.2.1；本期只对 WMS 实际有库存的仓跑，半成品区伪差异已知并接受 |
| 全量即时库存查询数据量大 | `TopRowCount=0` + `Limit` 分页；必要时按仓库分批查询 |
| 异步线程丢 Shiro 上下文 → `create_by` 为空 | 批次表显式存 `operator`，日志与差异处理都用它 |
| 单位/精度不一致（WMS 物料单位 vs 金蝶基本单位） | 本期假定 1:1；发现不一致先记入差异清单人工判断 |
| 金蝶批号为空 vs WMS 空串 | 统一归一化为空串再比对 |
| 盘点发起受"WMS 无账面不能盘点"限制 | 明确拒绝并提示，不硬造库存 |
| 部分仓库在金蝶未建档 | 预先同步仓库档案（已做全量同步）；缺失的仓库记入差异清单 |
| `kingdee_code` 为空的物料（实测 7 个） | 标为非 ERP 物料，记为「仅WMS」并带「无金蝶编码」备注，不参与 ERP 匹配 |

---

## 8. 非目标与后续扩展

**非目标**

- 不在金蝶生成单据 / 不修改金蝶库存（后续可扩展）
- 不比对流水，不逐单核对
- 不做历史时点结存（KC-004 已知缺口）
- 不接 MES 独立接口（MES 是金蝶子模块）
- 不做自动调账（只发起盘点）
- 不做"指定仓库 + 今日变动"的组合筛选（本期）

**后续扩展点（已预留）**

1. **落账模式**：在 `sync/` 加一个 `IWmsInventorySnapshotWriter`，今天只有「导出/回放」实现；将来 BOS 建好自定义接收单据后，增加 Kingdee 实现即可，汇总与比对逻辑不动。
2. **维度扩展**：比对 Key 做成参数化（当前 3 维），101 启用仓位管理后可加 `location_code`。
3. **范围组合**：`scope_type` 之外预留 `filters` JSON 字段（本期不落库，加字段即可）。
4. **流水比对**：`wms_sync_flow` 已按单号落库，将来可加"按 `erp_bill_no` 逐单核对"。
