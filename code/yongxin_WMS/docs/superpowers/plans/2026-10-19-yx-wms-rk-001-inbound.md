# 永鑫WMS 采购入库流程（YX-WMS-RK-001）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现采购入库全流程：扫码收料 → 检验 → 库位分配 → 上架入库（更新库存）→ 自动审核 → ERP 同步（失败自动重试），四个异常分支全落地。

**Architecture:** 单据合一（wms_inbound_order 走 8 态状态机），后端在既有 `jeecg-boot-module-wms` 内新增 inbound/inspection/inventory 三组 entity-mapper-service-controller，ERP 同步照抄既有 wms_order 的 Gateway+RetryJob 范式；前端在 `src/views/wms/inbound/` 下三页面；菜单/种子走幂等 Flyway 脚本 V3.9.5_11。

**Tech Stack:** Spring Boot 4.1 / MyBatis-Plus 3.5.16 / JUnit5+Mockito / Flyway / Vue 3 + Ant Design Vue 4 / Vite

**Spec:** `docs/superpowers/specs/2026-10-19-yx-wms-rk-001-inbound-design.md`（同仓库；口径表 §4.1 的 7 条决定是本计划的直接依据，实施者必读）

## Global Constraints

- Maven 一律 `bash /d/tools/m.sh -P dev ...`（mvn 不在 PATH；`-P dev` 永不省略）
- 单测命令带 `-Dsurefire.failIfNoSpecifiedTests=false`；模块测试在 `jeecg-boot` 目录下执行
- 提交信息 Conventional Commits（feat/test/chore…，header ≤108 字符），结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；前后端同一个 git 仓库，从 `D:\yongxin-WMS\JeecgBoot` 根提交
- 所有接口返回 `Result<T>`（`Result.OK(...)` / `Result.error(...)`）；业务校验失败抛 `RuntimeException(中文文案)`，controller catch 后 `Result.error(e.getMessage())` 原样透出（不要依赖全局异常处理器）
- 新表 `wms_` 前缀；实体 extends `JeecgEntity`；除 wms_inventory 外都带 `deleted_at` + `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")`；不建物理外键
- 禁止魔法值：状态/类型常量全部进 `WmsConstants`（已有 `WAREHOUSE_TYPE_STEREO="立库"`、`WAREHOUSE_TYPE_LINE_SIDE="线边柜"`、`SYNC_MAX_RETRY=3`、`SYNC_SCAN_BATCH=50`、`LOCATION_STATUS_IDLE="空闲"`、`LOCATION_STATUS_OCCUPIED="占用"`、`SYNC_STATUS_*` 直接复用）
- Flyway 脚本必须可重复执行（INSERT IGNORE / 带 name 条件的 UPDATE）；权限 ID 段 `1910000000000000xxx`、授权/任务 ID 段 `1920000000000000xxx`
- 后端集成验证前，改完的 wms 模块必须 `install` + 重启后端 JVM（仅 Task 10 做一次）
- 前端路径别名用 `/@/`；页面风格参照 `src/views/wms/order/index.vue`
- 所有面向用户的文案为简体中文

## Review Focus

1. **同步失败不得阻断入库**——ERP 网关抛错时，入库单仍须完成「已审核」，失败只落库+告警。测试：Task 7 单测 `syncFailureDoesNotRollbackAudit`（由 Task 6 的 putaway 调用路径触发）。
2. **putaway 的并发与重复调用**——同单二次 putaway、库位被并发抢占必须被状态/行级条件拒绝而非脏写。测试：Task 6 `putawayRejectsNonPendingState`、`putawayFailsWhenLocationOccupiedConcurrently`。
3. **实收=0 的行不得写库存、不得占库位**——短装行跳过后库存表和库位状态都无痕迹。测试：Task 6 `putawaySkipsZeroActualRows`。
4. **库位不足时部分分配生效 + 明确告警**——不是整单失败也不是静默。测试：Task 5 `autoAllocateReturnsWarningsWhenInsufficient`。
5. **Flyway 静默失败**——迁移坏了应用照常启动（有前科）。检查：Task 10 步骤 2（grep 启动日志为 0 + history success=1），不可跳过。

---

### Task 1: Flyway 迁移 V3.9.5_11（4 表 DDL + 菜单 + 种子）

