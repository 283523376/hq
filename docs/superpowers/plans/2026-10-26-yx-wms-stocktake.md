# 库存盘点模块 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建库存盘点业务域：盘点单/明细/计划三表 + 状态机 + 冻结与重盘 + 差异阈值复核 + 直接调库存的调整 + WMS→ERP 回写（QTRK/QTCK），前端新增「库存盘点」菜单（定期/周期两个子模块）。

**本期边界（2026-10-26 用户澄清）:** 盘点**只由 WMS 发起**，结果同步到 ERP。ERP→WMS 下发盘点任务、AGV 自动盘点均为**后续可能**，本期不实现；项目当前无 AGV，全部为人工盘点。

**Architecture:** 状态机仿 `WmsTransferStatusResolver`（白名单转移 + 终态判定）；盘点单是**调整凭证本身**，调整直接改 `wms_inventory`，绝不建 inbound/outbound 真单据（避免触发设备下发与销售出库兜底回写）；冻结走 `wms_location.stocktake_frozen` 标记位，在三条库存写入路径上拦截。

**Tech Stack:** Spring Boot（jakarta）+ MyBatis-Plus + Flyway + Quartz + JUnit 5 + Mockito；前端 Vue 3 + Vben。

**Spec:** `docs/superpowers/specs/2026-10-26-yx-wms-outbound-subcontract-sample-stocktake-design.md` §5.3 / §7 / §9（D9–D15）

## Global Constraints

- Maven 一律 `bash /d/tools/m.sh -P dev ...`；改 wms 模块后 `install -DskipTests` + 重启后端。
- Flyway 迁移后 grep 启动日志「flyway执行sql脚本失败」=0，且 `flyway_schema_history` 行 `success=1`。
- 状态机白名单（含复核回退）：`草稿 → 盘点中`、`盘点中 → 差异确认`、`盘点中 → 复核中`（阈值直达）、`差异确认 ⇄ 复核中`、`差异确认 → 已调整`、`已调整 → 已归档`；**任何未列出的转移必须抛异常**。
- 复核人 ≠ 发起人（`wms:stocktake:review` 权限点）；测试环境用 `wms.stocktake.simulate-review`（默认空）跳过该校验，仿 CK-004 的 `simulate-erp-status`。
- 差异阈值判定式（整单）：`(Σ|盘盈| + Σ|盘亏|) / Σ账面数量 >= threshold` → 需复核；**Σ账面 = 0 时任一差异即复核**；threshold ≤ 0 时全部差异都复核。
- 明细粒度必须对齐 `wms_inventory` 唯一键 `(material_id, location_id, batch_no, container_code)`：敏捷仓到料箱、立库 `container_code` 为空。
- 调整**不得**产生 `wms_inbound_order` / `wms_outbound_order` 行。
- 提交：Conventional Commits `wms` scope，仓库根提交，结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## Review Focus

1. **冻结被绕过**——盘点期间的出库/移库/上架仍能改动冻结库位，导致盘点失真 → Task 5 三路径各一条测试。
2. **阈值判定式写错**——多行小差异 vs 单行大差异给出相反结论 → Task 4 三条边界测试（账面 0、threshold 0、合计口径）。
3. **盘点产生真单据**——盘亏若建出库单会触发 RMS 料箱任务与 SAL_OUTSTOCK 回写 → Task 6 测试断言零 outbound/inbound 写入。
4. **复核形同虚设**——同一人确认+复核 → Task 8 测试：复核人等于发起人时抛异常（simulate 关闭时）。
5. **半程回写**——盘盈 QTRK 成功、盘亏 QTCK 失败被当成整体成功 → Task 9 测试：两侧独立记录、失败侧可重试、归档不强制 ERP 成功。

---

