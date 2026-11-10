# 永鑫WMS BD-002 生产工单 ERP 同步（P0）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 工单只从金蝶 `PRD_MO` 同步（C→已下达、A/B→待生产、D/未知跳过），删除 WMS 手工新建/编辑/删除，新增同步 Job 与同步日志，下游 RK-002/CK-001 接受「已下达」。

**Architecture:** 金蝶同步服务为唯一写入口（upsert + 状态 rank-max 合并），`wms_sync_log` 承载失败留痕与重试队列；`/wms/work-order/push` 保留为 MES 兼容入口；扫描查询走「本地→单单同步→再查」回源。

**Tech Stack:** Spring Boot 4 / MyBatis-Plus / Quartz / Flyway (MySQL) / Vue3 + Vben。

**Spec:** `docs/superpowers/specs/2026-10-21-yx-wms-bd-002-production-work-order-design.md`（评审通过待排期，2026-10-22 修订版）

## Global Constraints

- 状态枚举对齐 `WmsConstants`：同步状态用 `待同步/同步中/同步成功/同步失败/无需同步`；工单状态用 `待生产/已下达/生产中/已完成/已关闭`；来源 `ERP/MES/手工`。
- 状态合并 = rank-max（待生产0 < 已下达1 < 生产中2 < 已完成3 < 已关闭4）；`已关闭` 终态无出边。
- 新代码必须显式写 `source`，不得依赖 DB 默认值。
- 金蝶状态门槛：`C`→已下达，`A`/`B`→待生产，其余整单跳过并写 `messages`。
- 同步日志脱敏（`X-MES-Token`、金蝶 appSecret/token 不落库）、单条超 8KB 截断加 `[截断]`。
- 所有接口返回 `Result<T>`；删除端点直接删方法不留兼容；拒绝文案逐字 `Result.error(e.getMessage())`。
- 构建一律走 `bash /d/tools/m.sh -P dev ...`；单测 `-pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=<Class>`；带 `-am` 必须加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- Flyway 脚本必须幂等（`IF NOT EXISTS`/带条件 UPDATE/INSERT IGNORE），脚本头写回滚 SQL；改完迁移后若删改过旧脚本需 `bash /d/tools/m.sh -P dev clean process-resources -pl jeecg-module-system/jeecg-system-start`。
- 提交规范 `feat(wms): …`，从仓库根提交，结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## Review Focus

1. **未审核 A/B 工单被放行**：期望 RK-002 提交 / CK-001 领料被拒（「工单状态不允许入库」）→ Task 5 测 `gateStatus("B")=待生产`、Task 7 测提交拒绝。
2. **重复同步把状态拖回**：生产中工单再同步 C，期望仍是生产中 → Task 3 测 rank-max。
3. **MES push 覆盖 ERP 来源 / 重开已关闭**：期望 source 保持 ERP、已关闭保持已关闭 → Task 3 测。
4. **新单首次同步失败成死角**：时间窗被其他单推进后期望 Job 仍按 `wms_sync_log` 重试 → Task 4 测查询、Task 8 测两阶段执行。
5. **同步日志泄密**：payload 含 `X-MES-Token`/`access_token` 时期望被脱敏 → Task 4 测。

---

### Task 1: Flyway `V3.9.5_19__wms_work_order_erp_sync.sql`

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_19__wms_work_order_erp_sync.sql`

**Interfaces:**
- Produces: `wms_work_order` 新列 `erp_document_status/sync_status/retry_count/sync_fail_count/last_sync_error/last_sync_time` + 索引 `idx_wms_work_order_sync(sync_status,last_sync_time)`；表 `wms_sync_log`（结构=spec §7）；菜单：工单 `1910000000000000201` parent→`1910000000000000001`（基础数据目录，sort_no=2.00）；入库管理 `1910000000000000101` redirect→`/wms/production/inbound`；按钮 `1910000000000000211/212/213` del_flag=1 并清 `sys_role_permission`；新按钮 `1910000000000000214`（`wms:work-order:sync`，授权 admin 角色 `f6817f48af4fb3af11b9e8bf182f618b`，INSERT 形状照抄 V3.9.5_2:68）；`sys_quartz_job` 种子 id=`1920000000000000340`、cron=`0 1/5 * * * ? *`、class=`org.jeecg.modules.wms.job.WmsWorkOrderSyncJob`（INSERT 形状照抄 V3.9.5_13:132-133）。

- [ ] **Step 1: 写迁移脚本**：DDL/回填/回滚头照 spec §7 逐字；历史回填 `UPDATE wms_work_order SET sync_status='无需同步' WHERE source IN ('MES','手工') AND sync_status='待同步';`；目录名「物料管理」不改（另立需求）。
- [ ] **Step 2: 起后端验证迁移**：`cd jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run --server.port=8081`，启动后 `grep -c "flyway执行sql脚本失败" 日志` 须为 0，`SELECT success FROM flyway_schema_history WHERE version='3.9.5.19'` 须为 1；并用 `"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms` 抽查新列与 `1910000000000000201` 的 `parent_id`。
- [ ] **Step 3: Commit** `feat(wms): BD-002 工单ERP同步迁移（同步列/同步日志表/菜单迁移/Job种子）`