**Files:**
- Create: `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_11__wms_inbound.sql`

**Interfaces:**
- Produces: 表 `wms_inbound_order` / `wms_inbound_detail` / `wms_inspection_task` / `wms_inventory`；菜单「入库管理」（目录，url=/wms/inbound，redirect=/wms/inbound/receive）→「采购入库」(url=/wms/inbound/receive, component=wms/inbound/index, route name InboundReceive)、「检验任务」(url=/wms/inbound/inspection, component=wms/inbound/InspectionList, route name InboundInspection)；按钮权限 `wms:inbound:receive/allocate/putaway/void/sync`、`wms:inspection:do`；「基础管理」改名「基础数据」；线边柜库区 + 8 个空闲库位种子；sys_quartz_job 种子行（WmsInboundSyncRetryJob）

- [ ] **Step 1: 写迁移脚本**

要点（照抄 V3.9.5_3/4/5 的既有写法）：
- 4 张表 `CREATE TABLE IF NOT EXISTS`，字段与规格见 spec §6；`order_no`/`task_no` 加 `UNIQUE INDEX uk_wms_inbound_order_no` / `uk_wms_inspection_task_no`；inventory 加 `UNIQUE INDEX uk_wms_inv_mat_loc_batch (material_id, location_id, batch_no)`；detail/inventory 建 `idx_` 常规索引（inbound_id、material_id、location_id）；inbound_order 上 sync/audit 字段与 wms_order 同名同型
- 改名：`UPDATE sys_permission SET name='基础数据' WHERE id='1910000000000000001' AND name='基础管理';`
- 目录行（menu_type=0，is_route=0，always_show=1，icon=ant-design:import-outlined）id=`1910000000000000081`；两个菜单行 id=`1910000000000000082`（sort 1）/`1910000000000000083`（sort 2），parent=0081；按钮行 id=`1910000000000000091`～`096`（perms 顺序 receive/allocate/putaway/void/sync/inspection:do，父分别挂 0082/0083）；admin 授权（role_id=`f6817f48af4fb3af11b9e8bf182f618b`）id=`1920000000000000141`～`152`
- 线边柜种子：库区 id=`2110885981815907000`，warehouse_name=线边柜一组，warehouse_type=线边柜，status='1'；库位 id=`2110885981815907001`～`008`，location_code=XB-G01…XB-G08，status=空闲
- quartz 种子：id=`1920000000000000102`，job_class_name=org.jeecg.modules.wms.job.WmsInboundSyncRetryJob，cron=`0 8/5 * * * ? *`，description=WMS入库单ERP同步补偿任务（id 必须与 Task 7 常量 `WmsConstants.INBOUND_SYNC_JOB_ID` 同值）

- [ ] **Step 2: 自查幂等与 ID 冲突**

Run: `grep -c "INSERT IGNORE" jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_11__wms_inbound.sql` 及通读一遍
Expected: 所有 INSERT 均带 IGNORE；UPDATE 均带 name/状态条件；无与既有脚本重复的 ID（`grep -rn "1910000000000000081\|1920000000000000102\|2110885981815907000" jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/ | grep -v V3.9.5_11` 应为空）

- [ ] **Step 3: Commit**

```bash
git add jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_11__wms_inbound.sql
git commit -m "feat(wms): RK-001 入库四表与入库管理菜单迁移脚本"
```

（实际落库验证在 Task 10 Step 2——Flyway 失败不阻断启动，必须以日志为准。）

---

### Task 2: 常量 + 实体 + Mapper

