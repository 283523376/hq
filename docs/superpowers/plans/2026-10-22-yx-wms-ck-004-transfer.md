# 永鑫WMS CK-004 库存调拨 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans，按 Task 逐条执行。步骤用 `- [ ]` 勾选。

**Goal:** 实现 YX-WMS-CK-004 库存调拨：WMS 建单 → 实物执行（同仓走米塔区域搬运 / 跨仓两段式在途）→ 金蝶 `STK_TransferDirect` Save+Submit → **轮询到 `FDocumentStatus=C` 才置「已完成」**。

**Architecture:** 双时钟——`exec_status`（实物）+ `sync_status`（ERP）+ `erp_document_status`（金蝶原值留痕）；单据总状态 `status` 由 `WmsTransferStatusResolver.deriveStatus()` 唯一派生，任何 service 都不得直接写 `status`。实物移动即时更新本地库存，ERP 库存由金蝶工作流审批驱动，二者时间差由 SB-001 兜底。

**Tech Stack:** Spring Boot 4 / MyBatis-Plus / Quartz / Flyway(MySQL) / Vue3 + Vben / 金蝶 WebAPI / 米塔 RMS V1.6。

**Spec:** `docs/superpowers/specs/2026-10-22-yx-wms-ck-004-transfer-design.md`（评审中）
**口径依据:** `docs/superpowers/specs/2026-10-22-yx-wms-ck-004-transfer-interface-analysis.md`（实测）

---

## 分阶段（本计划的核心结构）

| 阶段 | Task | 是否受 spec §2 前置条件阻塞 |
| --- | --- | --- |
| **A 执行段** | Task 1–9 | ❌ 不阻塞，可立即开工（金蝶/RMS 均可 mock） |
| **B 完成判定段** | Task 10–13 | ✅ 阻塞于 P1 审批人/角色、P2 审批时效、P3 驳回责任人、P4 仓管员编码 |

A 段做完即可演示「实物能调、账能对、单据停在待ERP审核」；B 段把「已完成」闭环补上。

### 执行状态（2026-10-22）

| Task | 状态 | 提交 |
| --- | --- | --- |
| 1 迁移 `V3.9.5_21` | ✅ 完成（`success=1`） | `7dcbd083` |
| 2–8 模型/状态机/库存/RMS/跨仓/接口/前端 | ✅ 完成 | `40a90438`、`0495f664` |
| 9 Phase A 全链路验证 | ✅ 完成（722/722 单测 + `pnpm build` + 同仓 E2E + 4 条负向/回归） | 见验收报告 |
| 10–13 Phase B | ⬜ 未开工（待 spec §2 前置条件） | — |

> 验收证据与 3 个执行期缺陷（容器未过滤 / FINISHED 字面值 / 关单漏释放锁定）见
> `docs/superpowers/plans/2026-10-22-yx-wms-ck-004-transfer-验收报告.md`。
> 偏差：Task 6 执行器合并进 `WmsTransferServiceImpl`；`wms_transfer_task` 增 `inventory_id`/`allocated_qty` 两列。

## Global Constraints