### Task 2: 常量、实体、DTO 字段

**Files:**
- Modify: `.../wms/constant/WmsConstants.java`（在 WORK_ORDER_SOURCE_MANUAL≈:337 后追加）
- Modify: `.../wms/entity/WmsWorkOrder.java`
- Modify: `.../wms/dto/WmsWorkOrderPushDTO.java`

**Interfaces:**
- Produces:
  - `WmsConstants.WORK_ORDER_STATUS_RELEASED = "已下达"`；`WORK_ORDER_SOURCE_ERP = "ERP"`；`WORK_ORDER_SYNC_JOB_ID = "1920000000000000340"`；`WORK_ORDER_SYNC_JOB_CRON = "0 1/5 * * * ? *"`；`WORK_ORDER_SYNC_LOG_TYPE = "WORK_ORDER"`。
  - `WmsWorkOrder` 新字段：`String erpDocumentStatus; String syncStatus; Integer retryCount; Integer syncFailCount; String lastSyncError; java.util.Date lastSyncTime;`（注释照 spec §4.1）。
  - `WmsWorkOrderPushDTO` 新字段：`String source; String erpDocumentStatus;`（`workOrderStatus` 注释补「已下达/待生产」）。

- [ ] **Step 1: 按上述签名追加字段与常量**（纯追加，不改动既有常量；`statusMap` 相关注释标废弃）。
- [ ] **Step 2: 编译验证** `bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms compile`，期望 BUILD SUCCESS。
- [ ] **Step 3: Commit** `feat(wms): BD-002 工单实体与常量扩展（已下达/ERP来源/同步字段）`

### Task 3: `push()` 状态合并与来源保护

**Files:**
- Modify: `.../wms/service/impl/WmsWorkOrderServiceImpl.java`（`push()` :113-154）
- Test: `.../wms/service/impl/WmsWorkOrderServiceImplTest.java`

**Interfaces:**
- Produces: `static int statusRank(String)`（待生产0/已下达1/生产中2/已完成3/已关闭4，未知返回 -1）；`push(WmsWorkOrderPushDTO)` 行为：新建 `source=dto.getSource()`（空则 `MES`）、`status=dto.getWorkOrderStatus()`（空则 `生产中`）；更新时 **不改 `source`**、`status = rankMax(现有, 传入)`、`已关闭` 恒为 `已关闭`；同时写 `erpDocumentStatus`（dto 提供时）。
- Consumes: Task 2 的常量与 DTO 字段。

- [ ] **Step 1: 写失败测试**（加到既有测试类，沿用其构造/style）：
  - `pushUpdateKeepsErpSourceAndMergesStatus`：已有 `source=ERP,status=生产中`，dto `source=MES,status=已下达` → 断言 source 仍 `ERP`、status 仍 `生产中`、`erpDocumentStatus` 更新。
  - `pushUpgradesPendingToReleased`：已有 `待生产`，dto `status=已下达` → 变 `已下达`。
  - `pushNeverReopensClosed`：已有 `已关闭`，dto `status=生产中` → 仍 `已关闭`。
  - `pushCreateDefaultsMesSource`：dto 无 source → 新建 `source=MES`；dto `source=ERP,status=已下达` → 新建即该值。
- [ ] **Step 2: 跑测试确认失败**：`bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsWorkOrderServiceImplTest`
- [ ] **Step 3: 实现 statusRank + push 合并逻辑**（唯一算法：rank-max 合并，见上）。
- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）。
- [ ] **Step 5: Commit** `feat(wms): BD-002 push状态rank-max合并与来源保护`

### Task 4: `WmsSyncLog` 实体/Mapper/服务