**Files:**
- Modify: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`
- Create: `entity/WmsInboundOrder.java`、`entity/WmsInboundDetail.java`、`entity/WmsInspectionTask.java`、`entity/WmsInventory.java`（均在 `org.jeecg.modules.wms` 包下）
- Create: `mapper/WmsInboundOrderMapper.java`、`mapper/WmsInboundDetailMapper.java`、`mapper/WmsInspectionTaskMapper.java`、`mapper/WmsInventoryMapper.java`、`mapper/xml/WmsInventoryMapper.xml`
- Test: `jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/entity/WmsInboundEntityMappingTest.java`

**Interfaces:**
- Produces:
  - 常量：`INBOUND_TYPE_PURCHASE="采购入库"`；`INBOUND_STATUS_PENDING_INSPECT="待检验"`、`INBOUND_STATUS_PENDING_ALLOCATE="待分配"`、`INBOUND_STATUS_PENDING_PUTAWAY="待上架"`、`INBOUND_STATUS_PUTAWAY="已入库"`、`INBOUND_STATUS_AUDITED="已审核"`、`INBOUND_STATUS_REJECTED="已拒收"`、`INBOUND_STATUS_INSPECT_FAILED="检验不合格"`、`INBOUND_STATUS_VOIDED="作废"`；`CATEGORY_TYPE_HALF_FINISHED="半成品"`、`CATEGORY_TYPE_AUXILIARY="辅料"`；`INSPECT_STATUS_PENDING="待检验"`、`INSPECT_STATUS_PASSED="合格"`、`INSPECT_STATUS_FAILED="不合格"`
  - 实体：四表字段与 spec §6 一致，`WmsInboundOrder` 额外含 purchaseOrderNo/supplierId/supplierName/supplierCode/inboundStatus/rejectReason/syncStatus/retryCount(Integer)/lastSyncError/syncTime/auditBy/auditTime；`WmsInventory` **无** deletedAt 字段；其余三个 extends JeecgEntity + deletedAt/@TableLogic（与 WmsSupplier 相同注解）
  - `WmsInventoryMapper.xml` 提供：`int upsertInventory(WmsInventory inv)` —— `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)`

- [ ] **Step 1: 写映射测试**（仿 `WmsEntityMappingTest`）：断言 4 个实体的 `@TableName` 分别为 `wms_inbound_order/wms_inbound_detail/wms_inspection_task/wms_inventory`，且 `WmsInventory.class.getDeclaredField("deletedAt")` 抛 `NoSuchFieldException`（无逻辑删除）、`WmsInboundOrder.class.getDeclaredField("deletedAt")` 存在
- [ ] **Step 2: 跑测试确认失败**

Run: `cd jeecg-boot && bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsInboundEntityMappingTest -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（类不存在，编译错误即预期失败形态）

- [ ] **Step 3: 实现常量 + 实体 + Mapper（含 xml upsertInventory）**
- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令，Expected: PASS）
- [ ] **Step 5: Commit** — `git commit -m "feat(wms): RK-001 入库域常量与四实体"`

---

### Task 3: 收料（receive）

**Files:**
- Create: `dto/WmsReceiveDTO.java`（purchaseOrderNo、packageDamaged、`List<Item> details`，Item：materialId、quantity(BigDecimal)、batchNo；内嵌静态类）
- Create: `vo/WmsInboundDetailVO.java`（order、details、inspection 三块）
- Create: `service/IWmsInboundService.java`、`service/impl/WmsInboundServiceImpl.java`
- Create: `controller/WmsInboundController.java`
- Test: `service/impl/WmsInboundServiceImplTest.java`

**Interfaces:**
- Consumes: `IWmsOrderService`（`getById` 校验采购订单：orderNo 匹配且 orderType=`WmsConstants.ORDER_TYPE_PURCHASE`）、`IWmsMaterialService.getById`、`IWmsMaterialCategoryService.getById`（取 categoryType）、既有 mapper
- Produces:
  - `WmsInboundOrder receive(WmsReceiveDTO dto)` —— 单号生成 `SLyyyyMMdd+4位当日序号`（用 `WmsLocationCodeGenerator` 同款风格：当日最大单号查询 +1）；校验链：采购订单存在→物料存在→分类类型 ∈ {半成品,辅料}（否则行级文案「物料[%s]类型不支持采购入库」）→quantity>0→batchNo 非空。packageDamaged=true → 单状态=已拒收+rejectReason=「外包装损坏，联系采购退货」，不建检验任务；否则状态=待检验 + 建 `wms_inspection_task`（task_no=`JY+同款序号`、inspect_status=待检验）
  - Controller：`POST /wms/inbound/receive`（@RequiresPermissions? 无——本模块用 v-auth 前端控制+后端不做注解，与 wms/order 现状一致）返回 `Result<WmsInboundOrder>`