- 状态常量一律进 `WmsConstants`，禁止魔法值。新增：`exec_status`（`待执行/执行中/在途/待入库/实物完成/异常`）、`sync_status`（沿用现有 5 个 + `已提交待审核/已审核/ERP驳回`）、`status`（`待执行/执行中/待ERP审核/已完成/异常/已关闭`）、`transfer_mode`（`跨仓/同仓区域`）。
- **总状态只派生不直写**：`status = deriveStatus(exec_status, sync_status)`（spec §5.3 八行表逐条实现）。
- **终态不可回退**：`已完成`/`已关闭` 拒绝任何 `exec_status`/`sync_status` 变更；`已审核` 之后不得再置 `同步失败`；`ERP驳回` 之后不得把 `exec_status` 改回 `待执行`。
- **绝不调用金蝶 `Audit`**：`ZJDB01_SYS` 关联工作流，WebAPI 审批会被拒（spec §0 第 4 条）。
- 所有状态推进走**条件更新**（`WHERE id=? AND <期望旧状态>`），0 行即放弃并 warn，防迟到回执/并发覆盖。
- 米塔只同仓、只到 `zoneCode`；跨仓**不下发** RMS 任务。
- 仓库级调拨：**不传** `FSrcStockLocId/FDestStockLocId`；不需要库位↔仓位映射。
- 告警接收人统一用 `wms.sync.alert.to-user`（注意 `WmsWorkOrderSyncJob` 用的是 `wms.sync.alert-to-user`，属历史不一致，**不要再新增第三种写法**）。
- 接口统一 `Result<T>`；删除/拒绝文案逐字 `Result.error(e.getMessage())`；事务 `@Transactional(rollbackFor = Exception.class)`（这是 WMS 既有硬约定）。
- 构建一律 `bash /d/tools/m.sh -P dev ...`；单测 `-pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=<Class>`，带 `-am` 时必须加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- Flyway 脚本幂等（`IF NOT EXISTS` / 条件 `UPDATE`），脚本头写回滚 SQL；改完迁移后 grep 启动日志「flyway执行sql脚本失败」必须为 0。
- 改完 wms 模块：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests` + **重启后端 JVM**，否则新接口 404。
- 注释、日志、提交信息用中文；提交规范 `feat(wms): …` / `fix(wms): …`，从仓库根提交。

## Review Focus（评审必看）

1. **「已完成」提前出现**：实物完成但 ERP 停在 B 时，`status` 必须是「待ERP审核」而非「已完成」→ Task 3 单测覆盖 §5.3 八种组合。
2. **驳回把单据拖回可执行**：ERP 驳回后 `exec_status` 必须保持「实物完成」→ Task 3 + Task 12 测。
3. **并发双扣/超发**：同一库存行被两张调拨单锁定、回执与 Job 同时推进 → Task 4 + Task 3 条件更新测试。
4. **跨仓在途丢账**：暂存库位未配置时必须**建单报错**，不允许静默落库到别处 → Task 6 测。
5. **同仓不改库位**：`FINISHED` + 位置回调后库存行 `location_id` 必须迁到 `stackCode`，源库位释放 → Task 5 测。
6. **静默 mock**：RMS/金蝶未配置时走 `mocked=true`，但**不得**因此置「已审核」→ Task 5/Task 10 测。

---

## Task 1: Flyway `V3.9.5_21__wms_transfer.sql`（3 表 + 菜单/权限）

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_21__wms_transfer.sql`

**Interfaces:**
- Produces: `wms_transfer_order`（spec §4.1 全字段）、`wms_transfer_detail`（§4.2）、`wms_transfer_task`（§4.3）；
  唯一索引 `uk_wms_transfer_no(transfer_no, deleted_at)`；`idx_wms_transfer_status(status)`、`idx_wms_transfer_erp(erp_document_status, sync_status)`、`idx_wms_transfer_detail(transfer_id)`、`idx_wms_transfer_task_no(task_no)`。
- Produces 菜单：目录「库存管理」 `1910000000000000401`（parent=`1910000000000000001`，sort_no=`93.00`，与入库 91/出库 92 平级）、页面「库存调拨」 `1910000000000000402`（`/wms/transfer`，component=`wms/transfer/index`）。
- Produces 按钮：`…0411~…0419` → `wms:transfer:list/add/dispatch/execute/sync/reverse/close`（8 个，除 execute 外一一对应 spec §9），授权 admin 角色 `f6817f48af4fb3af11b9e8bf182f618b`。
- **Job 种子不在此脚本**（class 还不存在），随 Task 11 的 `V3.9.5_22` 落地。

- [ ] **Step 1: 写脚本**：DDL 逐字照 spec §4.4；`deleted_at bigint NOT NULL DEFAULT 0`、审计列 `create_by/create_time/update_by/update_time`；菜单 INSERT 形状照抄 `V3.9.5_17__wms_outbound.sql`（同段位）；脚本头写回滚 SQL 注释块（3 张 DROP TABLE + 菜单/按钮 DELETE + `sys_role_permission` 清理），并注明「已应用迁移不可改 checksum，回滚段仅供运维手工执行」。
- [ ] **Step 2: 起后端验证**：`cd jeecg-boot/jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run --server.port=8081`；`grep` 启动日志「flyway执行sql脚本失败」计数为 **0**；`SELECT success FROM flyway_schema_history WHERE version='3.9.5.21'` 为 **1**；`"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "SHOW TABLES LIKE 'wms_transfer%'; SELECT id,name,parent_id,sort_no FROM sys_permission WHERE id LIKE '19100000000000004%'"` 抽查。
- [ ] **Step 3: Commit** `feat(wms): CK-004 调拨三表与菜单权限迁移`

