# 生产入库流程（YX-WMS-RK-002）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在永鑫WMS 中交付半成品（立库/堆垛机/RFID）与成品（敏捷仓/SCR/RMS/二维码）两条生产入库链路，含工单扫码、数量校验、废品仓、任务下发与回执、库存与载具绑定、ERP/MES/WMS 同步重试、任务跟踪页面。

**Architecture:** 新建 `wms_work_order` 与 `wms_production_inbound` 两张表；一次提交对应一个载具（料箱/周转盘）。提交在单事务内完成校验、库位占用、废品入库与主表落库，事务提交后再通过可配置网关下发 WCS/RMS；设备回执走幂等条件认领，完成时才写良品库存。同步复用 RK-001 的「网关 + 重试 + 乐观认领 + Quartz 补偿」范式。

**Tech Stack:** JeecgBoot 3.9.5 / Spring Boot 4.1.0 / Java 17 / MyBatis-Plus / Shiro+JWT / Flyway / Quartz / Vue 3 + Ant Design Vue 4 + BasicTable。

**Spec:** `docs/superpowers/specs/2026-10-19-yx-wms-rk-002-production-inbound-design.md`

## Global Constraints

- 只改 `D:\yongxin-WMS\JeecgBoot`；后端改动后必须 `install` wms 模块并**重启后端 JVM**。
- 所有业务表 `wms_` 前缀；状态值用中文常量，全部放 `WmsConstants`，禁止魔法值。
- 接口统一 `Result<T>`；Controller 内 `try/catch → Result.error(e.getMessage())`，文案逐字透出。
- 逻辑删除：`Long deletedAt` + `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")`；查询依赖自动追加 `deleted_at = 0`，不手写。
- Mapper XML 放 `src/main/java/org/jeecg/modules/wms/mapper/xml/`；Mapper 接口不加 `@Mapper`。
- 数量/库存一律 `BigDecimal`，比较用 `compareTo`，禁止 `equals`。
- 外部 HTTP 调用**不得**放在 `@Transactional` 方法内；失败只落库 + 告警，不向上抛。
- 来源系统接口用方法级 `@IgnoreAuth` + 令牌头校验；普通业务接口不加 `@IgnoreAuth`。
- Flyway 版本 `V3.9.5_13`，脚本幂等；迁移失败不会阻断启动，必须 grep 日志验证。
- 前端遵循 `jeecgboot-vue3` 约定：API 文件与页面同目录，`a-*` 组件免 import，`BasicTable` 需显式 import。
- 提交信息用 `feat(wms): …` / `fix(wms): …`，在 `JeecgBoot` 仓库根目录提交。
- **提交需用户授权**：计划中的 `git commit` 步骤在用户明确同意前不执行；未获授权时把改动留在工作区并在交付说明中列出待提交文件。

## Review Focus

1. **同一载具重复提交 / 重复回执**：同一载具码只允许一条未完结记录；重复 FINISHED 不得让库存翻倍。
2. **计划量边界与并发**：`good+bad` 恰好等于剩余量应通过，超出 0.001 应拒绝；两条并发提交不得同时越过计划量。
3. **下发超时但设备实际受理**：FINISHED 必须能从「异常待人工」收敛；重复下发不得产生第二个任务号或重复库存。
4. **事务与外部调用的边界**：下发失败不得回滚已落库的入库记录和不良品库存；JVM 在提交与下发之间崩溃后，`未下发` 记录必须能被补偿重新下发。
5. **关单/转人工后的资源释放**：关单必须释放半成品立库库位、不得写良品库存；已入库记录不得关单；关单后载具可复用。

---

## 文件结构

**后端（`jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/`）**

| 文件 | 职责 |
|---|---|
| `entity/WmsWorkOrder.java` | 生产工单 |
| `entity/WmsProductionInbound.java` | 生产入库记录（载具、任务、同步） |
| `mapper/WmsWorkOrderMapper.java` + `mapper/xml/WmsWorkOrderMapper.xml` | 工单锁行、累计入库量 |
| `mapper/WmsProductionInboundMapper.java` + `mapper/xml/WmsProductionInboundMapper.xml` | 回执认领、关单、同步候选、未下发扫描、载具占用查询 |
| `dto/WmsWorkOrderPushDTO.java`、`dto/WmsProductionSubmitDTO.java`、`dto/WmsProductionManualCompleteDTO.java`、`dto/WmsProductionCloseDTO.java`、`dto/WmsTaskFeedbackDTO.java`、`dto/WmsContainerLocationDTO.java` | 入参 |
| `vo/WmsWorkOrderInfoVO.java` | 扫码结果（工单 + 已入库 + 剩余） |
| `service/IWmsWorkOrderService.java` + `impl/WmsWorkOrderServiceImpl.java` | 工单 CRUD、推送、扫码查询、删除守卫 |
| `service/IWmsProductionInboundService.java` + `impl/WmsProductionInboundServiceImpl.java` | 提交、回执、转人工、关单、列表/详情、同步支撑 |
| `service/IWmsProductionTaskService.java` + `impl/WmsProductionTaskServiceImpl.java` | 设备检查、下发、取消（事务外） |
| `service/IWmsProductionSyncService.java` + `impl/WmsProductionSyncServiceImpl.java` | 三方同步重试编排与告警 |
| `sync/IWmsRmsGateway.java`、`sync/HttpWmsRmsGateway.java` | RMS 入库下发/取消 |
| `sync/IWmsWcsGateway.java`、`sync/HttpWmsWcsGateway.java` | WCS 入库下发/取消（占位） |
| `sync/IWmsProductionInboundSyncGateway.java`、`sync/HttpWmsProductionInboundSyncGateway.java` | ERP/MES/WMS 同步 |
| `sync/WmsTaskDispatchResult.java` | 下发结果（success/mocked/errorCode/errorMessage/retryable） |
| `job/WmsProductionSyncRetryJob.java` + `job/WmsProductionSyncJobRegistrar.java` | 同步补偿 + 未下发补偿 |
| `controller/WmsProductionController.java`、`controller/WmsWorkOrderController.java`、`controller/WmsRmsCallbackController.java`、`controller/WmsWcsCallbackController.java` | 对外接口 |

**前端（`jeecgboot-vue3/src/views/wms/`）**

| 文件 | 职责 |
|---|---|
| `production/index.vue` | 扫码作业台 + 入库任务列表 + 详情抽屉 |
| `production/production.api.ts`、`production/production.data.ts` | 接口与列定义 |
| `workOrder/index.vue`、`workOrder/components/WorkOrderModal.vue`、`workOrder/workOrder.api.ts`、`workOrder/workOrder.data.ts` | 工单维护 |

**迁移与配置**

- `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_13__wms_production_inbound.sql`
- `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`（追加 `wms.sync.*`）