- [ ] **Step 1: 写失败测试**（Mockito 纯单测，仿 `WmsOrderServiceImplTest` 的构造风格）：`receiveCreatesPendingInspectOrder`、`receiveRejectedWhenPackageDamaged`、`receiveFailsWhenPurchaseOrderNotFound`（断言异常文案「采购订单不存在」）、`receiveFailsWhenMaterialNotFound`、`receiveFailsWhenCategoryTypeNotSupported`
- [ ] **Step 2: 跑测确认失败**（命令同 Task 2 Step 2，-Dtest=WmsInboundServiceImplTest）
- [ ] **Step 3: 实现 DTO/VO/Service/Controller**
- [ ] **Step 4: 跑测通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 扫码收料生成收料通知单`

---

### Task 4: 检验任务（list + complete）

**Files:**
- Create: `service/IWmsInspectionService.java`、`service/impl/WmsInspectionServiceImpl.java`、`controller/WmsInspectionController.java`、`vo/WmsInspectionTaskVO.java`（task 字段 + 单号/供应商冗余展示字段）
- Modify: `service/IWmsInboundService.java` + `impl/WmsInboundServiceImpl.java`（新增 `void onInspectResult(String inboundId, boolean passed)`）
- Test: `service/impl/WmsInspectionServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 实体、Task 3 的 inbound service（complete 需要把单据状态改待分配/检验不合格——通过 `IWmsInboundService.onInspectResult(String inboundId, boolean passed)` 回写，避免检验服务直改他人表）
- Produces:
  - `IPage<WmsInspectionTaskVO> listPage(int pageNo, int pageSize, String inspectStatus)`
  - `void complete(String taskId, String result, String remark)` —— result ∈ {合格,不合格}（常量 INSPECT_STATUS_*）；合格→单据→待分配；不合格→单据→检验不合格；任务已非待检验→抛「该任务已处理」

- [ ] **Step 1: 失败测试**：`completePassedMovesOrderToPendingAllocate`、`completeFailedMarksOrderInspectFailed`、`completeRejectsProcessedTask`
- [ ] **Step 2: 跑测确认失败**
- [ ] **Step 3: 实现**（Controller：`GET /wms/inspection/list`、`POST /wms/inspection/{id}/complete`，body `{result, remark}`）
- [ ] **Step 4: 跑测通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 检验任务列表与检验结果回写`

---

### Task 5: 库位自动分配 + 空闲库位查询

**Files:**
- Modify: `service/IWmsLocationService.java` + `impl/WmsLocationServiceImpl.java`（新增一个方法）
- Modify: `service/IWmsInboundService.java` + impl、`WmsInboundController.java`
- Create: `vo/WmsAllocateResultVO.java`（allocatedCount、`List<String> warnings`、`List<Unallocated> unallocated`，Unallocated：detailId、materialName、reason）
- Test: `service/impl/WmsInboundServiceImplTest.java`（追加）、`impl/WmsLocationServiceImplTest.java`（追加）

**Interfaces:**
- Consumes: Task 2 实体、`WmsConstants.WAREHOUSE_TYPE_STEREO/LINE_SIDE`、物料→分类类型链（口径 10：不向上继承，空=其他）
- Produces:
  - `List<WmsLocation> listFreeByWarehouseType(String warehouseType)`（IWmsLocationService；locationStatus=空闲 按 location_code 升序）
  - `WmsAllocateResultVO autoAllocate(String inboundId)` —— 状态须为待分配；每行取物料分类类型：半成品→立库、辅料→线边柜、空/其他→不自动分配（unallocated，reason=「该物料类型不支持自动分配」）；每行取该类型空闲库位数（减去前面行已占用的候选），无库位→unallocated（reason=「库位不足」）+ warning「库位不足，请人工介入」；有则按序写 detail.location_id；**不翻库位状态**（口径 8）；全部行有归属 → 单状态=待上架，否则保持待分配
  - Controller：`POST /wms/inbound/{id}/auto-allocate`、`GET /wms/inbound/free-locations?materialId=&warehouseType=`

- [ ] **Step 1: 失败测试**：`autoAllocateMapsHalfFinishedToStereo`、`autoAllocateMapsAuxiliaryToLineSide`、`autoAllocateReturnsWarningsWhenInsufficient`（立库空闲 1 个、半成品 2 行 → 1 行分配 1 行 unallocated + warning）、`autoAllocateSkipsUnknownCategoryType`、`autoAllocateRejectsWrongState`
- [ ] **Step 2: 跑测确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 库位自动分配与不足告警`

