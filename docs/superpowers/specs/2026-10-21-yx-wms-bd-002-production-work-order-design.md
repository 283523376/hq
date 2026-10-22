# 永鑫WMS BD-002 生产工单管理设计（ERP 同步版）

日期：2026-10-21（2026-10-22 评审修订）
依据：`wms-blueprint/flows.json` 中 `YX-WMS-BD-002`（V2.2）、`2026-10-21-yx-wms-v22-impact-analysis.md`、金蝶云星空 `PRD_MO` 实测
状态：**评审通过待排期**（评审意见已并入：B1 状态门槛、I1 枚举对齐、I2 失败单重试、I3 历史回填、I4 状态生命周期、M1–M9）

## 0. 用户已确认的约束

1. **工单只从 ERP 同步**：WMS 不再手工新建、审核、下达；金蝶**已审核（`C`）**的工单同步成功后置为「已下达」。
2. **双状态字段**：`erp_document_status` 保存金蝶 `FDocumentStatus` 原始值，`work_order_status` 是 WMS 可执行状态。
3. **ERP 状态门槛（评审 B1，2026-10-22 确认）**：`C`（已审核）→「已下达」；`A/B`（创建/审核中）→「待生产」，只读不可执行；`D` 及未知值**整单跳过并告警**，不默认「已完成」。
4. **MES 推送端点保留**：`POST /wms/work-order/push` 继续可用，来源记 `MES`；不得覆盖已存在的 ERP 来源。
5. **工单是下游唯一索引**：RK-002、RK-005、CK-001 都以工单号取工单；状态校验必须接受「已下达」。
6. **同步失败可见**：接口返回逐单原因；定时任务重试并告警；失败留痕且**可被重试**。

## 1. 意图与范围

把 BD-002 从「WMS 维护工单 + 同步 MES」改为「ERP 为唯一源头，WMS 只读落地」：

- 金蝶 `PRD_MO` 是工单主数据；WMS 不再产生工单业务状态。
- WMS 只保留：按单号/时间段拉取、按工单号 upsert、状态落地、查询、关联入库记录。
- 下游流程只消费工单，不再回源 MES。

**非目标**

- 不做 WMS 审核/下达动作，不保留手工新增、编辑、删除。
- 不在本期做「ERP 状态自动关闭工单」；`已关闭` 仍由 WMS 人工关单。
- 不支持多物料工单（金蝶一单多物料仍跳过并给出原因）。
- 不改变 MES 推送的报文契约（只增加来源标识与不覆盖规则）。

## 2. 现状与差距

| 项 | 现状 | 差距 |
|---|---|---|
| 工单写入口 | `/add`、`/edit`、`DELETE /{id}`、`/sync-kingdee`、`/push` | 手工入口与新口径冲突，需下线 |
| 来源 | 新建固定 `MES`；手工固定 `手工` | 无 `ERP`；金蝶同步也被标成 MES |
| 状态 | `待生产/生产中/已完成/已关闭` | 无 `已下达`；金蝶同步走旧 `statusMap` |
| 同步触发 | 前端手工按钮，`top=50` | 无定时任务、无时间范围、无增量窗口 |
| 同步状态 | 无字段 | 无法知道上次同步成功/失败、重试次数 |
| 下游校验 | RK-002 只接受 `生产中/已完成` | 「已下达」会被拒绝 |
| 菜单 | 工单挂在「入库管理」 | 蓝图要求从「基础管理」进入 |

## 3. 目标流程（V2.2）

1. 计划员登录，进入基础管理。
2. 触发工单同步（按单号/时间段）。
3. WMS 调用 ERP 工单接口，ERP 返回生产订单（`PRD_MO`）。
4. WMS 校验工单号与数据完整性；缺字段拒绝该单。
5. 按工单号 upsert（计划量/状态/批次），已审核（`C`）工单状态置为「已下达」，未审核（`A/B`）置为「待生产」。
6. 查询工单执行情况，展示关联入库记录。
7. 异常：接口异常提示；字段缺失提示；同步失败重试并告警；无记录提示。

## 4. 状态与来源模型

### 4.1 字段