**Files:**
- Create: `.../wms/entity/WmsSyncLog.java`、`.../wms/mapper/WmsSyncLogMapper.java`、`.../wms/service/IWmsSyncLogService.java`、`.../wms/service/impl/WmsSyncLogServiceImpl.java`
- Test: `.../wms/service/impl/WmsSyncLogServiceImplTest.java`

**Interfaces:**
- Produces:
  - `IWmsSyncLogService extends JeecgService<WmsSyncLog>`：
    - `void logSync(String direction, String objectType, String objectNo, String target, boolean success, int retryCount, String requestPayload, String responsePayload, String errorMessage, long costMs)`（脱敏+截断后落库）；
    - `List<String> listFailedObjectNos(String objectType, int maxRetry, int withinDays)`（`status='同步失败'` 且 `retry_count<maxRetry` 且近 N 天，按 object_no 去重）。
  - `static String sanitize(String)`：把 `X-MES-Token:.*`、`"appSecret":".*"`、`access_token=...` 值替换为 `***`；超 8192 字符截断追加 `[截断]`。
- 字段映射照 spec §7 `wms_sync_log` DDL；`direction` 取值 `ERP_TO_WMS/WMS_TO_ERP/WMS_TO_MES`。

- [ ] **Step 1: 写失败测试**：`sanitizeMasksTokensAndTruncates`（构造含 `X-MES-Token: abc`、`"appSecret":"x"` 的 10KB 串，断言含 `***` 与 `[截断]`、不含明文 token）；`logSyncWritesRow`（用 H2/既有测试基建，断言 status=同步成功/同步失败 两分支、object_no 落库）。
- [ ] **Step 2: 跑测试确认失败**（命令样式同 Task 3，`-Dtest=WmsSyncLogServiceImplTest`）。
- [ ] **Step 3: 实现实体/Mapper/服务**（Mapper 仅 `BaseMapper`；`listFailedObjectNos` 用 `@Select` 或 QueryWrapper groupBy object_no）。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit** `feat(wms): 同步日志表服务（脱敏截断+失败单重试查询）`

### Task 5: 金蝶同步服务——门槛/时间窗/多批次/逐单日志/回源

**Files:**
- Modify: `.../wms/service/IWmsKingdeeWorkOrderSyncService.java`、`.../wms/service/impl/WmsKingdeeWorkOrderSyncServiceImpl.java`、`.../wms/vo/WmsKingdeeWorkOrderSyncResultVO.java`
- Test: `.../wms/service/impl/WmsKingdeeWorkOrderSyncServiceImplTest.java`

**Interfaces:**
- Produces:
  - `WmsKingdeeWorkOrderSyncResultVO syncFromKingdee(String billNo, String startDate, String endDate, Integer top)`（替换旧双参方法；`top` null→50，>200 截 200）。
  - `boolean syncSingleFromKingdee(String billNo)`（=syncFromKingdee(billNo,null,null,1)，imported+updated>0 返回 true）。
  - `WmsWorkOrderInfoVO scanWithErpFallback(String workOrderNo)`（=workOrderService.scan → 「工单号无效」时 syncSingleFromKingdee 再 scan）。
  - VO 新字段：`String effectiveStartDate; String effectiveEndDate;`。
  - 内部：`static String gateStatus(String documentStatus)`（`"C"`→已下达；`"A"/"B"`→待生产；其余 null）。`FIELD_KEYS` 追加 `,FDate`（索引 7）。
- 行为：过滤串按 billNo/日期区间拼接；日期空时默认窗口=最近 7 天（手工）并回填 VO；Job 窗口由调用方传入。多物料跳过（既有）；**同单多批次（FLot 去重>1）跳过**（spec M2）；`gateStatus` 为 null 的整单跳过并写 messages；每单处理完成写 `IWmsSyncLogService.logSync(ERP_TO_WMS, WORK_ORDER, 单号, KINGDEE, 成功/失败, …)`；dto 置 `source=ERP`、`erpDocumentStatus=原始值`、`workOrderStatus=gateStatus 结果`。
- Consumes: Task 3 的 push 合并、Task 4 的日志服务。

- [ ] **Step 1: 写失败测试**：`gateCMapsReleased_ABMapsPending_unknownSkipped`；`multiLotOrderSkippedWithMessage`（两行 FBillNo 同、FLot 异 → skipped+1、messages 含「批次」）；`unknownDocStatusSkippedNotImported`（FDocumentStatus=D → 不落库）；`dateWindowFilterBuiltAndEchoed`（断言 executeBillQuery 收到的 filter 含 `FDate>=` 且 VO 回填窗口）；`syncSingleFallsBackForScan`（本地无→同步→scan 返回 VO）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现上述接口与行为**；删除 `mapStatus()` 与 `status-map` 读取。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit** `feat(wms): 金蝶工单同步按审核门槛落地并留痕`