---

### Task 1: Flyway 迁移与配置占位

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_13__wms_production_inbound.sql`
- Modify: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`（文件末尾追加）

**Interfaces:**
- Produces: 表 `wms_work_order`、`wms_production_inbound`；库区 `FPC01`、库位 `FPC-DEFAULT`/`MJ-DEFAULT`；菜单与权限码 `wms:work-order:*`、`wms:production:*`；Quartz 任务 id `1920000000000000230`。

- [ ] **Step 1: 写迁移脚本**

建表 DDL 必须逐列实现 spec §6.1/§6.2，关键列如下（其余审计列与 `deleted_at` 照 `wms_inbound_order` 写法）：

```sql
CREATE TABLE IF NOT EXISTS `wms_work_order` (
  `id` varchar(32) NOT NULL,
  `work_order_no` varchar(64) NOT NULL COMMENT '工单号',
  `material_id` varchar(32) NOT NULL,
  `material_code` varchar(64) DEFAULT NULL,
  `material_name` varchar(100) DEFAULT NULL,
  `product_type` varchar(10) NOT NULL COMMENT '半成品/成品',
  `plan_qty` decimal(12,3) NOT NULL,
  `batch_no` varchar(64) NOT NULL,
  `work_order_status` varchar(20) NOT NULL DEFAULT '生产中' COMMENT '待生产/生产中/已完成/已关闭',
  `source` varchar(20) DEFAULT 'MES' COMMENT 'MES/手工',
  `mes_order_no` varchar(64) DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `deleted_at` bigint NOT NULL DEFAULT 0,
  `create_by` varchar(50), `create_time` datetime, `update_by` varchar(50), `update_time` datetime,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_wms_work_order_no` (`work_order_no`, `deleted_at`),
  INDEX `idx_wms_work_order_material` (`material_id`),
  INDEX `idx_wms_work_order_status` (`work_order_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='生产工单';

CREATE TABLE IF NOT EXISTS `wms_production_inbound` (
  `id` varchar(32) NOT NULL,
  `inbound_no` varchar(64) NOT NULL COMMENT 'SC前缀',
  `work_order_no` varchar(64) NOT NULL,
  `product_type` varchar(10) NOT NULL,
  `material_id` varchar(32) NOT NULL,
  `material_code` varchar(64), `material_name` varchar(100),
  `batch_no` varchar(64) NOT NULL,
  `quantity` decimal(12,3) NOT NULL,
  `good_qty` decimal(12,3) NOT NULL DEFAULT 0,
  `bad_qty` decimal(12,3) NOT NULL DEFAULT 0,
  `status` varchar(20) NOT NULL DEFAULT '待上架' COMMENT '待上架/执行中/已入库/异常待人工/已关闭',
  `carrier_code` varchar(64) NOT NULL COMMENT '成品料箱码/半成品周转盘RFID',
  `ident_type` varchar(10) NOT NULL COMMENT '二维码/RFID',
  `location_id` varchar(32) DEFAULT NULL,
  `scrap_location_id` varchar(32) DEFAULT NULL,
  `stack_code` varchar(64) DEFAULT NULL,
  `wcs_task_no` varchar(64) DEFAULT NULL,
  `task_status` varchar(20) NOT NULL DEFAULT '未下发' COMMENT '未下发/已受理/执行中/已完成/失败/已关闭',
  `task_error` varchar(500),
  `defect_remark` varchar(500),
  `close_reason` varchar(500), `close_by` varchar(50), `close_time` datetime,
  `sync_status` varchar(20) NOT NULL DEFAULT '无需同步',
  `retry_count` int NOT NULL DEFAULT 0,
  `last_sync_error` varchar(500), `last_sync_time` datetime,
  `operator` varchar(50), `submit_time` datetime, `complete_time` datetime,
  `deleted_at` bigint NOT NULL DEFAULT 0,
  `create_by` varchar(50), `create_time` datetime, `update_by` varchar(50), `update_time` datetime,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_wms_production_inbound_no` (`inbound_no`, `deleted_at`),
  INDEX `idx_wms_prod_inbound_wo` (`work_order_no`),
  INDEX `idx_wms_prod_inbound_carrier` (`carrier_code`),
  INDEX `idx_wms_prod_inbound_status` (`status`),
  INDEX `idx_wms_prod_inbound_task` (`task_status`),
  INDEX `idx_wms_prod_inbound_sync` (`sync_status`, `retry_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='生产入库';
```

种子与权限（全部 `INSERT IGNORE`）：

- 废品仓：`wms_warehouse(id='2110885981815907400', warehouse_code='FPC01', warehouse_name='废品仓', warehouse_type='废品仓')`；库位 `FPC-DEFAULT`（id `2110885981815907401`，row/col/layer=1/1/1，status=占用）。
- 敏捷仓默认位：`MJ-DEFAULT`（id `2110885981815907402`，warehouse_id=`2110885982046593025`，row/col/layer=1/1/1，status=占用）。
- 菜单：`1910000000000000201`「生产工单」`/wms/work-order` → `wms/workOrder/index`，sort 2；`1910000000000000202`「生产入库」`/wms/production/inbound` → `wms/production/index`，sort 3；两者 `parent_id=1910000000000000101`。
- 按钮：`1910000000000000211..213`（`wms:work-order:add/edit/delete`，挂在 201 下）；`1910000000000000221..226`（`wms:production:scan/submit/dispatch/manual/close/sync`，挂在 202 下）。
- admin 授权：`1920000000000000201`~`1920000000000000203`、`1920000000000000211`~`1920000000000000226` 对应上述权限。
- Quartz 种子：`1920000000000000230`，类 `org.jeecg.modules.wms.job.WmsProductionSyncRetryJob`，cron `0 13/5 * * * ? *`。
- 验收种子工单：`WO-DEMO-SEMI`（半成品，物料 `PBC001`，计划 100，批次 `B20261019A`）；物料 `FG001`（成品电路板，分类「电路板」）+ `WO-DEMO-FG`（成品，计划 50，批次 `B20261019B`）。

- [ ] **Step 2: 追加配置占位**

在 `application-dev.yml` 末尾追加 spec §10 的完整 `wms:` 块，开发令牌固定为 `dev-mes-token` / `dev-rms-token` / `dev-wcs-token`，其余地址留空并保留中文注释。

- [ ] **Step 3: 复制资源并重启后端**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev clean process-resources
# 停掉旧 JVM（找出 8081 的 PID 后 taskkill //F //PID <pid>）
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

- [ ] **Step 4: 验证迁移**

```bash
grep -c "flyway执行sql脚本失败" <启动日志>        # 期望 0
```

```sql
SELECT version, success FROM flyway_schema_history WHERE version='3.9.5.13';
SHOW TABLES LIKE 'wms_production%';
SHOW TABLES LIKE 'wms_work_order';
SELECT warehouse_code, warehouse_type FROM wms_warehouse WHERE deleted_at=0 AND warehouse_type='废品仓';
SELECT COUNT(*) FROM wms_production_inbound;
```

Expected: `success=1`；两张表存在；废品仓 1 行；`FPC-DEFAULT` 与 `MJ-DEFAULT` 各 1 行。

- [ ] **Step 5: Commit**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_13__wms_production_inbound.sql jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml
git commit -m "feat(wms): 新增生产入库表、废品仓种子与菜单权限"
```

---

### Task 2: 常量、实体、Mapper 与 SQL

**Files:**
- Modify: `.../wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`（末尾追加「生产入库域」段）
- Create: `entity/WmsWorkOrder.java`、`entity/WmsProductionInbound.java`
- Create: `mapper/WmsWorkOrderMapper.java`、`mapper/WmsProductionInboundMapper.java`
- Create: `mapper/xml/WmsWorkOrderMapper.xml`、`mapper/xml/WmsProductionInboundMapper.xml`
- Test: `src/test/java/org/jeecg/modules/wms/entity/WmsProductionInboundEntityMappingTest.java`

**Interfaces:**
- Produces：常量 `PRODUCT_TYPE_SEMI="半成品"`、`PRODUCT_TYPE_FINISHED="成品"`、`IDENT_TYPE_RFID="RFID"`、`IDENT_TYPE_QRCODE="二维码"`、`PROD_STATUS_PENDING_PUTAWAY/EXECUTING/INBOUND/EXCEPTION/CLOSED`、`TASK_STATUS_*`、`WORK_ORDER_STATUS_*`、`PRODUCTION_SYNC_JOB_ID/CRON`、`UNDISPATCHED_TIMEOUT_MINUTES=2`、`PRODUCTION_SYNC_ALERT_TITLE`。
- Mapper 方法：
  - `WmsWorkOrder selectByNoForUpdate(@Param("workOrderNo") String workOrderNo)`
  - `BigDecimal sumInboundedQty(@Param("workOrderNo") String workOrderNo)`
  - `WmsProductionInbound selectActiveByCarrier(@Param("carrierCode") String carrierCode)`
  - `int claimFinished(@Param("taskNo") String taskNo, @Param("stackCode") String stackCode)`
  - `List<WmsProductionInbound> selectSyncCandidates(@Param("staleBefore") Date staleBefore, @Param("limit") int limit)`
  - `int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore)`
  - `List<WmsProductionInbound> selectUndispatchedBefore(@Param("before") Date before, @Param("limit") int limit)`

- [ ] **Step 1: 写实体映射测试**

```java
@Test
void tableAndLogicDeleteAnnotations() {
    assertEquals("wms_production_inbound", WmsProductionInbound.class.getAnnotation(TableName.class).value());
    assertEquals("wms_work_order", WmsWorkOrder.class.getAnnotation(TableName.class).value());
    Field deletedAt = WmsProductionInbound.class.getDeclaredField("deletedAt");
    TableLogic logic = deletedAt.getAnnotation(TableLogic.class);
    assertEquals("0", logic.value());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsProductionInboundEntityMappingTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现常量、实体、Mapper 与 XML**

实体照 `WmsInboundOrder` 的注解组合；字段名用 lowerCamelCase 对应下划线列。XML 的关键 SQL：

```xml
<!-- 认领入库完成：允许从 待上架/执行中/异常待人工 收敛，重复回执影响 0 行 -->
<update id="claimFinished">
  UPDATE wms_production_inbound
     SET status='已入库', task_status='已完成', stack_code=#{stackCode},
         complete_time=NOW(), sync_status='待同步', update_time=NOW()
   WHERE wcs_task_no=#{taskNo} AND deleted_at=0
     AND status IN ('待上架','执行中','异常待人工')
</update>

<select id="selectActiveByCarrier" resultType="org.jeecg.modules.wms.entity.WmsProductionInbound">
  SELECT * FROM wms_production_inbound
   WHERE carrier_code=#{carrierCode} AND deleted_at=0
     AND status IN ('待上架','执行中','异常待人工') LIMIT 1
</select>

<select id="selectUndispatchedBefore" resultType="org.jeecg.modules.wms.entity.WmsProductionInbound">
  SELECT * FROM wms_production_inbound
   WHERE deleted_at=0 AND status='待上架' AND task_status='未下发'
     AND submit_time &lt; #{before}
   ORDER BY submit_time ASC LIMIT #{limit}
</select>
```

`claimForSync` / `selectSyncCandidates` 照 `WmsInboundOrderMapper.xml`，条件改为 `status='已入库'` 且 `sync_status IN ('待同步','同步失败') OR (同步中且 last_sync_time 僵死)`。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms
git commit -m "feat(wms): 生产入库实体、常量与 Mapper"
```

---

### Task 3: 生产工单维护 + 扫码查询 + MES 推送

**Files:**
- Create: `dto/WmsWorkOrderPushDTO.java`、`vo/WmsWorkOrderInfoVO.java`
- Create: `service/IWmsWorkOrderService.java`、`service/impl/WmsWorkOrderServiceImpl.java`
- Create: `controller/WmsWorkOrderController.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsWorkOrderServiceImplTest.java`

**Interfaces:**
- Consumes: `WmsWorkOrderMapper`、`WmsMaterialMapper`、`WmsProductionInboundMapper`。
- Produces:
  - `WmsWorkOrder getByWorkOrderNo(String workOrderNo)`（不存在返回 null）
  - `WmsWorkOrderInfoVO scan(String workOrderNo)`（不存在抛 `RuntimeException("工单号无效")`）
  - `WmsWorkOrder push(WmsWorkOrderPushDTO dto)`
  - `void addWorkOrder(WmsWorkOrder order)` / `void editWorkOrder(WmsWorkOrder order)` / `void removeWorkOrder(String id)`
  - `IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo, String productType, String workOrderStatus)`

- [ ] **Step 1: 写失败测试**

```java
@Test
void scan_missingWorkOrder_throwsInvalidMessage() {
    when(mapper.selectOne(any())).thenReturn(null);
    RuntimeException e = assertThrows(RuntimeException.class, () -> service.scan("NOPE"));
    assertEquals("工单号无效", e.getMessage());
}

@Test
void scan_returnsRemainingQty() {
    WmsWorkOrder wo = new WmsWorkOrder().setWorkOrderNo("WO1").setPlanQty(new BigDecimal("100"));
    when(mapper.selectOne(any())).thenReturn(wo);
    when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal("30"));
    assertEquals(0, service.scan("WO1").getRemainingQty().compareTo(new BigDecimal("70")));
}