## Task 2: 常量、实体、Mapper、DTO/VO

**Files:**
- Modify: `.../wms/constant/WmsConstants.java`（文件末尾追加 TRANSFER 段，带中文注释）
- Create: `.../wms/entity/WmsTransferOrder.java`、`WmsTransferDetail.java`、`WmsTransferTask.java`
- Create: `.../wms/mapper/WmsTransferOrderMapper.java`、`WmsTransferDetailMapper.java`、`WmsTransferTaskMapper.java`
- Create: `.../wms/dto/WmsTransferCreateDTO.java`、`WmsTransferDetailDTO.java`
- Create: `.../wms/vo/WmsTransferDetailVO.java`、`WmsTransferTaskVO.java`

**Interfaces:**
- Produces 常量：`TRANSFER_EXEC_PENDING/RUNNING/IN_TRANSIT/PENDING_INBOUND/COMPLETED/EXCEPTION`、`TRANSFER_SYNC_SUBMITTED("已提交待审核")/SYNC_APPROVED("已审核")/ERP_REJECTED("ERP驳回")`、`TRANSFER_STATUS_*`、`TRANSFER_MODE_CROSS/SAME`、`TRANSFER_SOURCE_WMS/ERP`。
- Produces 实体：字段与 spec §4 一一对应（`extends JeecgEntity`、`@TableName`、`@Accessors(chain=true)`、`deletedAt` 用 `@TableLogic`）。

