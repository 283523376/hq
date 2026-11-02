# XT-002 操作日志留痕（审计与回溯）设计方案

- 日期：2026-11-02
- 状态：待评审
- 需求来源：蓝图 XT-002「操作日志留痕流程」；用户 2026-11-02 给出驱动场景（见 §1）

---

## 1. 为什么要做（真实驱动场景）

> 「操作员在 ERP 上输入了一个错误工单，过了一段时间以后把这个错误工单作废，但这个错误工单已经同步到 WMS 执行了。
> 我们需要通过日志追溯，并把错误工单造成的错误在 WMS 上纠正回来。以此类推，**所有通过 ERP 同步过来的、或者回写 ERP 的信息都要做必要的记录**，以便审计和回溯。」

由此推出三条硬要求：

1. **按业务单号反查**，不是按时间翻——事后几天才追，时间线没用
2. **覆盖没有 HTTP 请求的通道**：定时同步任务、ERP 回写、设备回调。这些才是错数据的入口，AOP 切 controller 抓不到
3. **追溯之后要能纠正**：系统直接给出「这张错单在 WMS 影响了哪些单据/库存」的清单

## 2. 现状与缺口（2026-11-02 实测）

| 设施 | 现状 | 缺口 |
| --- | --- | --- |
| Jeecg `@AutoLog` → `sys_log` | 67 个注解分布 14 个 controller，976 条日志 | 只存 `request_param`（请求入参），**拿不到变更前的旧值**；且**出库/调拨/入库三个最关键的 controller 恰好都没加** |
| `wms_sync_log` | 有 `request_payload`/`response_payload`（ERP 原始报文）、`status`、`cost_ms` | **只记 ERP→WMS，没记 WMS→ERP 回写**（`direction` 只有 `ERP_TO_WMS`/`WMS_INTERNAL`，还有历史脏值 `ERP->WMS`）；`object_no` 大量为空（工序汇报按行同步），**按单号反查不成立** |
| `wms_export_task` | 异步导出任务（KC-004）：排队/生成/成功/失败、重试、7 天清理 | 可直接复用，只是多一个导出类型 |

**结论**：现有设施单独用都不够，但 `wms_export_task` 可以原样复用。

## 3. 方案总览：两条腿

```
                    ┌─────────────────────────────┐
   ERP ──同步──►     │  ① 变更留痕  wms_audit_log   │  ◄── WMS 内部操作
   WMS ──回写──►     │  谁、何时、对哪张单、改了什么 │
                    └──────────────┬──────────────┘
                                   │ 按业务单号检索
                    ┌──────────────▼──────────────┐
                    │  ② 影响面清单（实时查询）     │
                    │  这张单在 WMS 碰过什么        │
                    └─────────────────────────────┘
```

- **① 变更留痕**：一张新表 `wms_audit_log`，记录每一次对业务数据的变更（含同步落地与回写），带 **变更前后值** 与 **业务单号**。
- **② 影响面清单**：输入工单号，实时 join 当前库，输出它在 WMS 生成的单据、动过的库存行、回写过的 ERP 单号。
- **不存单据快照**（用户 2026-11-02 拍板）：影响面基于**当前库**查询。单据若已被删除，影响面查不到——这是明确接受的取舍；此时靠 ① 的历史记录解释「当时发生了什么」。

### 3.1 为什么不扩展 `wms_sync_log`

`wms_sync_log` 的职责是**同步基础设施**（水位线、重试计数、失败告警、退避），生命周期短；
审计留痕要长期保留、只增不改、可导出取证。混在一张表里会让清理策略打架。
`wms_sync_log` 保持原职责不动，审计另起一表。

## 4. 数据模型：`wms_audit_log`