@Test
void remove_referencedWorkOrder_isRejected() {
    when(mapper.selectCount(any())).thenReturn(2L); // 入库记录引用数
    RuntimeException e = assertThrows(RuntimeException.class, () -> service.removeWorkOrder("id1"));
    assertTrue(e.getMessage().contains("已被生产入库引用"));
}
```

- [ ] **Step 2: 跑测试确认失败**（同 Task 2 命令，类名替换）

- [ ] **Step 3: 实现服务与控制器**

校验：工单号/物料/产品类型/计划量/批次非空；`productType ∈ {半成品, 成品}`；`planQty > 0`；物料必须存在。推送按 `workOrderNo` upsert（存在则更新，不存在则新增，`source='MES'`）。删除守卫：`wms_production_inbound` 中存在同 `work_order_no` 且未删除的行 → `该工单已被生产入库引用，不可删除`。

**MES 查询兜底**：`scan` 在本地查不到且 `${wms.sync.mes.work-order-url:}` 非空时，用 `RestTemplate` GET 该地址（参数 `workOrderNo`，配置了 `username/password` 则加 Basic Auth），响应 `{code:0,data:{...}}`；`code=0` 则把 `data` 落成 `wms_work_order`（`source='MES'`）后返回；调用异常或 `code!=0` 一律按 `工单号无效` 处理并记录 warn 日志。网关逻辑放 `WmsWorkOrderServiceImpl` 私有方法，地址为空时直接跳过。

控制器端点（全部 `Result<T>`）：
`GET /wms/work-order/list`、`POST /add`、`PUT /edit`、`DELETE /{id}`、`POST /push`（`@IgnoreAuth`，头 `X-MES-Token` 与 `${wms.sync.mes.push-token:}` 比对，不符 `Result.error("MES 推送令牌无效")`）。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): 生产工单维护、扫码查询与 MES 推送接口"
```