### Task 1: Flyway V3.9.5_26（三表 + 冻结位 + 菜单）

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_26__wms_stocktake.sql`

**Interfaces:**
- Produces: 表 `wms_stocktake_order`、`wms_stocktake_detail`、`wms_stocktake_plan`；`wms_location.stocktake_frozen varchar(1) NOT NULL DEFAULT '0'`；菜单「库存盘点」+ 两个子菜单。

- [ ] **Step 1: 写迁移脚本**——DDL 要点（字段名即后续任务使用的列名）：
  - `wms_stocktake_order`：`id, stocktake_no, stocktake_type(定期/周期), scope_desc, status, diff_review_threshold decimal(5,2), book_total decimal(18,4), gain_total decimal(18,4), loss_total decimal(18,4), initiator, reviewer, review_time, adjust_time, archive_time, erp_sync_status, erp_bill_no, remark, deleted_at, create_by/create_time/update_by/update_time`；唯一索引 `uk_wms_stocktake_no(stocktake_no)`。
  - `wms_stocktake_detail`：`id, stocktake_id, location_id, location_code, material_id, material_code, batch_no, container_code, book_qty decimal(18,4), actual_qty decimal(18,4), diff_qty decimal(18,4), diff_reason, recheck_flag varchar(1), adjusted varchar(1), deleted_at, 审计列`；索引 `idx_wms_stocktake_detail_order(stocktake_id)`。
  - `wms_stocktake_plan`：`id, plan_name, plan_type(定期/周期), cron_expr, warehouse_id, rolling_size int, last_run_time, enabled varchar(1), deleted_at, 审计列`。
  - `ALTER TABLE wms_location ADD COLUMN stocktake_frozen varchar(1) NOT NULL DEFAULT '0' COMMENT '盘点冻结：1冻结 0正常'`。
  - 菜单：`sys_permission` 顶层「库存盘点」（`sort_no=94`，`menu_type=0`）+ 两个子菜单（`定期盘点` / `周期盘点`，`component` 指向 `wms/stocktake/index`）+ `sys_role_permission` 授权 admin（照 `V3.9.5_21` 的 INSERT 结构）。

- [ ] **Step 2: 应用并验证**

```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e \
"SELECT version,success FROM flyway_schema_history WHERE version='3.9.5.26'; \
 SHOW TABLES LIKE 'wms_stocktake%'; SHOW COLUMNS FROM wms_location LIKE 'stocktake_frozen';"
```
Expected: `success=1`；3 张表；1 列。
- [ ] **Step 3: Commit** `feat(wms): 盘点三表/库位冻结位/盘点菜单 (V3.9.5_26)`

### Task 2: 常量与状态机

**Files:**
- Modify: `.../wms/constant/WmsConstants.java`
- Create: `.../wms/service/impl/WmsStocktakeStatusResolver.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeStatusResolverTest.java`

**Interfaces:**
- Produces:
  - 常量：`STOCKTAKE_STATUS_DRAFT/COUNTING/DIFF_CONFIRMED/REVIEWING/ADJUSTED/ARCHIVED` = `草稿/盘点中/差异确认/复核中/已调整/已归档`；`STOCKTAKE_TYPE_PERIODIC="定期"`、`STOCKTAKE_TYPE_CYCLE="周期"`；`STOCKTAKE_FROZEN="1"`、`STOCKTAKE_UNFROZEN="0"`。
  - `WmsStocktakeStatusResolver`（`@Component`）：`void assertTransition(String from, String to)`（非法抛 `RuntimeException("盘点单状态不允许从 X 变更为 Y")`）、`boolean isTerminal(String status)`（`已归档`）。

- [ ] **Step 1: 失败测试**：合法转移全部不抛——`草稿→盘点中`、`盘点中→差异确认`、`盘点中→复核中`（阈值超限直达）、`差异确认→复核中`、`复核中→差异确认`（复核通过回退给调整环节）、`差异确认→已调整`、`已调整→已归档`；非法转移抛——`草稿→已调整`、`草稿→已归档`、`已归档→任意`；`isTerminal("已归档")==true`、`isTerminal("盘点中")==false`。
- [ ] **Step 2-4: 确认失败 → 实现（Map<String, Set<String>> 白名单，照 `WmsTransferStatusResolver`）→ 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点状态机与常量`