### Task 6: Controller 改造与 scan() 去 MES 回源

**Files:**
- Modify: `.../wms/controller/WmsWorkOrderController.java`
- Modify: `.../wms/service/IWmsWorkOrderService.java`、`.../wms/service/impl/WmsWorkOrderServiceImpl.java`
- Modify: `.../wms/service/IWmsProductionInboundService.java`、`.../wms/service/impl/WmsProductionInboundServiceImpl.java`（新增 `List<WmsProductionInbound> listByWorkOrderNo(String workOrderNo)`，上限 100，按提交时间倒序）
- Modify: `.../wms/controller/WmsProductionController.java:57-67`（scan 改调 `kingdeeSyncService.scanWithErpFallback`）

**Interfaces:**
- Produces:
  - 删除端点：`POST /add`、`PUT /edit`、`DELETE /{id}`（连同 Controller 方法与 `addWorkOrder/editWorkOrder/removeWorkOrder` 及其测试用例）；`fetchFromMes` 一并删除。
  - `GET /list` 增加 `source/syncStatus/startDate/endDate` 参数（透传 `listPage(9 参)`）。
  - `POST /sync-kingdee`：参数 `billNo/startDate/endDate/top`，权限 `wms:work-order:sync`，加 `@AutoLog("生产工单-ERP同步")`，返回 VO（含生效窗口）。
  - `POST /push`：加 `@AutoLog("生产工单-MES推送")`；`pushToken` 校验不变；日志记录调用方 IP（`IpUtils`/request.getRemoteAddr() 写入 remark 同级字段或仅 @AutoLog）。
  - `GET /inbound-records?workOrderNo=` → `Result<List<WmsProductionInbound>>`。

- [ ] **Step 1: 改接口与实现**（本任务为端点编排，无新算法；`listPage` 过滤用 QueryWrapper 条件追加）。
- [ ] **Step 2: 全模块编译 + 既有工单相关测试跑通**（`WmsWorkOrderServiceImplTest` 删掉 add/edit/remove 用例；`WmsKingdeeWorkOrderSyncServiceImplTest`、`WmsProductionInboundServiceImplTest` 全绿）。
- [ ] **Step 3: Commit** `feat(wms): BD-002 下线工单手工端点并开放ERP同步/详情接口`

### Task 7: 下游门槛——RK-002 接受已下达、CK-001 回源+状态校验

**Files:**
- Modify: `.../wms/service/impl/WmsProductionInboundServiceImpl.java:86-89`
- Modify: `.../wms/service/impl/WmsOutboundServiceImpl.java`（`scanPick` :632-660、`createPick` :662-694）
- Test: `.../wms/service/impl/WmsProductionInboundServiceImplTest.java`、`.../wms/service/impl/WmsOutboundScanTest.java`、`.../wms/service/impl/WmsOutboundCreateTest.java`

**Interfaces:**
- Produces:
  - RK-002 允许集合：`已下达/生产中/已完成`（`WORK_ORDER_STATUS_RELEASED` 加入既有判断）；拒绝文案不变（`工单状态不允许入库`）。
  - `WmsOutboundServiceImpl` 注入 `IWmsKingdeeWorkOrderSyncService`；`scanPick` 本地查不到 → `syncSingleFromKingdee(workOrderNo)` → 再查本地，仍无则原文案 `工单号无效`。
  - `createPick`：工单状态 ∉ {已下达, 生产中, 已完成} → `RuntimeException("工单状态不允许领料")`。
- 无循环依赖：outbound/sync 服务不被金蝶同步服务依赖。

- [ ] **Step 1: 写失败测试**：RK-002 `releasedOrderAccepted_pendingRejected`；CK-001 `createPickRejectsPendingOrder`、`scanPickSyncsFromKingdeeOnMiss`（mock 同步服务，验证本地 miss 时调用了 syncSingle 且第二次查询命中）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现两处门槛与回源**。
- [ ] **Step 4: 跑测试确认通过**（三个测试类全跑）。
- [ ] **Step 5: Commit** `feat(wms): RK-002/CK-001 接受已下达工单并接ERP回源`