---

### Task 6: 上架确认 putaway（事务核心）

**Files:**
- Modify: `service/IWmsInboundService.java` + impl、`WmsInboundController.java`
- Create: `dto/WmsPutawayDTO.java`（`List<Item> details`：detailId、actualQuantity(BigDecimal)、locationId）
- Test: `service/impl/WmsInboundServiceImplTest.java`（追加）

**Interfaces:**
- Consumes: Task 2（实体/upsertInventory）、Task 5（库位查询）、`IWmsInboundSyncService`（Task 7，本任务先以接口类型出现，测试中 mock；两任务存在接口依赖，先写接口签名 `WmsOrderSyncAttempt syncInbound(WmsInboundOrder order, boolean announce)` 于本任务提交的 `IWmsInboundSyncService.java` 空实现类骨架，Task 7 填实现）
- Produces:
  - `void putaway(String inboundId, WmsPutawayDTO dto)` —— 校验：状态 ∈ {待分配, 待上架}（否则「当前状态不允许上架」）；行 locationId 必填且库位状态=空闲、类型匹配（口决算例：半成品物料行选了线边柜库位→「库位类型与物料不匹配」）；actualQuantity≥0 且整单至少一行>0；实收≠应收的行由前端二次确认，后端照实录入。事务内（`@Transactional(rollbackFor=Exception.class)`）：写 actual_quantity/location_id → 实收>0 的行 `upsertInventory` → 逐库位 `UPDATE wms_location SET status='占用' WHERE id=? AND status='空闲'`（影响 0 行→抛「库位已被占用」整体回滚）→ 单状态=已入库 + audit_by=system/audit_time=now → 调 `syncInbound(order, true)`，其结果由 Task 7 落库，**任何同步异常不得使事务回滚**（try/catch 包裹，catch 里落 sync_status=同步失败+last_sync_error）
  - Controller：`POST /wms/inbound/{id}/putaway`

- [ ] **Step 1: 失败测试**：`putawayWritesInventoryAndAudits`、`putawaySkipsZeroActualRows`（0 行不入库存、不占库位）、`putawayRejectsNonPendingState`、`putawayFailsWhenLocationOccupiedConcurrently`（update 返回 0 → 异常且文案「库位已被占用」）、`putawayRejectsWarehouseTypeMismatch`、`syncFailureDoesNotRollbackAudit`（mock 同步抛异常 → 单据仍已入库+已审核字段落库，sync_status=同步失败）
- [ ] **Step 2: 跑测确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 上架确认事务（库存/占用/审核）`

---

### Task 7: ERP 同步网关 + 补偿 Job + 手动重试

**Files:**
- Create: `sync/WmsInboundErpSyncGateway.java`（接口）、`sync/HttpWmsInboundErpSyncGateway.java`
- Create: `service/IWmsInboundSyncService.java`、`service/impl/WmsInboundSyncServiceImpl.java`
- Create: `job/WmsInboundSyncRetryJob.java`、`job/WmsInboundSyncJobRegistrar.java`
- Modify: `constant/WmsConstants.java`（`INBOUND_SYNC_JOB_ID="1920000000000000102"`、`INBOUND_SYNC_JOB_CLASS` 全类名、`INBOUND_SYNC_STALE_MINUTES`）、`controller/WmsInboundController.java`（`POST /wms/inbound/{id}/sync`）
- Test: `sync/HttpWmsInboundErpSyncGatewayTest.java`（仿 `HttpWmsOrderSyncGatewayTest`）、`impl/WmsInboundSyncServiceImplTest.java`、`job/WmsInboundSyncRetryJobTest.java`（仿 `WmsOrderSyncRetryJobTest`）

**Interfaces:**
- Consumes: Task 6 的 putaway 触发点、`WmsOrderSyncAttempt`（**复用**，字段 success/errorMessage/retryCount 与单据无关）、`WmsConstants.SYNC_*`
- Produces:
  - `WmsOrderSyncAttempt syncInbound(WmsInboundOrder order, boolean announce)`（IWmsInboundSyncService）：失败自动重试至多 `SYNC_MAX_RETRY` 次（首次+3）；仍失败且 announce=true 投递站内消息告警（照抄订单同步服务的告警实现方式）；结果由调用方（service 层 persistSyncResult）落库
  - `WmsOrderSyncResult sync(WmsInboundOrder order, List<WmsInboundDetail> details)`（Gateway）：`@Value("${wms.sync.erp.inbound-url:}")`——**未配置时返回 mock 成功**（与订单网关相反，spec §7 明确），result 标注「mock」；配置了 URL 则真实 HTTP POST
  - `IWmsInboundService` 增加：`Date syncStaleBefore()`、`List<WmsInboundOrder> selectSyncCandidates(Date staleBefore, int batch)`、`int claimForSync(String id, Date staleBefore)`（UPDATE ... SET sync_status='同步中' WHERE id=? AND sync_status IN ('待同步','同步失败') AND update_time < staleBefore，返回影响行数）、`void persistSyncResult(String id, WmsOrderSyncAttempt attempt)`（成功→同步成功+sync_time；失败→同步失败+retry_count 累计+last_sync_error；retry_count>SYNC_MAX_RETRY → 额外告警日志 log.error）
  - Job：照抄 `WmsOrderSyncRetryJob`（无参构造器+字段注入+包级 `scanAndResyncForTest()`+DisallowConcurrentExecution）；Registrar 照抄 `WmsOrderSyncJobRegistrar`（用 INBOUND_SYNC_JOB_ID/CLASS 常量）

- [ ] **Step 1: 失败测试**：`gatewayMockSucceedsWhenUrlUnconfigured`、`gatewayPostsWhenConfigured`、`syncServiceRetriesUpToMax`、`jobClaimsAndResyncsFailedInbounds`、`persistSyncResultAccumulatesRetryCount`
- [ ] **Step 2: 跑测确认失败**（三个测试类分三次跑）
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测全部通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 入库单 ERP 同步与补偿任务`