```sql
CREATE TABLE wms_audit_log (
  id            varchar(32)  NOT NULL PRIMARY KEY,
  -- 业务定位（反查入口）
  biz_type      varchar(32)  NOT NULL COMMENT '业务类型：工作台/入库单/出库单/调拨单/盘点单/系统配置/主数据',
  biz_no        varchar(64)  NOT NULL COMMENT '业务单号（工单号/入库单号/出库单号/配置键…），反查主键',
  biz_ref       varchar(64)  NULL     COMMENT '关联单号（如入库单对应的工单号），用于串起链路',
  -- 变更内容
  action        varchar(32)  NOT NULL COMMENT '动作：创建/修改/审核/作废/同步落地/回写ERP/绑定/解绑',
  channel       varchar(32)  NOT NULL COMMENT '通道：界面操作/ERP同步/回写ERP/设备回调/定时任务',
  before_value  mediumtext   NULL     COMMENT '变更前（JSON，脱敏后）',
  after_value   mediumtext   NULL     COMMENT '变更后（JSON，脱敏后）',
  diff_fields   varchar(1000) NULL    COMMENT '变化的字段名列表，便于快速判断影响',
  -- 上下文
  operator      varchar(100) NULL     COMMENT '操作人；系统/定时任务写 system',
  ip            varchar(64)  NULL,
  request_id    varchar(64)  NULL     COMMENT '同一次请求/同一批同步的串联 id',
  result        varchar(20)  NOT NULL COMMENT '成功/失败',
  error_msg     varchar(500) NULL,
  cost_ms       int          NULL,
  create_time   datetime     NOT NULL,   -- 无 update_time：审计记录不可变
  KEY idx_biz (biz_no, biz_type),
  KEY idx_time (create_time),
  KEY idx_ref (biz_ref),
  KEY idx_channel (channel, create_time)
) COMMENT='WMS 审计留痕（只增不改，无逻辑删除）';
```

**关键设计**：
- `biz_no` 是反查入口，**必须始终有值**（工序汇报这类按行同步的，用「汇报单号#序列#工序号」保证唯一）
- **没有 `update_time`/`deleted_at`**：审计记录一旦写入不可变
- `before_value`/`after_value` 存 JSON，只存**业务关键字段**，不存整行（避免日志爆炸，也避免存无关敏感字段）

## 5. 采集点清单（这是工作量的主要部分）

### 5.1 同步与回写（错数据的主要入口，必须全覆盖）

| 场景 | 打点位置 | 记什么 |
| --- | --- | --- |
| 工单同步（ERP→WMS） | `WmsKingdeeWorkOrderSyncServiceImpl.syncOne` | 工单号、ERP 原始字段 vs WMS 落库值、新建还是更新 |
| 工序汇报草稿落地 | `WmsOperationReportDraftServiceImpl.ingest` | 汇报单号+序列+工序号、数量、入库仓 |
| 生产入库回写（WMS→ERP） | `WmsKingdeeInboundWriterImpl.write` | 入库单号、目标 ERP 单号、成功/失败与原因 |
| 领料回写（WMS→ERP） | `WmsKingdeePickWriterImpl` | 领料单号、PPBOM、数量 |
| 销售出库回写（WMS→ERP） | `WmsKingdeeOutstockWriterImpl` | 出库单号、通知单号、ERP 单号、信用拦截等失败原因 |
| 调拨回写（WMS→ERP） | `WmsKingdeeTransferWriterImpl` | 调拨单号、ERP 单号 |
| 盘点回写（WMS→ERP） | `WmsStocktakeErpWriterImpl` | 盘点单号、盘盈 QTRK / 盘亏 QTCK 单号 |
| 主数据同步 | `WmsKingdeeMaterialSyncServiceImpl` / `WmsKingdeeWarehouseSyncServiceImpl` / `WmsKingdeeCategorySyncServiceImpl` / `WmsKingdeeBinSyncServiceImpl` | 对象类型+编码，新增/更新/停用 |
| 销售订单快照（ERP→WMS） | `WmsSalesOrderServiceImpl` | 销售订单号、明细、状态 |
| 销售出库待办生成（通知单→出库单） | `WmsSalesDemandServiceImpl` | 通知单号、生成的出库单号、缺口——**「错通知单生成错出库单」最典型** |
| 外协订单同步（ERP→WMS） | `WmsSubcontractOrderSyncServiceImpl` | 委外订单号、草稿单号 |
| 各回写重试编排 | `WmsOutboundSyncServiceImpl` / `WmsProductionSyncServiceImpl` / `WmsTransferSyncServiceImpl` | 重试次数、最终结果、错误 |

> 口径：用户要求「**所有**通过 ERP 同步过来的、或者回写 ERP 的信息都要做必要的记录」——上表按代码实测列全，采集点数量约为初版的 1.6 倍。

### 5.2 WMS 内部关键操作（当前 `@AutoLog` 的缺口）