| 字段 | 取值 | 说明 |
|---|---|---|
| `erp_document_status` | `A/B/C/D` 等金蝶原始值 | 只读留痕，不参与 WMS 业务判断 |
| `work_order_status` | `待生产/已下达/生产中/已完成/已关闭` | 下游可执行状态仅 `已下达/生产中/已完成`；`待生产` 见 §4.4 生命周期 |
| `source` | `ERP/MES/手工` | `手工` 仅历史数据；新数据只可能 ERP 或 MES |
| `sync_status` | `待同步/同步中/同步成功/同步失败/无需同步` | **与 `WmsConstants.SYNC_STATUS_*` 现有枚举完全一致**（评审 I1）；历史 MES/手工数据回填 `无需同步` |
| `retry_count` | 整数 | 连续失败次数，成功清零 |
| `sync_fail_count` | 整数 | 累计失败次数，用于告警 |
| `last_sync_error` | 文本 | 最近一次失败原因 |
| `last_sync_time` | 时间 | 最近一次成功同步时间 |

### 4.2 同步写入规则（按金蝶 `FDocumentStatus` 门槛，评审 B1）

| 金蝶状态 | WMS 处理 |
|---|---|
| `C`（已审核） | 新建：`source=ERP`、`work_order_status=已下达`；更新：`待生产` 升为 `已下达`；`生产中/已完成/已关闭` **不回退** |
| `A/B`（创建/审核中） | 新建：`source=ERP`、`work_order_status=待生产`；更新：已有 `已下达/生产中/已完成/已关闭` **不降级** |
| `D` 及未知值 | **整单跳过**：不落库、不更新，写 `messages` 告警（不默认「已完成」） |

通用规则：

1. 每次成功同步都刷新 `erp_document_status` 原始值。
2. `已关闭` 永不因同步或 MES 重新打开；业务字段（计划量/批次/物料）正常更新。
3. **MES 推送 `/push`**：新建时 `source=MES`，状态按报文（缺省 `生产中`）；更新已有 ERP 工单时只更新业务字段，不改 `source`、不做状态升降级（`已关闭` 除外）。
4. **失败**：已有工单行写 `sync_status=同步失败`、`last_sync_error`、`retry_count+1`、`sync_fail_count+1`；新单失败无法落行时写 `wms_sync_log`（`object_type=WORK_ORDER`、`status=同步失败`），由 Job 重试（§8）。

### 4.3 下游校验口径

- RK-002/RK-005：允许 `已下达/生产中/已完成`；拒绝 `已关闭/待生产`。
- CK-001：同上；`createPick()` 以前不校验状态，本次补上。
- 历史 `待生产` 工单不允许入库/领料；ERP 侧审核到 `C` 后重新同步即升为 `已下达`。

### 4.4 状态生命周期（开发不得自行扩展，评审 I4）

- 本期 **ERP 同步只产生** `待生产`（金蝶 `A/B`）与 `已下达`（金蝶 `C`）两个状态。
- `生产中/已完成` 为 **MES 兼容/历史状态**：仅 MES `/push` 报文可写，WMS 自身不推进、不回退。
- WMS 本期**不做**「首笔入库→生产中」「累计入库达计划量→已完成」等自动流转；如业务需要，另立需求并补状态机测试。
- `已关闭` 只能由 WMS 人工关单产生（RK-002 关单）；同步与 MES 均不得重开。
- 状态迁移合法表（✅=准许，空白=禁止；「同步」永不把状态向左移）：

| 从 ＼ 到 | 待生产 | 已下达 | 生产中 | 已完成 | 已关闭 |
|---|---|---|---|---|---|
| 待生产（新建） | ✅ 同步A/B | ✅ 同步C | ✅ MES push | ✅ MES push | — |
| 已下达 | — | ✅ 同步C | ✅ MES push | ✅ MES push | ✅ RK-002 关单 |
| 生产中 | — | — | ✅ | ✅ MES push | ✅ RK-002 关单 |
| 已完成 | — | — | — | ✅ | ✅ 关单 |
| 已关闭 | — | — | — | — | 终态，无出边 |

## 5. 金蝶同步契约