---

### Task 4: 生产入库提交（校验、废品库存、库位占用）

**Files:**
- Create: `dto/WmsProductionSubmitDTO.java`
- Create: `service/IWmsProductionInboundService.java`、`service/impl/WmsProductionInboundServiceImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsProductionInboundServiceImplTest.java`

**Interfaces:**
- Consumes: `WmsWorkOrderMapper.selectByNoForUpdate/sumInboundedQty`、`WmsProductionInboundMapper.selectActiveByCarrier`、`WmsLocationMapper`、`WmsInventoryMapper.upsertInventory`、`WmsWarehouseMapper`。
- Produces: `WmsProductionInbound submit(WmsProductionSubmitDTO dto)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void submit_exceedsPlan_throwsExactMessage() {
    WmsWorkOrder wo = wo("WO1", "半成品", "100");
    when(workOrderMapper.selectByNoForUpdate("WO1")).thenReturn(wo);
    when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal("95"));
    WmsProductionSubmitDTO dto = dto("WO1", "4", "2", "RFID-1");
    RuntimeException e = assertThrows(RuntimeException.class, () -> service.submit(dto));
    assertEquals("数量超出工单范围", e.getMessage());
}

@Test
void submit_exactRemaining_isAccepted() {
    when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal("95"));
    service.submit(dto("WO1", "4", "1", "RFID-1"));
    verify(inboundMapper).insert(argThat(r -> r.getQuantity().compareTo(new BigDecimal("5")) == 0));
}

@Test
void submit_activeCarrier_isRejected() {
    when(inboundMapper.selectActiveByCarrier("RFID-1")).thenReturn(new WmsProductionInbound());
    RuntimeException e = assertThrows(RuntimeException.class, () -> service.submit(dto("WO1", "1", "0", "RFID-1")));
    assertEquals("载具正在使用中", e.getMessage());
}

@Test
void submit_badQty_writesScrapInventory() {
    service.submit(dto("WO1", "8", "2", "RFID-1"));
    verify(inventoryMapper).upsertInventory(argThat(inv ->
        inv.getLocationId().equals("SCRAP-LOC") && inv.getQuantity().compareTo(new BigDecimal("2")) == 0));
}

@Test
void submit_semiFinished_locksStereoLocation() {
    service.submit(dto("WO1", "8", "0", "RFID-1"));
    verify(locationMapper).update(isNull(), argThat(w -> ((UpdateWrapper<?>) w).getSqlSet().contains("status")));
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现 `submit`**

顺序严格照 spec §7.1.1。要点：

- `@Transactional(rollbackFor = Exception.class)`；工单行 `selectByNoForUpdate` 防并发超量。
- 工单状态必须是 `生产中` 或 `已完成`，否则 `工单状态不允许入库`。
- `carrierCode` 必填；`identType` 由 `productType` 推导（半成品→RFID，成品→二维码）。
- 半成品：查 `warehouse_type='立库'` 的空闲库位（`locationService.listFreeByWarehouseType`），逐个条件更新 `status='空闲' → '占用'`，全部失败则 `立库库位不足，已告警` + 告警。
- 成品：取 `warehouse_type='敏捷仓'` 的库区下 `location_code='MJ-DEFAULT'` 的库位；缺失 → `敏捷仓未配置，请联系管理员`。
- 不良品：`upsertInventory(materialId, scrapLocationId, batchNo, badQty)`；废品仓默认位按 `warehouse_type='废品仓'` + `location_code='FPC-DEFAULT'` 查。
- 主表：`status=待上架, task_status=未下发, sync_status=无需同步, operator=当前登录人, submitTime=now`；`inbound_no` 用 `SC+yyyyMMddHHmmss+4位序号`，`wcs_task_no` 用 `产品前缀+yyyyMMddHHmmss+4位序号`（成品 `RMS`，半成品 `WCS`）。
- 良品为 0：`status=已入库, task_status=已完成, sync_status=待同步, complete_time=now`。
- 载具占用校验只查 `待上架/执行中/异常待人工`。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): 生产入库提交校验、废品入库与库位占用"
```

---

### Task 5: WCS/RMS 网关与任务下发

**Files:**
- Create: `sync/WmsTaskDispatchResult.java`、`sync/IWmsRmsGateway.java`、`sync/HttpWmsRmsGateway.java`、`sync/IWmsWcsGateway.java`、`sync/HttpWmsWcsGateway.java`
- Create: `service/IWmsProductionTaskService.java`、`service/impl/WmsProductionTaskServiceImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsProductionTaskServiceImplTest.java`

**Interfaces:**
- Produces:
  - `WmsTaskDispatchResult dispatchInbound(String inboundId)`
  - `void cancelInbound(String inboundId)`（尽力而为，不抛异常）
  - `WmsTaskDispatchResult` 静态工厂：`ok()`、`okMock()`、`fail(Integer code, String message, boolean retryable)`；字段 `success/mocked/errorCode/errorMessage/retryable`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void dispatch_finishedProduct_usesRmsAndCarrierCode() {
    WmsProductionInbound r = record("成品", "待上架", "未下发");
    when(rms.dispatchInbound(r.getWcsTaskNo(), r.getCarrierCode()))
        .thenReturn(WmsTaskDispatchResult.ok());
    taskService.dispatchInbound(r.getId());
    verify(rms).dispatchInbound(r.getWcsTaskNo(), r.getCarrierCode());
    verify(inboundMapper).updateById(argThat(x -> "已受理".equals(x.getTaskStatus())));
}

@Test
void dispatch_deviceFault_movesToManualWithAlert() {
    when(deviceMapper.selectList(any())).thenReturn(List.of(device("ST-01", "故障")));
    taskService.dispatchInbound("id1");
    verify(inboundMapper).updateById(argThat(x ->
        "异常待人工".equals(x.getStatus()) && x.getTaskError().contains("ST-01")));
    verify(sysBaseAPI).sendSysAnnouncement(any());
}

@Test
void dispatch_rms300001_treatedAsAccepted() {
    when(rms.dispatchInbound(any(), any()))
        .thenReturn(WmsTaskDispatchResult.fail(300001, "任务号已存在", false));
    taskService.dispatchInbound("id1");
    verify(inboundMapper).updateById(argThat(x -> "已受理".equals(x.getTaskStatus())));
}

@Test
void dispatch_rms300006_goesManual() {
    when(rms.dispatchInbound(any(), any()))
        .thenReturn(WmsTaskDispatchResult.fail(300006, "容器编码已存在", false));
    taskService.dispatchInbound("id1");
    verify(inboundMapper).updateById(argThat(x -> "异常待人工".equals(x.getStatus())));
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现网关与任务服务**

`HttpWmsRmsGateway`：读 `wms.sync.rms.*`，`RestTemplate` 连接/读取超时均取 `timeout-ms`（默认 60000）；URL 为空 → `okMock()`。下发 URL `{url}/api/v2/task/inbound`，body 照 spec §7.2；`code=0` 成功，否则 `fail(code,msg)`，`300000` 标记 `retryable`。取消 URL `{url}/api/v2/task/cancel`，body `{type:"INBOUND",details:[{containerCode,taskNo}]}`。

`HttpWmsWcsGateway`：URL 为空 → mock；body 照 spec §7.2（`carrierCode`、`locationCode` 等）；响应 `code=0` 成功。

`WmsProductionTaskServiceImpl.dispatchInbound`：
1. 加载记录；仅 `status ∈ {待上架}` 且 `task_status ∈ {未下发, 失败}` 继续，否则直接返回（幂等）。
2. 设备检查：按记录产品类型确定设备类型（半成品→堆垛机，成品→SCR）与目标库区（半成品=库位所属库区，成品=敏捷仓库区）；查 `warehouse_id=目标库区 AND device_type=设备类型 AND status='故障' AND deleted_at=0`，非空 → `markManual`。
3. 调用对应网关；成功 → `task_status=已受理`；`300001` → 视为成功；其余失败 → `markManual`。
4. `markManual`：`status=异常待人工, task_status=失败, task_error=…`，调 `ISysBaseAPI.sendSysAnnouncement`（收件人 `${wms.sync.alert.to-user:admin}`），告警异常只记录不抛。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): RMS/WCS 网关与生产入库任务下发"
```

---

### Task 6: 设备回执（RMS/WCS）与幂等入库

**Files:**
- Create: `dto/WmsTaskFeedbackDTO.java`、`dto/WmsContainerLocationDTO.java`
- Modify: `service/IWmsProductionInboundService.java`、`service/impl/WmsProductionInboundServiceImpl.java`
- Create: `controller/WmsRmsCallbackController.java`、`controller/WmsWcsCallbackController.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsProductionInboundCallbackTest.java`

**Interfaces:**
- Produces:
  - `boolean onTaskFeedback(String taskNo, String carrierCode, String taskStatus, String stackCode, String message, boolean rms)`
  - `void onContainerLocation(String containerCode, String stackCode)`
  - 返回 `true` 表示本次调用完成了入库（调用方随后触发同步）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void finished_writesInventoryOnce() {
    when(inboundMapper.claimFinished("T1", "STACK-1")).thenReturn(1, 0);
    assertTrue(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));
    assertFalse(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));
    verify(inventoryMapper, times(1)).upsertInventory(any());
}

@Test
void running_onlyMovesToExecuting() {
    when(inboundMapper.claimFinished(any(), any())).thenReturn(0);
    service.onTaskFeedback("T1", "C1", "RUNNING", null, null, true);
    verify(inboundMapper).update(any(), argThat(w -> ((UpdateWrapper<?>) w).getSqlSet().contains("执行中")));
}

@Test
void failed_movesToManual() {
    service.onTaskFeedback("T1", "C1", "FAILED", null, "设备故障", false);
    verify(inboundMapper).update(any(), argThat(w -> ((UpdateWrapper<?>) w).getSqlSet().contains("异常待人工")));
}

@Test
void finished_fromException_isAccepted() {
    WmsProductionInbound r = record("成品", "异常待人工", "失败");
    when(inboundMapper.claimFinished("T1", "STACK-1")).thenReturn(1);
    when(inboundMapper.selectOne(any())).thenReturn(r);
    assertTrue(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));
    verify(inventoryMapper).upsertInventory(any());
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现回执服务与控制器**

- `FINISHED`：先 `claimFinished`；影响 1 行 → 重新加载记录，按 `location_id` 写良品库存（`inventoryMapper.upsertInventory`），返回 true；影响 0 行 → 返回 false（幂等）。
- `RUNNING/OUTSTATION`：条件更新 `status='执行中', task_status='执行中'`，仅当当前状态 ∈ `待上架/执行中`。
- `FAILED`：条件更新 `status='异常待人工', task_status='失败', task_error=message`，发告警。
- 控制器：`POST /wms/rms/task-feedback`（头 `X-RMS-Token` 比对 `wms.sync.rms.callback-token`）、`POST /wms/rms/container-location`、`POST /wms/wcs/task-feedback`（头 `X-WCS-Token` 比对 `wms.sync.wcs.callback-token`），均 `@IgnoreAuth`。请求体照 spec §7.3；响应 `{code,msg,details:[{taskNo/containerCode,code,msg}]}`，未知任务明细 `code=-1`、整体 `code=1`。
- **本任务不注入同步服务**（Task 8 才创建），完成入库只把 `sync_status` 置 `待同步`；即时同步的调用在 Task 9 的控制器收口步骤补上。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): RMS/WCS 回执接口与幂等入库"
```

---

### Task 7: 转人工完成、关单与重新下发