### Task 8: `WmsWorkOrderSyncJob` + Registrar

**Files:**
- Create: `.../wms/job/WmsWorkOrderSyncJob.java`、`.../wms/job/WmsWorkOrderSyncJobRegistrar.java`
- Modify: `.../wms/constant/WmsConstants.java`（Task 2 已加 ID/CRON）

**Interfaces:**
- Produces: Job 两阶段执行（spec §8）：①`syncFromKingdee(null, windowStart, now, 50)` 循环（windowStart=`wms_sync_log` 中 WORK_ORDER 最近一次成功时间 -30min，无记录则最近 7 天；每批后若 `queried<50` 停）；②`listFailedObjectNos(WORK_ORDER, 3, 7)` 逐单 `syncSingleFromKingdee`；连续失败 3 次发站内告警（照抄 `WmsProductionSyncRetryJob` 的告警调用）。Registrar 照抄 `WmsProductionSyncJobRegistrar` 结构，`@EventListener(ApplicationReadyEvent)` 自注册，`wms.sync.job-enabled` 同款开关 key 复用。
- Consumes: Task 4/5 的服务。

- [ ] **Step 1: 实现 Job 与 Registrar**（既有模式照抄，无新算法；DisallowConcurrentExecution 加在 Job 类上）。
- [ ] **Step 2: 编译 + 启动验证注册**：启动后 `SELECT * FROM sys_quartz_job WHERE id='1920000000000000340'` 存在、日志出现「已存在于调度器中，跳过注册」或注册成功行。
- [ ] **Step 3: Commit** `feat(wms): 工单ERP同步定时任务（增量窗口+失败单重试）`

### Task 9: 前端工单页改造

**Files:**
- Modify: `jeecgboot-vue3/src/views/wms/workOrder/workOrder.api.ts`
- Modify: `jeecgboot-vue3/src/views/wms/workOrder/workOrder.data.ts`
- Modify: `jeecgboot-vue3/src/views/wms/workOrder/index.vue`
- Delete: `jeecgboot-vue3/src/views/wms/workOrder/components/WorkOrderModal.vue`（若无他处引用）

**Interfaces:**
- Produces:
  - api：`syncKingdeeWorkOrder(params: { billNo?: string; startDate?: string; endDate?: string; top?: number })` → `POST /wms/work-order/sync-kingdee`；`fetchInboundRecords(workOrderNo)` → `GET /wms/work-order/inbound-records`；删除 `saveOrUpdateWorkOrder/deleteWorkOrder`。
  - data：状态选项 `待生产/已下达/生产中/已完成/已关闭`（含颜色映射：已下达=blue）；列增加 来源/同步状态/最近同步时间；删除 `formSchema`。
  - index.vue：工具栏 = 查询 + 「从ERP同步」（权限 `wms:work-order:sync`）+ 详情；同步弹窗四字段，完成后展示 `queried/imported/updated/skipped` 与 messages、生效窗口提示（spec M5）；行操作仅「详情」，详情抽屉含入库记录列表。

- [ ] **Step 1: 改 api/data/index.vue 并删除 Modal**；`npx eslint src/views/wms/workOrder` 通过。
- [ ] **Step 2: `pnpm build` 通过**（vue-tsc 类型门禁）。
- [ ] **Step 3: Commit** `feat(wms): 工单页改为ERP同步+只读详情`

### Task 10: 全链路验证

- [ ] **Step 1:** `bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests`，重启后端（8081）。
- [ ] **Step 2:** 浏览器验证：工单页出现在「基础数据」目录；从金蝶同步一条 `FG001173` 类工单 → 列表 `source=ERP`、状态按门槛、同步状态=同步成功；用该工单走 RK-002 提交成功；把该工单在金蝶改为关闭后再同步 → WMS 不重开。
- [ ] **Step 3:** 启动日志 grep「flyway执行sql脚本失败」= 0；`wms_sync_log` 有 WORK_ORDER 成功记录。
- [ ] **Step 4: Commit（如有零散修正）并更新验收报告** `plans/2026-10-22-yx-wms-bd-002-production-work-order-验收报告.md`。

## Out of scope（本期不做，避免蔓延）

- 前端共享 `WmsErpWorkOrderSelect` 组件（当前输入框 + 后端回源保持可用 UX，组件化放 P1）。
- `生产中/已完成` 的 WMS 自动流转（spec §4.4 明确另立需求）。
- 目录「物料管理」改名「基础数据」、`status-map` 配置项删除（仅代码停用）。