- [ ] **Step 1**：常量段追加（禁魔法值，注释写清「三个时钟分别是什么」）。
- [ ] **Step 2**：三个实体 + 三个 Mapper（Mapper 不写 `@Mapper`，符合既有约定）。
- [ ] **Step 3**：DTO/VO（入参 `@Schema` 中文说明；VO 带 `taskList`、`waitingHours` 计算字段供前端展示「待ERP审核已等 X 小时」）。
- [ ] **Step 4**：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests` 编译通过。
- [ ] **Step 5: Commit** `feat(wms): CK-004 调拨常量与实体模型`

## Task 3: `WmsTransferStatusResolver`（派生 + 迁移守卫）+ 单测

**Files:**
- Create: `.../wms/service/impl/WmsTransferStatusResolver.java`（`@Component`，静态纯函数）
- Create: `src/test/java/.../wms/service/WmsTransferStatusResolverTest.java`

**Interfaces:**
- Produces: `String deriveStatus(String execStatus, String syncStatus)`、`void assertTransition(String from, String to)`。
- 顺序依赖：Task 4–12 全部用它写库，**此 Task 必须先行且全绿**。

- [ ] **Step 1: 先写测试**（TDD）：§5.3 八行表逐条 → 8 个用例；终态拒绝回退 3 个（已完成、已关闭、已审核后置同步失败）；驳回后拒绝 `exec_status=待执行` 1 个；`sync_status=同步失败` 派生「待ERP审核」1 个；共 **≥13 例**。
- [ ] **Step 2: 实现**：`deriveStatus` 用 `switch` 穷举，未知组合抛 `IllegalStateException`（禁止 default 静默返回）；`assertTransition` 白名单表驱动。
- [ ] **Step 3: 验证**：`bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsTransferStatusResolverTest`（全绿）。
- [ ] **Step 4: Commit** `feat(wms): CK-004 双时钟状态派生与迁移守卫`

## Task 4: 库存执行服务（锁定/扣减/迁库位/暂存）

**Files:**
- Create: `.../wms/service/IWmsTransferInventoryService.java` + `impl/WmsTransferInventoryServiceImpl.java`
- Modify: `.../wms/mapper/xml/WmsInventoryMapper.xml`（如需新增 `selectByContainer`/`moveLocation` 语句；优先用 `WmsInventoryMapper` 已有 `selectAvailable`/`lockInventory`）
- Create: `src/test/java/.../wms/service/WmsTransferInventoryServiceTest.java`

**Interfaces:**
- Produces: `lock(detail, qty)`、`release(detail, qty)`、`deductOutbound(detail, qty)`、`addToTransit(detail, qty, transitLocationId)`、`moveLocation(detail, targetLocationId, qty)`（合并同 material+batch+container 行）。
- 约定：所有更新返回影响行数，0 行抛 `RuntimeException`（文案逐字透出到前端）。

- [ ] **Step 1: 测试先行**：FIFO 取批次、跨行拆分、并发锁定失败回滚、`release` 用 `WHERE locked_qty >= qty` 不清他单锁定、迁库位合并行、暂存 upsert、源库位状态回「空闲」。
- [ ] **Step 2: 实现**（复用 CK-001/002 的 `lockInventory` 条件更新口径，不新造锁）。
- [ ] **Step 3: 验证**：`-Dtest=WmsTransferInventoryServiceTest` 全绿。
- [ ] **Step 4: Commit** `feat(wms): CK-004 调拨库存执行服务`

## Task 5: 同仓区域搬运（RMS 下发 + 回执 + 位置回调）

**Files:**
- Modify: `.../wms/sync/IWmsRmsGateway.java`（新增 `WmsTaskDispatchResult dispatchTransfer(List<WmsRmsTransferTask> tasks)`、`void cancelTransfer(...)`）
- Create: `.../wms/sync/WmsRmsTransferTask.java`
- Modify: `.../wms/sync/HttpWmsRmsGateway.java`（`POST /api/v2/task/transfer`；报文 `zoneCode`/`details[].taskNo/containerCode/priority`；错误码 `300001` 幂等成功、`300007/300013` 转异常、`300000` 退避 3 次）
- Modify: `.../wms/controller/WmsRmsCallbackController.java`（`task-feedback` 未命中入库/出库时按 `task_no` 查 `wms_transfer_task`；`container-location` 额外更新 `wms_transfer_detail.in_location_*`） 
- Create: `src/test/java/.../wms/sync/HttpWmsRmsGatewayTransferTest.java`

**Interfaces:**
- Consumes: Task 2 实体、Task 3 resolver、Task 4 库存服务。
- Produces: 同仓 `dispatch` 成功后 `exec_status=执行中`；`FINISHED` + 位置回调后 `exec_status=实物完成` 并把库存行迁到 `stackCode`。

- [ ] **Step 1**：网关 + 报文单测（字段名、`zoneCode` 必填、错误码四条分支、mock 分支写 `mocked=true` 且**不**推进 `sync_status`）。
- [ ] **Step 2**：回调路由（先查 `wms_inbound` / `wms_outbound_allocation`，未命中再查 `wms_transfer_task`，避免影响既有流程）。
- [ ] **Step 3**：位置回调落 `in_location_id/in_location_code`，事务内完成库存迁库位 + 库位状态。
- [ ] **Step 4**：验证 `-Dtest=HttpWmsRmsGatewayTransferTest` 全绿 + 手工 `curl` mock 回执一次。
- [ ] **Step 5: Commit** `feat(wms): CK-004 同仓区域搬运下发与回执`

## Task 6: 跨仓两段式（出发/到达/上架）

**Files:**
- Create: `.../wms/service/IWmsTransferExecutor.java` + `impl/WmsTransferExecutorImpl.java`（同仓/跨仓共用入口）
- Modify: `.../wms/service/impl/WmsTransferInventoryServiceImpl.java`（接在途三段）

**Interfaces:**
- Produces: `dispatch(orderId)`、`depart(orderId)`、`arrive(orderId)`、`putaway(orderId, locations)`、`close(orderId, reason)`。
- 规则：`enable-transit=false` 时 `arrive` 直接完成三段合并；`transit-location-id` 为空时**建单即报错**；`putaway` 未指定库位时按目标区域空闲库位分配。

- [ ] **Step 1**：单测（两段式状态序列、合并模式、暂存库位缺失报错、上架合并行、关单释放锁定）。
- [ ] **Step 2**：实现，每步走 Task 3 的 `assertTransition` + 条件更新。
- [ ] **Step 3**：验证 `-Dtest=WmsTransferExecutorTest` 全绿。
- [ ] **Step 4: Commit** `feat(wms): CK-004 跨仓在途两段式执行`

## Task 7: Controller 与权限

**Files:**
- Create: `.../wms/controller/WmsTransferController.java`、`.../wms/service/IWmsTransferService.java` + `impl/WmsTransferServiceImpl.java`

**Interfaces:**
- Produces spec §9 的 10 个端点（`list/get/create/dispatch/depart/arrive/putaway/sync-erp/reverse/close`），权限串与 Task 1 按钮一一对应；
  Phase A 阶段 `sync-erp` / `reverse` 先返回 `Result.error("待审批口径确认后开放")`（占位，Task 10/12 替换）。

- [ ] **Step 1**：CRUD + 动作端点（`Result<T>`、`@AutoLog` 记关键动作）。
- [ ] **Step 2**：创建校验：仓库存在、同仓时必须给 `in_zone_code`、跨仓时必须配 `transit-location-id`、明细物料/数量 >0、容器与仓库类型匹配。
- [ ] **Step 3**：接口级验证（登录态 `curl` 或 `ui-check.js` 风格脚本）：建单 → dispatch（mock）→ 回执 → 详情状态。
- [ ] **Step 4: Commit** `feat(wms): CK-004 调拨接口与权限`

## Task 8: 前端 `src/views/wms/transfer/`

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/transfer/index.vue`、`TransferCreateModal.vue`、`TransferDetailDrawer.vue`、`transfer.api.ts`、`transfer.data.ts`