**Files:**
- Create: `dto/WmsProductionManualCompleteDTO.java`、`dto/WmsProductionCloseDTO.java`
- Modify: `service/IWmsProductionInboundService.java`、`service/impl/WmsProductionInboundServiceImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsProductionManualCloseTest.java`

**Interfaces:**
- Produces: `void manualComplete(String id, WmsProductionManualCompleteDTO dto)`、`void close(String id, String reason)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void manualComplete_fromException_writesInventoryAndBindsCarrier() {
    service.manualComplete("id1", dto("NEW-RFID"));
    verify(inboundMapper).update(any(), argThat(w -> ((UpdateWrapper<?>) w).getSqlSet().contains("已入库")));
    verify(inventoryMapper).upsertInventory(any());
}

@Test
void manualComplete_fromPending_isRejected() {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> service.manualComplete("id1", dto(null)));
    assertEquals("当前状态不允许转人工完成", e.getMessage());
}

@Test
void close_releasesStereoLocation_andDoesNotWriteInventory() {
    service.close("id1", "设备永久故障");
    verify(locationMapper).update(any(), argThat(w -> ((UpdateWrapper<?>) w).getSqlSet().contains("空闲")));
    verifyNoInteractions(inventoryMapper);
}

@Test
void close_inboundRecord_isRejected() {
    RuntimeException e = assertThrows(RuntimeException.class, () -> service.close("id1", "x"));
    assertEquals("当前状态不允许关单", e.getMessage());
}

@Test
void closedCarrier_canBeReused() {
    when(inboundMapper.selectActiveByCarrier("RFID-1")).thenReturn(null);
    assertDoesNotThrow(() -> service.submit(dto("WO1", "1", "0", "RFID-1")));
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

- `manualComplete`：`@Transactional`；条件更新 `status ∈ {异常待人工} → 已入库`，影响 0 行 → `当前状态不允许转人工完成`。若 `dto.carrierCode` 非空且不同：校验 `selectActiveByCarrier` 不命中其他记录后更新。写良品库存（`location_id`）+ `task_status=已完成`、`complete_time=now`、`sync_status=待同步`。
- `close`：`@Transactional`；条件更新 `status ∈ {待上架, 异常待人工} → 已关闭`，置 `task_status=已关闭`、`sync_status=无需同步`、`close_*`；影响 0 行 → `当前状态不允许关单`。释放半成品记录占用的库位（`locationMapper` 条件更新 `status='占用' → '空闲'`）；成品不释放 `MJ-DEFAULT`；不写良品库存。
- 控制器：`POST /{id}/manual-complete`、`POST /{id}/close`、`POST /{id}/dispatch`（调用 `IWmsProductionTaskService.dispatchInbound`；关单后取消由控制器在 `close` 成功后调用 `cancelInbound`，失败只记日志）。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): 生产入库转人工完成、关单与重新下发"
```

---

### Task 8: ERP/MES/WMS 同步、重试与 Quartz 补偿

**Files:**
- Create: `sync/IWmsProductionInboundSyncGateway.java`、`sync/HttpWmsProductionInboundSyncGateway.java`
- Create: `service/IWmsProductionSyncService.java`、`service/impl/WmsProductionSyncServiceImpl.java`
- Create: `job/WmsProductionSyncRetryJob.java`、`job/WmsProductionSyncJobRegistrar.java`
- Modify: `service/IWmsProductionInboundService.java` + impl（`syncStaleBefore/claimForSync/selectSyncCandidates/persistSyncResult/selectUndispatchedBefore`）
- Test: `WmsProductionSyncServiceImplTest.java`、`WmsProductionSyncRetryJobTest.java`

**Interfaces:**
- Consumes: `WmsOrderSyncResult`（复用）、`WmsOrderSyncAttempt`（复用）、`IWmsProductionTaskService.dispatchInbound`。
- Produces: `WmsOrderSyncAttempt syncInbound(String id, boolean announce)`；Job `scanAndResyncForTest()`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void sync_allTargetsUnconfigured_isMockSuccess() {
    when(gateway.sync(any(), any())).thenReturn(WmsOrderSyncResult.okMock());
    assertTrue(syncService.syncInbound("id1", true).isSuccess());
}

@Test
void sync_failure_retriesAndAlerts() {
    when(gateway.sync(any(), any())).thenReturn(WmsOrderSyncResult.fail("ERP 500"));
    WmsOrderSyncAttempt a = syncService.syncInbound("id1", true);
    assertFalse(a.isSuccess());
    assertEquals(3, a.getRetryCount());
    verify(sysBaseAPI).sendSysAnnouncement(any());
}