### Task 3: 盘点单服务（建单 + 账面快照）

**Files:**
- Create: `.../service/IWmsStocktakeService.java`、`.../service/impl/WmsStocktakeServiceImpl.java`、`.../dto/WmsStocktakeCreateDTO.java`（`String stocktakeType; List<String> locationIds; String remark;`）、`.../vo/WmsStocktakeDetailVO.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeCreateTest.java`

**Interfaces:**
- Consumes: `WmsInventoryMapper`（按 location_id 查账面行）、`WmsLocationMapper`、Task 2 常量。
- Produces:
  - `WmsStocktakeOrder create(WmsStocktakeCreateDTO dto, String operator)`——生成 `stocktake_no`（前缀 `PD` + `yyyyMMdd` + 4 位序号）、`status=草稿`、按 `locationIds` 里的库存行生成明细（`book_qty=quantity`、`actual_qty` 留空）、`book_total=Σquantity`；**同时**把范围内 `wms_location.stocktake_frozen` 置 `1`。
  - `void start(String id)`——`草稿→盘点中`（经 resolver 校验）。
  - `WmsStocktakeOrder requireById(String id)`。

- [ ] **Step 1: 失败测试**：建单后捕获订单（status=草稿、bookTotal=库存合计）、明细条数=库存行数且 `bookQty` 与库存一致、`locationMapper.update` 被调用置冻结；`start` 后 status=盘点中；对已归档单调 `start` 抛异常。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点建单与账面快照`

### Task 4: 差异计算与阈值判定

**Files:**
- Create: `.../service/impl/WmsStocktakeDiffCalculator.java`
- Modify: `.../service/IWmsStocktakeService.java`（加 `void recordActual(String id, List<WmsStocktakeDetailVO> actuals)`、`boolean needsReview(WmsStocktakeOrder order)`）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeDiffCalculatorTest.java`

**Interfaces:**
- Consumes: Task 3 明细实体。
- Produces: `WmsStocktakeDiffCalculator.summarize(List<WmsStocktakeDetail> details)` → `BigDecimal[]{bookTotal, gainTotal, lossTotal}`；`boolean needsReview(BigDecimal bookTotal, BigDecimal gainTotal, BigDecimal lossTotal, BigDecimal thresholdPct)`。

- [ ] **Step 1: 失败测试**（纯函数，三条边界必须覆盖）：
  - 账面 100 / 盈 2 / 亏 3，threshold=5 → `(2+3)/100=5% >= 5%` → true；
  - 账面 100 / 盈 1 / 亏 0，threshold=5 → false；
  - 账面 0（整库位盘盈）/ 盈 5 → **true**（分母为 0 时任一差异即复核）；
  - threshold=0 且有任一差异 → true；
  - `summarize` 对空明细返回 `[0,0,0]`。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点差异汇总与复核阈值判定`

### Task 5: 冻结拦截与重盘标记

**Files:**
- Create: `.../service/IWmsStocktakeFreezeGuard.java`、`.../service/impl/WmsStocktakeFreezeGuardImpl.java`
- Modify: `.../service/impl/WmsOutboundAllocatorImpl.java`（分配前对每个候选库位调 `assertNotFrozen`）
- Modify: `.../service/impl/WmsTransferInventoryServiceImpl.java`（`allocate` / `addToLocation` 同样拦截）
- Modify: `.../service/impl/WmsProductionInboundServiceImpl.java`（上架前拦截）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeFreezeGuardTest.java` + 三个调用点各一条测试

**Interfaces:**
- Produces: `void assertNotFrozen(String locationId)`（该库位 `stocktake_frozen='1'` → 抛 `RuntimeException("库位 X 处于盘点冻结，暂不可操作")`）；`void markRecheck(String stocktakeId, String locationId, String materialId, String batchNo, String containerCode)`——若该盘点单明细里存在匹配行则置 `recheck_flag='1'`，不存在则插入一条 `book_qty` 为快照值的重盘明细。