- **FormId**：`PRD_MO`。
- **查询字段**：`FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty,FDate`。
- **过滤**：
  - 指定 `billNo`：`FBillNo='...'`（单号转义）。
  - 指定日期区间：`FDate>='yyyy-MM-dd' AND FDate<='yyyy-MM-dd'`。
  - 两者都为空：手工同步默认取最近 7 天；定时任务取「上次成功时间 - 30 分钟重叠窗口」至当前。**返回值必须带实际生效的窗口**（评审 M5），前端提示，避免误以为全量。
- **批量**：`top` 默认 50，上限 200；Job 每批 50，循环直到取完或达到安全上限。
- **分组**：同一 `FBillNo` 多分录按数量求和；多物料工单跳过并写入 `messages`。
- **物料映射**：沿用 `material-map` 反向映射；`material-product-type` 缺失则跳过。
- **批次**：`FLot` 为空或 `0` 时用 `KD-<工单号>`；同一 `FBillNo` 多分录 `FLot` 不一致时**整单跳过并告警**（WMS 工单为单批次模型，取首行会错批次，评审 M2）。
- **幂等键**：`work_order_no`（唯一索引已存在）。
- **状态**：`erp_document_status` 存原始值；`work_order_status` 按 §4.2 门槛规则（C→已下达、A/B→待生产、D/未知跳过），不再使用 `status-map` 的 A/B/C 映射。`status-map` 配置保留但标记废弃，代码不再读取。

## 6. 接口变更

### 6.1 保留 / 修改

| 方法 | 路径 | 变更 |
|---|---|---|
| GET | `/wms/work-order/list` | 增加 `source`、`syncStatus`、`startDate`、`endDate` 过滤 |
| POST | `/wms/work-order/sync-kingdee` | 参数 `billNo`、`startDate`、`endDate`、`top`；权限改为 `wms:work-order:sync`；补 `@AutoLog`（评审 M4）；返回 queried/imported/updated/skipped/messages + **实际生效的时间窗口** |
| POST | `/wms/work-order/push` | 保留；`@IgnoreAuth` + `X-MES-Token`；新增 `source=MES` 规则；补 `@AutoLog` 并记录调用方 IP（评审 M4） |
| GET | `/wms/work-order/inbound-records` | **新增**：按 `workOrderNo` 返回关联生产入库记录（工单详情用） |

### 6.2 下线

- `POST /wms/work-order/add`
- `PUT /wms/work-order/edit`
- `DELETE /wms/work-order/{id}`

下线方式：删除 Controller 方法与权限种子；前端同步移除入口。不保留兼容端点，避免绕过 ERP 源头。

## 7. 数据模型与 Flyway

新增脚本：`V3.9.5_19__wms_work_order_erp_sync.sql`（幂等，含回滚说明）。

```sql
ALTER TABLE `wms_work_order`
  ADD COLUMN `erp_document_status` varchar(10)  DEFAULT NULL COMMENT '金蝶FDocumentStatus原始值',
  ADD COLUMN `sync_status`         varchar(20)  NOT NULL DEFAULT '待同步' COMMENT '同步状态：待同步/同步中/同步成功/同步失败/无需同步（对齐WmsConstants.SYNC_STATUS_*）',
  ADD COLUMN `retry_count`         int          NOT NULL DEFAULT 0 COMMENT '连续失败次数',
  ADD COLUMN `sync_fail_count`     int          NOT NULL DEFAULT 0 COMMENT '累计失败次数',
  ADD COLUMN `last_sync_error`     varchar(500) DEFAULT NULL COMMENT '最近一次同步失败原因',
  ADD COLUMN `last_sync_time`      datetime     DEFAULT NULL COMMENT '最近一次成功同步时间',
  ADD INDEX `idx_wms_work_order_sync` (`sync_status`, `last_sync_time`);

ALTER TABLE `wms_work_order`
  MODIFY COLUMN `work_order_status` varchar(20) NOT NULL DEFAULT '已下达'
    COMMENT '工单状态：待生产/已下达/生产中/已完成/已关闭',
  MODIFY COLUMN `source` varchar(20) DEFAULT 'MES'
    COMMENT '来源：ERP/MES/手工（手工仅历史数据；默认值仅为历史兼容，新代码必须显式写来源，评审M1/M9）';

-- 历史数据回填（评审 I3）：MES/手工来源的存量工单不经 ERP 同步通道，
-- 标为「无需同步」，避免被误判为待同步、被 Job 误重试
UPDATE `wms_work_order` SET `sync_status` = '无需同步'
WHERE `source` IN ('MES', '手工') AND `sync_status` = '待同步';
```