@Test
void job_dispatchesUndispatchedAndResyncs() {
    when(inboundService.selectUndispatchedBefore(any(), anyInt())).thenReturn(List.of(record("未下发")));
    when(inboundService.selectSyncCandidates(any(), anyInt())).thenReturn(List.of(record("同步失败")));
    job.scanAndResyncForTest();
    verify(taskService).dispatchInbound(any());
    verify(inboundService).claimForSync(any(), any());
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

- 网关依次调用已配置的 ERP/MES/WMS 地址，任一失败汇总为失败；全部未配置 → `okMock()`；Basic Auth 使用对应 username/password（为空则不加头）。报文照 spec §7.5。
- `WmsProductionSyncServiceImpl` 照 `WmsInboundSyncServiceImpl`：3 次重试、退避 `WmsConstants.SYNC_RETRY_BACKOFF_MS`、失败告警标题 `PRODUCTION_SYNC_ALERT_TITLE`、`setBackoffMsForTest`。
- `persistSyncResult` 照 RK-001；`selectSyncCandidates` 仅 `status='已入库'` 且同步待处理。
- `WmsProductionSyncRetryJob`：先补偿 `未下发`（`submit_time < now-2min`，每轮 50 条，逐条 `dispatchInbound`），再跑同步候选（认领 → 同步 → 落库，`announce=false`）；两条链路单条失败不中断整轮。
- `WmsProductionSyncJobRegistrar` 照 `WmsInboundSyncJobRegistrar`，id/cron 用 `PRODUCTION_SYNC_JOB_ID/CRON`，开关复用 `wms.sync.job.enabled`。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): 生产入库三方同步重试与补偿任务"
```

---

### Task 9: 列表/详情接口、控制器收口与后端构建

**Files:**
- Create: `controller/WmsProductionController.java`
- Modify: `service/IWmsProductionInboundService.java` + impl（`listPage`、`detail`）
- Test: `WmsProductionControllerTest.java`

**Interfaces:**
- Produces: `GET /wms/production/list`、`GET /wms/production/{id}`、`GET /wms/production/work-order`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void list_filtersByStatusAndCarrier() {
    WmsProductionInbound query = new WmsProductionInbound()
        .setStatus("已入库").setInboundNo("SC001").setCarrierCode("RFID-1");
    service.listPage(1, 10, query);
    verify(inboundMapper).selectPage(any(), argThat(w ->
        ((QueryWrapper<?>) w).getParamNameValuePairs().containsValue("已入库")));
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

- `listPage(int pageNo, int pageSize, WmsProductionInbound query)`：`inboundNo/orderNo` 用 like，其余实体字段用 eq（忽略空值），`orderByDesc("submit_time")`。
- `detail(id)`：返回实体；不存在 → `生产入库记录不存在`。
- 删除守卫复核：确认物料与库位 `checkDelete` 已覆盖 `wms_inventory` 引用（RK-001 已加）；若库位守卫只查 RK-001 明细，改为直接统计 `wms_inventory` 中 `location_id` 的未删除行，确保生产入库的库存也阻止删除。
- `WmsProductionController`：
  - `GET /work-order` → `workOrderService.scan(workOrderNo)`。
  - `POST /submit` → `service.submit(dto)`，然后调用 `taskService.dispatchInbound(record.getId())`（仅当 `good_qty > 0`），返回最新记录。
  - `POST /{id}/dispatch` → `taskService.dispatchInbound(id)`。
  - `POST /{id}/manual-complete` → `service.manualComplete`，然后 `syncService.syncInbound(id, true)`。
  - `POST /{id}/close` → `service.close`，然后 `taskService.cancelInbound(id)`（捕获异常仅日志）。
  - `POST /{id}/sync` → 校验 `status='已入库'` → `claimForSync` → `syncService.syncInbound` → `persistSyncResult` → 返回 attempt。
  - `GET /list`、`GET /{id}`。
- 回执控制器补即时同步：Task 6 的 RMS/WCS 回执控制器对返回 `true` 的记录调用 `syncService.syncInbound(id, true)`，再 `persistSyncResult`；返回 `false`（重复回执）不触发。
- 所有写端点捕获 `RuntimeException → Result.error(e.getMessage())`。

- [ ] **Step 4: 构建 + 单测全量**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，所有生产入库测试通过。

- [ ] **Step 5: install 并重启后端**

```bash
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests
# 重启 8081 后端 JVM
```

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(wms): 生产入库列表详情接口与控制器收口"
```

---

### Task 10: 前端生产入库页与任务跟踪

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/production/production.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/production/production.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/production/index.vue`
- Test: `npx eslint` + `pnpm build`

**Interfaces:**
- Consumes: Task 9 的 HTTP 端点。
- Produces: 页面组件，菜单 `component=wms/production/index`。

- [ ] **Step 1: 写 API 封装**

```ts
import { defHttp } from '/@/utils/http/axios';
const P = '/wms/production';
export const scanWorkOrder = (workOrderNo: string) => defHttp.get({ url: `${P}/work-order`, params: { workOrderNo } });
export const submitProductionInbound = (params) => defHttp.post({ url: `${P}/submit`, params });
export const listProductionInbound = (params?) => defHttp.get({ url: `${P}/list`, params });
export const getProductionInbound = (id: string) => defHttp.get({ url: `${P}/${id}` });
export const dispatchProductionInbound = (id: string) => defHttp.post({ url: `${P}/${id}/dispatch` });
export const manualCompleteProductionInbound = (id: string, params) => defHttp.post({ url: `${P}/${id}/manual-complete`, params });
export const closeProductionInbound = (id: string, reason: string) => defHttp.post({ url: `${P}/${id}/close`, params: { reason } });
export const resyncProductionInbound = (id: string) => defHttp.post({ url: `${P}/${id}/sync` });
```

- [ ] **Step 2: 写列定义**

`production.data.ts` 导出 `columns: BasicColumn[]`（字段见 spec §8）与 `statusColorMap`；状态 tag 颜色：待上架=processing、执行中=processing、已入库=success、异常待人工=error、已关闭=default。

- [ ] **Step 3: 写页面**

`index.vue` 结构：
- 顶部 `a-card title="扫码作业台"`：工单号 `a-input`（`ref` + 自动聚焦 + `@press-enter`）、查询按钮；工单信息 `a-descriptions`；录入区 `a-form layout="inline"`：良品数量/不良品数量 `a-input-number :min="0" :precision="3"`、不良原因 `a-input`、批次号 `a-input`、载具码 `a-input`（label 随产品类型切换「周转盘 RFID」/「料箱二维码」）；提交按钮 `:loading="submitting"`。
- 底部 `BasicTable`：`useListPage({ tableProps: { api: listProductionInbound, columns, rowKey:'id', formConfig:{schemas}, actionColumn:{width:260} } })`；`TableAction` 按状态显示：详情、重新下发（`taskStatus in ['失败','未下发']`）、转人工完成（`status==='异常待人工'`）、关单（`status in ['待上架','异常待人工']`）、重试同步（`syncStatus==='同步失败'`）。
- 轮询：`onMounted` 启动 `setInterval(() => reload(), 5000)`，`onUnmounted` 清除；另有手动刷新按钮。
- 提交成功后清空数量/载具码并重新聚焦工单号输入框；查询失败文案原样 `createMessage.error`。
- 转人工完成用 `Modal.confirm` + 可选载具码输入（用 `a-input` 简化：确认框内不输入，传 `{remark:'人工确认完成'}`）；关单用 `Modal.confirm` 要求原因（`a-input` 通过 `Modal.confirm` 的 `content` 插槽渲染）。

- [ ] **Step 4: 校验与构建**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3
npx eslint src/views/wms/production/*.ts src/views/wms/production/index.vue
pnpm build
```

Expected: eslint 无 error；build 成功。

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(wms): 生产入库页面与任务状态跟踪"
```

---

### Task 11: 前端生产工单维护页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/workOrder/workOrder.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/workOrder/workOrder.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/workOrder/index.vue`
- Create: `jeecgboot-vue3/src/views/wms/workOrder/components/WorkOrderModal.vue`
- Test: `npx eslint` + `pnpm build`

**Interfaces:**
- Consumes: Task 3 的 `/wms/work-order/*`。
- Produces: 菜单 `component=wms/workOrder/index`。

- [ ] **Step 1: 写 API 与列/表单**

```ts
import { defHttp } from '/@/utils/http/axios';
const W = '/wms/work-order';
export const listWorkOrder = (params?) => defHttp.get({ url: `${W}/list`, params });
export const addWorkOrder = (params) => defHttp.post({ url: `${W}/add`, params });
export const editWorkOrder = (params) => defHttp.put({ url: `${W}/edit`, params });
export const deleteWorkOrder = (params, cb) => defHttp.delete({ url: `${W}/${params.id}` }, { joinParamsToUrl: true }).then(() => cb && cb());
```