**Interfaces:**
- Consumes: Task 7 端点；列表列按 spec §10（含 ERP 状态小标签、待审核停留时长）；详情抽屉含**双时钟时间轴**。

- [ ] **Step 1**：列表 + 查询 + 状态列（`status` 唯一取值来源，前端不自行组合字段）。
- [ ] **Step 2**：新建弹窗（同仓自动锁区域必填、跨仓显示在途提示）。
- [ ] **Step 3**：详情抽屉（明细/库位/任务/同步日志 + 双时钟时间轴 + 「待ERP审核（已等 X 小时）」tooltip）。
- [ ] **Step 4**：`cd jeecgboot-vue3 && pnpm build` 通过；ESLint 该目录无 error。
- [ ] **Step 5: Commit** `feat(wms): CK-004 调拨单前端页面`

## Task 9: Phase A 全链路验证

- [ ] **Step 1**：同仓：建单（D.01→D.02）→ dispatch（RMS 未配 → `mocked=true`）→ 模拟 `FINISHED` + `container-location` → 断言 `exec_status=实物完成`、`status=待ERP审核`、库存行库位已迁、源库位空闲。
- [ ] **Step 2**：跨仓：未配暂存库位时建单报错；配置后 depart→arrive→putaway 三段，断言暂存行与目标行数量、`status=待ERP审核`。
- [ ] **Step 3**：并发/异常：两张单抢同一库存行（第二次锁定失败回滚）、关单释放锁定不清他单、`300007` 转异常并告警。
- [ ] **Step 4**：`bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms`（全量单测）+ `pnpm build`；写 Phase A 验收小结（放 `plans/2026-10-2x-yx-wms-ck-004-验收报告.md` 的「Phase A」段）。
- [ ] **Step 5: Commit** `docs(wms): CK-004 Phase A 验收小结（执行段）`

---

## 以下 Task 受 spec §2 前置条件阻塞（P1–P4 确认后启动）

## Task 10: 金蝶 Writer（Save + Submit + 状态查询）

**Files:**
- Create: `.../wms/sync/IWmsKingdeeTransferWriter.java` + `impl/WmsKingdeeTransferWriterImpl.java`
- Modify: `.../wms/sync/WmsKingdeeProperties.java`（`transferBillType/transferBizType/transferDirect/stockerNumber/transferApprovalTimeoutHours`）
- Modify: `application-dev.yml`（按 spec §11 增加配置块，凭据仍走环境变量）
- Create: `src/test/java/.../wms/sync/WmsKingdeeTransferWriterTest.java`

**Interfaces:**
- Produces: `saveAndSubmit(order, details)`（表头 9 必填 + 明细 11 必填、**不传仓位**、`FBillNo=transferNo`、Submit 后 `sync_status=已提交待审核`）、`queryStatus(billNo)` 返回 `A/B/C/D`。
- 幂等：单号重复 → 转为查状态；**不调用 `Audit`**。

- [ ] **Step 1**：报文快照单测（表头/明细字段逐字比对 spec §2.5）+ 幂等分支 + mock 分支（`mocked=true` 仍置「已提交待审核」）。
- [ ] **Step 2**：实现 + 同步日志（`objectType=TRANSFER`、失败退避 3 次、连续失败 3 次告警）。
- [ ] **Step 3**：真实环境手工验一次 `Save+Submit`（101，D.01→D.02，`0050` 仓管员），确认返回 B。
- [ ] **Step 4: Commit** `feat(wms): CK-004 金蝶直接调拨单回写`