| Controller | 需覆盖的动作 | 现状 |
| --- | --- | --- |
| `WmsOutboundController` | 分配/下发/复核/确认出库/关单 | **无 `@AutoLog`** |
| ~~`WmsInboundController`~~ | 采购入库已于 2026-10-19 范围收窄停用，**不补留痕** | — |
| `WmsTransferController` | 创建/下发/完成/关单 | **无** |
| `WmsProductionController` | 提交/推进/转人工/关单 | 有部分 `@AutoLog` |
| `WmsSystemConfigController` | 配置变更（**要前后值**） | 有，但无前后值 |
| `WmsWarehouseController` / `WmsDeviceController` | 绑定/解绑 | 有，但无前后值 |
| `WmsRmsCallbackController` / `WmsWcsCallbackController` | 设备回执 | **无**（通道=设备回调） |

### 5.3 采集方式（两种，按通道选）

- **HTTP 通道**：AOP 切面 + 注解 `@WmsAudit(bizType=..., action=...)`，从方法入参取业务单号，从返回值取结果
- **非 HTTP 通道**（定时任务/回写/回调）：在 service 方法内**显式调用** `auditLogService.record(...)`

> 为什么不全部用切面：同步任务没有 HTTP 请求上下文，切面拿不到操作人和单号；强行切只会得到一堆 object_no 为空的记录（这正是 `wms_sync_log` 今天的问题）。

**前后值怎么取**（我的决定，供评审）：
- 系统配置/主数据：写前先查旧值，写后赋值新值
- 单据状态流转：记状态字段的前后值 + 触发条件
- 同步落地：`before` = WMS 原记录（无则 null），`after` = 本次落库的关键字段；ERP 原始报文仍由 `wms_sync_log.request_payload` 承载，审计表只存 diff 摘要（不重复存大报文）

## 6. 影响面清单（核心交付物）

**输入**：工单号（其他单号类型可扩展）
**输出**（JSON，页面上以分组卡片呈现，也可导出）：

| 分组 | 内容 | 来源 |
| --- | --- | --- |
| 工单本身 | 工单号、物料、计划量、当前状态、ERP 单据状态 | `wms_work_order` |
| 生产入库单 | 单号、数量、状态、库位、ERP 单号、创建时间 | `wms_production_inbound.work_order_no` |
| 出库/领料单 | 单号、类型、状态、明细（物料+需求/已分配）、ERP 单号 | `wms_outbound_order.work_order_no` + 明细 |
| 受影响的库存行 | 物料、批次、库位、当前数量、锁定数量 | 由上述单据的 物料+批次+库位 关联 `wms_inventory` |
| 回写过 ERP 的单据 | ERP 单号、类型、回写时间、结果 | 上述单据的 `erp_bill_no` + 审计日志 |
| 变更时间线 | 上面所有动作按时间排序 | `wms_audit_log`（按 `biz_no`/`biz_ref`） |

**已知边界**（写清楚，避免误期）：
- 单据已被删除 → 影响面查不到，但 ① 的日志仍能看到「当时创建过/操作过」
- 库存行只能按 物料+批次+库位 近似关联，无法精确归属到某张单据的扣减

## 7. 防篡改

| 层 | 措施 |
| --- | --- |
| 应用层 | **不提供任何 update / delete 接口**；实体不继承带 `deleted_at` 的基类；无编辑页 |
| 数据库层 | **应用层能做到的**：`BEFORE UPDATE` 触发器直接报错（只挡更新，不挡 DELETE——保留期清理本身就要删）。<br>**做不到的**：专用只读写账号——当前数据源是单账号 `root`（`application-dev.yml:148`），要落地得新建第二数据源+运维单独建账号，本期**不做**，验收标准里去掉这条 |
| 权限 | 查询/导出权限独立（`wms:audit:list`、`wms:audit:export`），默认只给管理员/审计角色 |
| 保留期 | **前端页面可调节，以「天」为单位**（系统配置项 `audit.retention.days`，默认 365）。
到期由定时任务**直接删除**，不做归档——归档要另定冷存储载体与查询入口，成本远超日志表本身的价值 |

## 8. 查询与导出

**查询**：按 业务类型 / 业务单号 / 操作人 / 时间区间 / 通道 / 结果 组合过滤，分页。

**导出**（按用户 2026-11-02 指示，**不引 EasyExcel**，复用 KC-004）：
- 走 `wms_export_task` 异步任务 + AutoPoi `exportBigExcel` 流式写盘
- **注意**：现 `WmsExportTaskRunnerImpl` 把导出服务硬编码为 `WmsLedgerExportService`（只认库存台账），
  `createLedgerExport` 也只认 `INVENTORY_LEDGER`。复用要先加一层**按 `taskType` 分发**，不是原样复用