- [ ] **Step 1: 失败测试**：
  - 冻结库位 → `assertNotFrozen` 抛异常且异常含库位编码；未冻结不抛；
  - 出库分配候选含冻结库位 → `allocate` 抛异常；
  - 移库 `addToLocation` 目标冻结 → 抛异常；
  - 入库上架目标冻结 → 抛异常；
  - `markRecheck` 命中已有明细 → `recheck_flag=1`；未命中 → 插入新明细。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点冻结拦截（出库/移库/上架）与重盘标记`

### Task 6: 库存调整（唯一允许改库存的盘点路径）

**Files:**
- Modify: `.../service/IWmsStocktakeService.java`、`.../service/impl/WmsStocktakeServiceImpl.java`
- Create: `.../service/impl/WmsStocktakeInventoryAdjuster.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeAdjustTest.java`

**Interfaces:**
- Consumes: `WmsInventoryMapper`、Task 2 resolver、Task 4 判定。
- Produces: `void applyAdjustment(String id, String operator)`——按明细 `actual_qty` 覆盖库存行 `quantity`（不存在则插入、为 0 则删除该行），置 `adjusted='1'`、订单 `status=已调整`、`adjust_time=now`；**不写** `WmsInboundOrder`/`WmsOutboundOrder`。

- [ ] **Step 1: 失败测试**：盘盈行（actual > book）→ 库存 `quantity` 变为 actual；盘亏 → 变为 actual；actual=0 → 库存行被删；断言 `inboundService`/`outboundService` **零交互**；状态非 `差异确认` 时调 `applyAdjustment` 抛异常。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点库存调整（不经出入库单据）`

### Task 7: 盘点计划（定期 / 周期滚动）

**Files:**
- Create: `.../service/IWmsStocktakePlanService.java`、`.../service/impl/WmsStocktakePlanServiceImpl.java`
- Create: `.../job/WmsStocktakePlanJob.java`、`.../job/WmsStocktakePlanJobRegistrar.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakePlanTest.java`、`.../job/WmsStocktakePlanJobTest.java`

**Interfaces:**
- Produces:
  - `List<String> resolveCycleLocationIds(String warehouseId, int rollingSize)`——按 `last_run_time` 之后轮转圈定 `rollingSize` 个库位（`location_code` 升序，循环取模，跳过冻结库位）；
  - `void runDuePlans()`——`enabled='1'` 的计划：定期（cron 到期）→ 全仓库位；周期 → `resolveCycleLocationIds`；各自调 Task 3 `create`，更新 `last_run_time`。
  - Job：`@DisallowConcurrentExecution`，执行体吞异常记日志（照 `WmsMasterDataSyncJob`）。

- [ ] **Step 1: 失败测试**：`resolveCycleLocationIds` 对 10 个库位、rollingSize=3 返回 3 个且不重复、跳过冻结库位；`runDuePlans` 对禁用计划零调用、对启用定期计划调 `create`；Job 异常不外抛。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点计划（定期/周期滚动）与定时任务`

### Task 8: 复核闸门

**Files:**
- Modify: `.../service/IWmsStocktakeService.java`、`.../service/impl/WmsStocktakeServiceImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeReviewTest.java`

**Interfaces:**
- Produces（三条转移，各自经 Task 2 resolver 校验）：
  - `void confirmDiff(String id, String operator)`：`盘点中 → 差异确认`；若 Task 4 `needsReview(...)` 为真，则再推进一格到 `复核中`（即一次调用完成 `盘点中→复核中`，经 resolver 逐跳校验）。
  - `void review(String id, String reviewer)`：`复核中 → 差异确认`（复核通过、放行给调整环节）。校验 `reviewer` 不等于订单 `initiator`，不等则抛 `RuntimeException("复核人不能是盘点发起人")`；`wms.stocktake.simulate-review` 非空时跳过该校验。
  - `void archive(String id)`：`已调整 → 已归档`，写 `archive_time`。

- [ ] **Step 1: 失败测试**：阈值内差异 → `confirmDiff` 后 status=`差异确认`；超阈值 → status=`复核中`；`reviewer==initiator` → 抛异常；`simulate-review` 非空 → 不抛且 status 回到 `差异确认`；从 `盘点中` 直接调 `archive` → 抛异常。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点差异确认与复核闸门`