## Task 11: `WmsTransferSyncJob` + Registrar + `V3.9.5_22`

**Files:**
- Create: `.../wms/job/WmsTransferSyncJob.java`、`WmsTransferSyncJobRegistrar.java`
- Create: `flyway/.../V3.9.5_22__wms_transfer_sync_job.sql`（`sys_quartz_job` id=`1920000000000000350`、cron=`0 23/10 * * * ? *`、class=`org.jeecg.modules.wms.job.WmsTransferSyncJob`，INSERT 形状照抄 `V3.9.5_13:132-133`）

**Interfaces:**
- Consumes: Task 10 `queryStatus`。
- Produces 三段执行：① 状态回读（B→不动、C→`已审核`+`已完成`+两个时间锚点、D→`ERP驳回`+`异常`、A→退回`待同步`）；② 审批超时告警（`approval_alert_time` 幂等）；③ 失败重试（`retry_count<3` 退避）。

- [ ] **Step 1**：单测（C/D/A/B 四分支、超时只告警一次、重试次数上限、并发条件更新 0 行不重复推进）。
- [ ] **Step 2**：实现 + Registrar（重启后仍注册，沿用既有 Registrar 模式）。
- [ ] **Step 3**：验证：迁移 success=1、Job 注册、`timeout=0` 造超时告警一次。
- [ ] **Step 4: Commit** `feat(wms): CK-004 调拨ERP状态回读与审批超时告警`

## Task 12: 驳回处置（反向调拨 / 人工关闭）

**Files:**
- Modify: `WmsTransferServiceImpl`（`reverse` / `close`）、`WmsTransferController`（去掉 Task 7 的占位返回）

**Interfaces:**
- Produces: `reverse(id)` → 生成源/目标互换的反向单（`reverse_transfer_no` 回填原单、`reject_handle_status=已冲销`）；`close(id, reason)` → `已关闭`、`reject_handle_status=已人工关闭`；**不修改原单 `exec_status`**。

- [ ] **Step 1**：单测（reverse 字段互换与单号关联、close 必填理由、驳回态下禁止改回待执行）。
- [ ] **Step 2**：实现 + 告警（`WMS调拨ERP驳回告警` → `wms.sync.alert.to-user`）。
- [ ] **Step 3: Commit** `feat(wms): CK-004 驳回处置与反向调拨`

## Task 13: Phase B 验收（真实金蝶 + mock RMS）

- [ ] **Step 1**：真实金蝶跑通「Save→B→金蝶 UI 审批→C→Job 置已完成」，记录 `submit_time/exec_finish_time/erp_approve_time` 三个锚点与审批延迟。
- [ ] **Step 2**：驳回路径：造一张被驳回/作废的单，验证 `status=异常`、告警、`reverse` 生成反向单。
- [ ] **Step 3**：清理测试单据（含 `WMS-TEST-TR-20261022-02` 的处置说明）。
- [ ] **Step 4**：写完整验收报告 `plans/2026-10-2x-yx-wms-ck-004-验收报告.md`（含 Phase A 段 + Phase B 段、遗留、回滚）。
- [ ] **Step 5: Commit** `docs(wms): CK-004 验收报告`

---

## 验收放行条件（全部满足才算完成）

- [ ] spec §2 的 P1–P3 已确认并回填 spec（审批人/角色、时效 24h、驳回责任人）
- [ ] `status=已完成` **只出现**在 `exec_status=实物完成 && sync_status=已审核`
- [ ] B 状态单据 100% 出现在「待ERP审核」，且列表能显示已等待时长
- [ ] 驳回场景有告警 + 责任人 + 处置记录（reverse 或 close）
- [ ] 后端全量单测通过、`pnpm build` 通过、`flyway_schema_history` 两条迁移 `success=1`、启动日志无「flyway执行sql脚本失败」
- [ ] 回滚段（3 表 + 菜单/按钮/Job）写在脚本头注释里

## Out of scope（本期不做，防蔓延）

1. ERP→WMS 调拨单拉取（Phase 2，与 SB-001 对账一起做）
2. `STK_TransferApply` 调拨申请单
3. 跨组织调拨（100↔101，`FTransferBizType` 未实测）
4. WMS 库位 ↔ 金蝶仓位内码映射（101 不启用仓位）
5. 设备任务监控页（SB-002）
6. 库存对账/差异清单（SB-001）