`workOrder.data.ts`：列（工单号、物料编码、物料名称、产品类型、计划数量、批次号、工单状态、来源、创建时间）；表单 schema（工单号、物料编码、产品类型下拉「半成品/成品」、计划数量、批次号、工单状态下拉、备注）。

- [ ] **Step 2: 写列表页与弹窗**

`index.vue` 照 `wms/order/index.vue`：`BasicTable` + `useListPage` + `TableAction`（编辑/删除），新增按钮 `v-auth="'wms:work-order:add'"`。
`WorkOrderModal.vue` 照 `wms/order/components/OrderModal.vue`：`BasicModal` + `BasicForm`，`useModalInner`，`isUpdate` 区分新增/编辑，成功后 `emit('success')`。

- [ ] **Step 3: 校验与构建**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3
npx eslint src/views/wms/workOrder/**/*.ts src/views/wms/workOrder/**/*.vue
pnpm build
```

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(wms): 生产工单维护页面"
```

---

### Task 12: 后端端到端联调（API 级）

**Files:**
- Create: `docs/superpowers/plans/2026-10-19-yx-wms-rk-002-api-e2e.md`（执行记录，可后并入验收报告）

**Interfaces:**
- Consumes: 所有已部署接口。

- [ ] **Step 1: 获取 token 并确认工单可用**

登录接口 `/jeecg-boot/sys/login`（验证码可随意填，见项目记忆），取 `result.token`。查询 `WO-DEMO-SEMI` 与 `WO-DEMO-FG` 返回 `remainingQty > 0`。

- [ ] **Step 2: 半成品主链路**

1. `POST /wms/production/submit` body `{"workOrderNo":"WO-DEMO-SEMI","goodQty":8,"badQty":2,"carrierCode":"RFID-E2E-001","batchNo":"B20261019A"}` → `status=待上架`。
2. 确认废品仓库存：`SELECT quantity FROM wms_inventory WHERE location_id='2110885981815907401' AND batch_no='B20261019A'` = 2。
3. 模拟 WCS 回执：`POST /wms/wcs/task-feedback`，头 `X-WCS-Token: dev-wcs-token`，body `{"details":[{"taskNo":"<wcsTaskNo>","carrierCode":"RFID-E2E-001","taskStatus":"FINISHED","locationCode":"LB-..."}]}`。
4. 断言记录 `status=已入库`、`task_status=已完成`；立库库位库存 +8；`sync_status=同步成功`（全部同步地址为空 → mocked）。
5. 再次发送同一 FINISHED → 库存不增加。

- [ ] **Step 3: 成品主链路**

1. `POST /submit` body `{"workOrderNo":"WO-DEMO-FG","goodQty":5,"badQty":0,"carrierCode":"MITA_00101"}` → `status=待上架`。
2. 模拟 RMS 回执：`POST /wms/rms/task-feedback`，头 `X-RMS-Token: dev-rms-token`，body `{"details":[{"taskNo":"<rmsTaskNo>","containerCode":"MITA_00101","taskStatus":"FINISHED","stackCode":"MITA-STACK-01-15"}]}`。
3. 断言 `stack_code=MITA-STACK-01-15`、敏捷仓 `MJ-DEFAULT` 库存 +5、`sync_status=同步成功`。

- [ ] **Step 4: 异常分支逐条断言**

| 场景 | 请求 | 期望 |
|---|---|---|
| 工单不存在 | `GET /work-order?workOrderNo=NOPE` | `message="工单号无效"` |
| 超计划量 | 半成品单再提交 `goodQty=1000` | `message="数量超出工单范围"` |
| 载具占用 | 半成品提交未完结的 `RFID-E2E-001` | `message="载具正在使用中"` |
| 设备故障 | 将堆垛机设备置「故障」后提交半成品 | `status=异常待人工`，收到站内公告 |
| 关单 | 对异常单 `POST /close` | `status=已关闭`，立库库位回「空闲」，库存不变 |
| 同步失败 | 临时把 `wms.sync.erp.production-inbound-url` 配成不可达地址并重启，再完成一单 | `sync_status=同步失败`，`last_sync_error` 非空，收到告警 |
| 未下发补偿 | 直接改库把一条记录置 `task_status=未下发` 且 `submit_time` 提前 10 分钟，触发 Job | 记录转 `已受理` 或 `异常待人工` |

- [ ] **Step 5: 记录结果并 Commit**

```bash
git add docs/superpowers/plans/2026-10-19-yx-wms-rk-002-api-e2e.md
git commit -m "docs(wms): 生产入库 API 端到端联调记录"
```

---

### Task 13: 浏览器验收、验收报告与收尾

**Files:**
- Create: `docs/superpowers/plans/2026-10-19-yx-wms-rk-002-验收报告.md`

- [ ] **Step 1: 浏览器实测**

用 chromium + playwright-core（npmmirror 安装，登录 token 注入）：
1. 菜单出现「生产工单」「生产入库」。
2. 工单页新增一条工单并能在入库页扫码查到。
3. 半成品：扫码 → 填 8/2 → 提交 → 列表状态流转到「已入库」。
4. 成品：扫码 → 填 5/0 → 扫料箱码 → 提交 → 状态流转到「已入库」。
5. 异常单显示「转人工完成 / 关单」按钮且可点击完成。
6. 截图留存到 `.tmp_e2e/`。

- [ ] **Step 2: 检查构建与迁移**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev clean package -pl jeecg-boot-module/jeecg-boot-module-wms -DskipTests
grep -c "flyway执行sql脚本失败" <启动日志>   # 0
```

- [ ] **Step 3: 写验收报告**

结构照 `2026-10-16-yx-wms-bd-004-验收报告.md`：结论、验收标准对照（半成品/成品/四条异常）、端到端执行结果、抓到并修复的问题、未覆盖项与遗留风险（MES/ERP 地址、WCS 协议、RMS 真实联调未完成）。

- [ ] **Step 4: 最终提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add -A
git commit -m "docs(wms): 生产入库验收报告与收尾"
```

---

## 完成定义（DoD）

- [ ] `V3.9.5_13` 迁移成功，`flyway_schema_history.success=1`，启动日志无「flyway执行sql脚本失败」。
- [ ] 后端 wms 模块全量单测通过；`install` 完成且后端已重启。
- [ ] 半成品与成品两条链路 API 端到端通过；库存、载具绑定、状态流转有 DB 证据。
- [ ] 四条异常提示逐字匹配；设备故障有站内告警；同步失败自动重试且补偿任务可续跑。
- [ ] 前端两页面渲染、扫码查询、提交、轮询、详情、转人工、关单可用；`pnpm build` 通过。
- [ ] 验收报告落盘，遗留项（MES/ERP 地址、WCS 协议、RMS 真实环境）明确标注。