回滚说明（评审 M7，写在脚本头）：

```sql
-- 回滚（慎用：丢失同步留痕）：
-- UPDATE `wms_work_order` SET `sync_status`='待同步' WHERE `source` IN ('MES','手工');  -- 先恢复回填
-- ALTER TABLE `wms_work_order`
--   DROP INDEX `idx_wms_work_order_sync`,
--   DROP COLUMN `erp_document_status`, DROP COLUMN `sync_status`, DROP COLUMN `retry_count`,
--   DROP COLUMN `sync_fail_count`, DROP COLUMN `last_sync_error`, DROP COLUMN `last_sync_time`;
-- DROP TABLE IF EXISTS `wms_sync_log`;  -- 确认无其他流程写入后再执行
-- 菜单回滚见 §9。
```

同步日志表（P0 最小集，供 SB-001 复用）：

```sql
CREATE TABLE IF NOT EXISTS `wms_sync_log` (
  `id`               varchar(32)  NOT NULL COMMENT '主键',
  `direction`        varchar(20)  NOT NULL COMMENT 'ERP_TO_WMS / WMS_TO_ERP / WMS_TO_MES',
  `object_type`      varchar(30)  NOT NULL COMMENT 'WORK_ORDER/SALES_ORDER/INBOUND/OUTBOUND/TRANSFER/INVENTORY',
  `object_no`        varchar(64)  DEFAULT NULL COMMENT '业务单号',
  `target`           varchar(30)  DEFAULT NULL COMMENT 'KINGDEE/MES/WCS/RMS',
  `status`           varchar(20)  NOT NULL COMMENT '成功/失败',
  `retry_count`      int          NOT NULL DEFAULT 0,
  `request_payload`  mediumtext,
  `response_payload` mediumtext,
  `error_message`    varchar(500) DEFAULT NULL,
  `cost_ms`          int          DEFAULT NULL,
  `create_by`        varchar(50)  DEFAULT NULL,
  `create_time`      datetime     DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_sync_log_obj` (`object_type`, `object_no`, `create_time`) USING BTREE,
  INDEX `idx_wms_sync_log_status` (`status`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='上位系统同步日志';
```

`wms_sync_log` 写入约定（评审 M3）：

- `request_payload/response_payload` **脱敏后落库**：`X-MES-Token`、金蝶 `appSecret`/`access_token` 一律不落库。
- 单条报文超过 8KB 截断并追加 `[截断]` 标记。
- 日志保留 90 天；定时清理归 SB-001 的日志归档任务，本期不实现。

## 8. 定时任务与重试

- 新增 `WmsWorkOrderSyncJob`，cron `0 1/5 * * * ? *`（与现有 3/7/9/13/17 分错峰），Job ID `1920000000000000340`。
- 每轮两步（评审 I2）：
  1. **增量拉取**：上次成功时间 - 30 分钟 → 当前，按批循环；
  2. **失败单重试**：查 `wms_sync_log` 中 `object_type='WORK_ORDER' AND status='同步失败' AND retry_count<3 AND create_time>now()-7 天` 的 `object_no`，去重后逐单重新同步——解决「新单首次同步失败、时间窗口已被其他单推进」的死角（只写日志不重试是无用功）。
- 重试策略沿用 `WmsConstants`：3 次、1/2/4s 退避、连续失败 3 次告警（站内消息）。
- 禁止并发执行（Quartz `DisallowConcurrentExecution` 或等价配置）。
- 每次执行写 `wms_sync_log`；成功更新 `last_sync_time` 并清零 `retry_count`。

## 9. 前端改动

`src/views/wms/workOrder/index.vue`：

- 工具栏保留：查询、**同步**、详情。
- 删除：新增、编辑、删除、`WorkOrderModal`。
- 同步弹窗：工单号（可空）、开始日期、结束日期、数量上限；执行后展示 查询/新增/更新/跳过/原因。
- 列表列：工单号、物料、产品类型、计划数量、批次、状态、来源、同步状态、最近同步时间、操作（详情）。
- 状态选项：`已下达/生产中/已完成/已关闭`；历史 `待生产` 只读展示。
- 详情抽屉：工单字段 + 关联生产入库记录（调用 `/inbound-records`）。
- 权限：同步按钮用 `wms:work-order:sync`。

菜单迁移：

- 工单菜单 `1910000000000000201` 从「入库管理」`1910000000000000101` 迁到「基础数据」`1910000000000000001`。
- 「入库管理」目录 redirect 改指 `/wms/production/inbound`（原指 `/wms/work-order`，迁走后会 404）。
- 删除按钮权限 `1910000000000000211/212/213`（`del_flag=1`），并清理 `sys_role_permission` 中对应残留授权（脚本头附回滚 INSERT，评审 M8）；新增 `wms:work-order:sync` 按钮并授予 admin 角色。

## 10. 下游契约

| 流程 | 改动 |
|---|---|
| RK-002 | `WmsProductionInboundServiceImpl` 状态校验接受 `已下达`；`WmsProductionController` 首步增加按单号从 ERP 同步 |
| RK-005 | 与 RK-002 共用；保持由生产入库承载，补只读台账查询 |
| CK-001 | `WmsOutboundServiceImpl.scanPick()` 本地查不到时先按单号同步；`createPick()` 增加状态校验 |
| 前端 | 抽 `WmsErpWorkOrderSelect`：按单号/时间段同步 + 远程搜索 + 回填，替换生产入库与领料两处原生输入框 |

## 11. 验收标准

1. 金蝶 `PRD_MO` 工单可按单号与日期区间同步；新单 `source=ERP`、`work_order_status=已下达`、`erp_document_status` 为原始值。
2. 重复同步幂等；`生产中/已完成/已关闭` 状态不回退；`已关闭` 不因同步重新打开。
3. `/add`、`/edit`、`DELETE /{id}` 返回 404；前端无新增/编辑/删除入口；对应权限已下线。
4. MES `/push` 仍可用；新建 `source=MES`；更新 ERP 工单不覆盖 `source`。
5. 定时任务按 5 分钟执行；失败重试与告警生效；`wms_sync_log` 有成功/失败记录。
6. RK-002、RK-005、CK-001 能用 `已下达` 工单完成入库/领料；`已关闭` 工单被拒绝。
7. 工单详情能展示关联生产入库记录；无记录时提示。
8. 单元/集成测试覆盖：同步 upsert、状态不回退、来源不覆盖、权限下线、下游状态校验。
9. 金蝶 `A/B`（未审核）工单落地为 `待生产`，RK-002 提交与 CK-001 领料均被拒绝；同步到 `C` 后升为 `已下达` 可执行（评审 M6）。
10. 人为构造一次同步失败，失败进入 `wms_sync_log`；恢复后定时任务自动重试成功，`retry_count` 清零（评审 M6）。
11. `D` 或未知 `FDocumentStatus` 的工单被整单跳过，`messages` 有明确原因，不产生工单记录。

## 12. 风险与待确认

| 风险 | 处理 |
|---|---|
| 金蝶 `FDate` 过滤格式/时区 | 联调确认；先用单号同步验证，再开时间窗口 |
| `D` 等状态的准确语义 | 已按 B1 处理（跳过+告警）；`D` 的准确语义需金蝶顾问确认，确认后可细化放行规则 |
| 多物料工单 | 继续跳过并给原因；业务确认是否需要按主产品分录处理 |
| 历史 `source=MES/手工` 数据 | 只读兼容；不批量改写，避免审计断链 |
| 入库管理目录 redirect | 迁移脚本必须同时改 redirect，否则目录点击 404（V3.9.5_7 的教训） |
| 旧 `status-map` 配置 | 标记废弃，避免运维误改；代码不再读取 |

## 13. 实施顺序

1. Flyway：工单字段 + `wms_sync_log` + 菜单/权限/redirect。
2. 后端：常量 → 同步服务（原始状态/已下达/时间窗口）→ Controller（下线手工端点、新增参数与详情接口）→ 下游状态校验 → Job。
3. 前端：工单页去 CRUD、加同步/详情 → 生产入库/领料首步改同步选择器。
4. 测试：单元 → 金蝶测试环境联调 → E2E（同步→入库→再同步→关单）。
5. 验收报告。