---

### Task 8: 作废 + 单据查询

**Files:**
- Modify: `service/IWmsInboundService.java` + impl、`WmsInboundController.java`
- Create: `dto/WmsVoidDTO.java`（reason）
- Test: `service/impl/WmsInboundServiceImplTest.java`（追加）

**Interfaces:**
- Produces:
  - `void voidOrder(String inboundId, String reason)` —— 状态 ∈ {待检验, 待分配, 待上架} 才可作废（否则「当前状态不可作废」）；置状态=作废 + rejectReason=「作废：」+reason；同时把未处理检验任务置「已作废」？——**否**：检验任务保持待检验列表之外（联查时按单据状态过滤展示），任务行不动（YAGNI）
  - `IPage<WmsInboundOrder> listPage(int pageNo, int pageSize, String inboundStatus, String orderNo, String supplierName)`（QueryGenerator 风格 like 查询）
  - `WmsInboundDetailVO detail(String id)`（order + details + inspection；不存在→「单据不存在」）
  - Controller：`POST /wms/inbound/{id}/void`、`GET /wms/inbound/list`、`GET /wms/inbound/{id}`、`POST /wms/inbound/{id}/sync`（Task 7 已加则不重复）

- [ ] **Step 1: 失败测试**：`voidSetsTerminalStateFromPendingInspect`、`voidRejectsAuditedOrder`、`voidRejectsBlankReason`
- [ ] **Step 2: 跑测确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测通过**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 单据作废与查询接口`

---

### Task 9: 删除守卫 blocker + 释放校验

**Files:**
- Modify: `service/impl/WmsMaterialServiceImpl.java`（checkDelete 追加库存 blocker，空白框架处）、`impl/WmsLocationServiceImpl.java`（checkDelete 追加 + 既有「释放」方法加校验）
- Test: `impl/WmsMaterialServiceImplTest.java`、`impl/WmsLocationServiceImplTest.java`（各追加）

**Interfaces:**
- Consumes: Task 2 的 `WmsInventoryMapper`（selectCount by material_id / location_id）
- Produces: blocker 文案——物料：「该物料已存在库存记录（N 条），不可删除」；库位：「该库位已存在库存记录（N 条），不可删除」；释放校验文案：「该库位已有库存，不能释放」（先清库存才可释放，本期无出库，此为人工兜底路径）

- [ ] **Step 1: 失败测试**：`materialDeleteBlockedByInventory`、`locationDeleteBlockedByInventory`、`locationReleaseBlockedByInventory`
- [ ] **Step 2: 跑测确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测通过（含两文件既有测试不回归）**
- [ ] **Step 5: Commit** — `feat(wms): RK-001 库存引用纳入删除守卫`

---

### Task 10: 后端集成验证（install + 重启 + Flyway + API E2E）

**Files:**
- Create: `.superpowers/sdd/2026-10-19-yx-wms-rk-001-inbound/e2e-inbound.mjs`（Node 脚本，session 产物不入库；fetch 走 `http://localhost:3100/jeecgboot` 代理）