### Task 9: WMS→ERP 差异回写

**Files:**
- Create: `.../service/IWmsStocktakeErpWriter.java`、`.../service/impl/WmsStocktakeErpWriterImpl.java`
- Modify: `.../service/impl/WmsStocktakeServiceImpl.java`（`archive` 前调 writer，失败只记录不阻断）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsStocktakeErpWriterTest.java`

**Interfaces:**
- Consumes: `WmsKingdeeFormClient.save(formId, model)`。
- Produces: `WmsOrderSyncResult writeGain(WmsStocktakeOrder, List<WmsStocktakeDetail>)` → `STK_Miscellaneous`（QTRK）；`WmsOrderSyncResult writeLoss(...)` → `STK_MisDelivery`（QTCK）；均先读配置 `wms.stocktake.stock-code`，为空 → 失败 message 含「未配置」；两侧**独立**调用、独立记 `wms_sync_log`。

- [ ] **Step 1: 失败测试**：配置空 → 两方法均失败且 `formClient` 零交互；配置 `D.01` → 各调 `save` 一次，捕获 model 断言 `FormId`、仓库编码、物料与数量；盘盈成功/盘亏失败时整体不抛（由调用方记录）。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 盘点差异回写金蝶（QTRK/QTCK）`

### Task 10: Controller + 前端页面

**Files:**
- Create: `.../controller/WmsStocktakeController.java`（`/wms/stocktake`：`list`、`queryById`、`create`、`start`、`recordActual`、`confirmDiff`、`review`、`adjust`、`archive`）
- Create: `jeecgboot-vue3/src/views/wms/stocktake/{index.vue,stocktake.api.ts,stocktake.data.ts}`
- Test: `src/test/java/org/jeecg/modules/wms/controller/WmsStocktakeControllerTest.java`

**Interfaces:**
- Consumes: Task 3/6/8 的服务方法。
- Produces: 全部端点返回 `Result<T>`；列表按 `stocktake_type` 过滤，供两个子菜单复用同一页面。

- [ ] **Step 1: 失败测试**：每个端点转发到对应服务方法（照 `WmsTransferControllerTest` 风格，mock 服务）。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: 前端 lint**：`cd jeecgboot-vue3 && npx eslint src/views/wms/stocktake/`，Expected: 0 error
- [ ] **Step 6: Commit** `feat(wms): 盘点端点与前端页面`

### Task 11: 文档

**Files:**
- Create: `docs/wms/库存盘点使用说明.md`

- [ ] **Step 1: 写文档**——盘点流程（建单→清点→确认→复核→调整→归档）、冻结与重盘规则、阈值判定式、两个子模块区别、ERP 回写配置（`wms.stocktake.stock-code`）与 E2 就位后的切换步骤；
  并写明**本期边界**：只支持 WMS 发起盘点；ERP→WMS 下发、AGV 自动盘点均为后续事项（当前项目无 AGV，全部手工操作）。
- [ ] **Step 2: Commit** `docs(wms): 库存盘点使用说明`

---

## 执行注意

- Task 3/6/8/9 完成后 `install -DskipTests` + 重启后端。
- 每个任务后跑模块全量；已知 2 例既有环境失败（`WmsMaterialControllerTest.importExcel_*`）不计入回归判断。
- 前端页面需要菜单权限点：`wms:stocktake:{add,start,review,adjust,archive,import}`，与 Task 1 的菜单 SQL 对齐。