- 复用现成的：排队 → 生成 → 成功/失败、失败可「重试」、文件 7 天清理、仅本人可见
- 无数据时：页面直接提示「没有符合条件的日志」，不生成空文件

**导出内容**：查询条件下的全量日志（含 before/after JSON 摘要），列与页面一致。

## 9. 非目标（明确不做）

- **不存单据快照**，影响面基于当前库（用户拍板）
- 不做日志防篡改的区块链/哈希链（成本远超收益）
- 不改动 `wms_sync_log` 的现有职责与语义
- 不做实时告警（审计是事后行为）
- 不覆盖 Jeecg 平台自身的登录日志（`sys_log` 已承担）

## 10. 待拍板（3 个，建议值已给）

| # | 问题 | 决定（2026-11-02 用户拍板） |
| --- | --- | --- |
| D1 | 日志保留多久？ | **做成前端页面可调，以天为单位**（配置项 `audit.retention.days`）；到期**直接删除**，不归档 |
| D2 | 谁能查审计日志？ | 新增 `wms:audit:list` / `wms:audit:export`，**只授审计员 + 管理员** |
| D3 | WMS 内部操作要不要纳入？ | **纳入**：调拨 / 盘点 / 设备回调 |

## 11. 我的决定（不是用户拍板，供评审挑战）

- 表名单开 `wms_audit_log`，不改 `wms_sync_log`
- 前后值存**关键字段 diff**，不存整行、不重复存 ERP 大报文
- 采集方式按通道二分（HTTP 用切面，其余显式调用）
- 影响面只做「按工单号」一个入口，其他单号类型后续按需扩展

---

## 12. 附带的必修项：ERP 作废的工单，WMS 现在收不到

用户驱动场景成立的前提是「ERP 作废后 WMS 要知道」。**现状不成立**，有四处拦路（2026-11-02 实测）：

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `WmsKingdeeWorkOrderSyncServiceImpl.gateStatus()` | 只认 `FDocumentStatus` 的 `C/A/B`，其他一律返回 null → **整单跳过** |
| 2 | 同一类的查询字段 | **从没查过 `FCancelStatus`**（作废状态，`A`=未作废 / `B`=已作废）。金蝶作废工单时 `FDocumentStatus` 通常仍是 `C`，WMS 于是永远显示「已下达」 |
| 3 | 增量窗口 | 用 `FDate`（业务日期）过滤，不是 `FModifyDate`。「昨天建、今天作废」的单，业务日期仍在窗口外 → 永远不重拉 |
| 4 | `WmsWorkOrderServiceImpl:118` | 守卫「已关闭只能由 WMS 关单产生」（评审 I6）会直接拒绝 ERP 侧写入关闭态 |

> 证据：测试账套 300 张工单 `FCancelStatus` 全为 `A`（没有作废样本），但该字段在 `PRD_MO` 元数据中存在；
> `gateStatus` 与 `FDate` 过滤已在源码确认。

### 修复方案：**已定方案 B**（用户 2026-11-02 拍板）

**方案 B 的落地要点**：

1. `WmsConstants` 新增 `WORK_ORDER_STATUS_CANCELLED = "已作废"`
2. `statusRank("已作废")` = `statusRank("已关闭")`（并列最高，均为终态，任何来源不得拖回去）
3. `WmsWorkOrderServiceImpl` 的 I6 守卫改为：**只允许 ERP 来源**（`source=ERP` 且 `FCancelStatus=B`）写入「已作废」；
   「已关闭」仍严格只由 WMS 关单产生——两类终态职责分离
4. 前端工单状态下拉 / 配色 / 展示补「已作废」（顺手接 `scripts/wms-status-options-check.py` 的体检）
5. 已作废工单在 WMS 侧：不可再提交入库、不可领料（复用现有终态判断）

**三条同步修复（无分歧，一并做）**：
- 查询字段追加 `FCancelStatus`
- `gateStatus` 识别作废：`FCancelStatus='B'` → 「已作废」（优先于 `FDocumentStatus` 判断）
- 增量窗口 `FDate` → `FModifyDate`，与工序汇报口径一致（水位线沿用 `wms_sync_log`）

**注意**：改窗口字段会让「最近修改过」的工单全部重拉一次（含状态没变的），首轮同步量会上升；
与工序汇报增量口径（2026-10-29 已定的 `FModifyDate` + 水位线）保持一致即可。