**Interfaces:**
- Consumes: Task 3-8 全部接口；登录方式照抄 `.superpowers/sdd/2026-10-15-.../ui-check.js`（/sys/login 取 token，验证码不校验）

- [ ] **Step 1: 安装模块**

Run: `cd jeecg-boot && bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 重启后端并验证 Flyway**

提示用户后端由其环境运行——**此步必须重启后端 JVM**（新接口生效前提，spec §3 已获认可）。重启后：
Run: `grep -c "flyway执行sql脚本失败" <启动日志>` Expected: `0`；再查 `flyway_schema_history` 中 `V3.9.5_11` 行 success=1（经 API 无直接通道，以日志无失败 + 菜单/线边柜种子出现为准）
Run: `curl` 检查 `GET /wms/warehouse/list` 出现「线边柜一组」、菜单接口出现「入库管理」「基础数据」

- [ ] **Step 3: API E2E 脚本断言全流程**

场景序列（每步断言 success 与状态字段）：
1. 收料正常单（采购订单号取 `GET /wms/order/list?orderType=采购订单` 首单；物料取 半成品 1 行 + 辅料 1 行）→ 断言状态=待检验
2. 异常：不存在的订单号 → 断言失败文案「采购订单不存在」；物料分类类型不支持（若有此类物料）→ 行级文案
3. 检验合格 → 待分配
4. auto-allocate → 半成品行得立库库位、辅料行得线边柜库位
5. putaway（实收=应收）→ 已审核 + sync_status=同步成功（mock）+ inventory 数量正确 + 库位翻占用
6. 异常：对同一单再次 putaway → 「当前状态不允许上架」
7. 第二张收料单 → 检验不合格 → 检验不合格状态
8. 第三张收料单 → 作废 → 作废状态；再作废一次 → 「当前状态不可作废」
9. 异常：库位不足（可选，构造法：将线边柜 8 库位全部预占后收辅料单——若操作过重可降级为依赖 Task 5 单测证明，E2E 跳过并在报告注明）
10. ERP 失败重试：`POST /wms/inbound/{id}/sync` 在配置了不可达 URL 时才可真演练——E2E 仅断言 mock 成功路径；失败路径以 Task 7 单测为准（报告注明）

Run: `node .superpowers/sdd/2026-10-19-yx-wms-rk-001-inbound/e2e-inbound.mjs`
Expected: 全部 PASS 输出，退出码 0

- [ ] **Step 4: 全模块测试回归**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS，0 Failures

- [ ] **Step 5: Commit（E2E 脚本若需微调代码则一并）** — `test(wms): RK-001 采购入库 API 端到端验证`

---

### Task 11: 前端接口封装 + 采购入库页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/inbound/inbound.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/inbound/index.vue`

**Interfaces:**
- Consumes: Task 3-8 全部后端接口；`defHttp`；菜单路由 `/wms/inbound/receive`（Task 1 已种）
- Produces:
  - `inbound.api.ts`：`receiveInbound(params)`、`listInbound(params)`、`getInboundDetail(id)`、`autoAllocate(id)`、`listFreeLocations(params)`、`putaway(id, params)`、`voidInbound(id, reason)`、`resyncInbound(id)`（全部 defHttp 封装）
  - `index.vue`：上「收料台」卡片（采购订单号输入框回车提交=模拟扫码枪、「外包装损坏」勾选、明细行编辑表格：物料下拉（`/wms/material/list`）、数量、批次，添加/删行；提交后清空+按钮 loading 防双击）+ 下「收料单列表」（状态 tag 颜色映射、单号/供应商搜索、详情抽屉：明细+检验结果+同步状态+「重试同步」「库位分配」(Task 12 组件，待分配/待上架时显示)「作废」按钮）
- 后端错误文案经 `defHttp` 直接弹出（既有 errorMessage 拦截行为），不重复包装

- [ ] **Step 1: 实现两个文件**
- [ ] **Step 2: eslint**

Run: `cd jeecgboot-vue3 && npx eslint src/views/wms/inbound/`
Expected: 无错误（index.vue 的 multi-word 存量规则除外，若报则与既有 wms 页面同样处理：不改）

- [ ] **Step 3: Commit** — `feat(wms): RK-001 采购入库收料台与单据列表页`

---

### Task 12: 库位分配弹窗

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/inbound/LocationAllocateModal.vue`

**Interfaces:**
- Consumes: Task 11 的 api 封装（autoAllocate/listFreeLocations/putaway）；以 props `inboundId` 打开，`emit('success')` 供父级刷新
- Produces: 弹窗内容——「自动分配」按钮（结果回显：已分配 N 行；warnings 逐条 a-alert 告警）；明细表格每行：物料、应收、实收数量（a-input-number，默认=应收）、库位（a-select，options=按该物料类型过滤的空闲库位，显示 库位编码+库区）；实收=0 的行提交前弹确认（短装提示）；底部「确认上架」（二次确认文案「实收数量与应收不一致，确认按实收入库？」仅在存在差异行时出现）

- [ ] **Step 1: 实现组件并在 Task 11 的详情抽屉接入**
- [ ] **Step 2: eslint 通过**
- [ ] **Step 3: Commit** — `feat(wms): RK-001 库位分配与上架弹窗`

---

### Task 13: 检验任务列表页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/inbound/InspectionList.vue`

**Interfaces:**
- Consumes: `GET /wms/inspection/list`、`POST /wms/inspection/{id}/complete`
- Produces: 待检验/已处理 tab 或状态筛选；行操作「合格」「不合格」（不合格必填原因 Modal 输入，合格可直接确认）；操作成功刷新列表

- [ ] **Step 1: 实现页面**
- [ ] **Step 2: eslint 通过**
- [ ] **Step 3: Commit** — `feat(wms): RK-001 检验任务列表页`

---

### Task 14: 前端浏览器端到端验证

**Files:**
- Create: `.superpowers/sdd/2026-10-19-yx-wms-rk-001-inbound/ui-inbound-check.js`（session 产物；chromium 路径与登录兜底照抄 `.superpowers/sdd/2026-10-15-.../ui-check.js` + 字体缩放任务 `fontscale-ui-check.js` 的 PASS/FAIL 框架）

- [ ] **Step 1: 写并运行脚本**

检查项：菜单「入库管理」与「基础数据」显示且平级；三页面可达且渲染关键文案（收料台/快捷操作、检验任务、库位分配弹窗标题）；浏览器内走一遍主流程（收料→检验合格→分配→上架→列表状态=已审核+同步成功）；异常路径至少验证「订单不存在」文案弹出；截图 ≥4 张（收料台、分配弹窗、检验列表、终态列表）

Run: `node .superpowers/sdd/2026-10-19-yx-wms-rk-001-inbound/ui-inbound-check.js`
Expected: PASS 全绿，退出码 0；人工读截图复核版式

- [ ] **Step 2: 前端整体回归**

Run: `cd jeecgboot-vue3 && npx jest`（既有 15 测试不回归）
Expected: 15 passed

- [ ] **Step 3: Commit** — `test(wms): RK-001 前端浏览器端到端验证`（若有代码修正一并提交）

---

## 完成定义（DoD）

1. Task 10 E2E 全 PASS + Task 14 浏览器全 PASS + 截图人工复核通过
2. `flyway执行sql脚本失败` 日志计数为 0，菜单/线边柜种子在运行库可见
3. 全模块后端测试 0 Failures；前端 jest 15/15
4. 四个异常分支各有可演示的触发路径与明确文案（库位不足与 ERP 失败两条若 E2E 未真演练，以单测证明并在交付报告注明）
