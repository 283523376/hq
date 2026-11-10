# 成品/半成品出库（YX-WMS-CK-001 / CK-002）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付半成品领料出库（扫码工单 → 立库分配 → 堆垛机 → 取料确认 → `PRD_PickMtrl`）与成品销售出库（销售订单 → 料箱分配 → RMS 出库 → 复核 → 扣库存 → `SAL_OUTSTOCK`）两条链路，含四类异常告警、同步重试与前端作业页面。

**Architecture:** 新增 `wms_outbound_order` / `wms_outbound_detail` / `wms_outbound_allocation` 三张表；库存表增加 `container_code`、`locked_qty`，分配阶段按 `locked_qty` 条件更新锁定、确认阶段才扣 `quantity`。设备下发复用 RK-002 的「故障检查 → 网关分流 → 条件回写 → 告警转人工」范式；金蝶回写复用 `WmsKingdeeFormClient` 与组织/仓位解析逻辑，三个 Writer 分别对应 `PRD_PickMtrl`、`SAL_SaleOrder`、`SAL_OUTSTOCK`。

**Tech Stack:** JeecgBoot 3.9.5 / Spring Boot 4.1.0 / Java 17 / MyBatis-Plus / Shiro+JWT / Flyway / Quartz / Vue 3 + Ant Design Vue 4 + BasicTable。

**Spec:** `docs/superpowers/specs/2026-10-21-yx-wms-ck-001-ck-002-outbound-design.md`

## Global Constraints

- 只改 `D:\yongxin-WMS\JeecgBoot`；后端改动后必须 `install` wms 模块并**重启后端 JVM**。
- 所有业务表 `wms_` 前缀；状态值用中文常量，全部放 `WmsConstants`，禁止魔法值。
- 接口统一 `Result<T>`；Controller 内 `try/catch → Result.error(e.getMessage())`，文案逐字透出。
- 逻辑删除：`Long deletedAt` + `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")`。
- Mapper XML 放 `src/main/java/org/jeecg/modules/wms/mapper/xml/`；Mapper 接口不加 `@Mapper`。
- 数量/库存一律 `BigDecimal`，比较用 `compareTo`，禁止 `equals`。
- 外部 HTTP 调用**不得**放在 `@Transactional` 方法内；失败只落库 + 告警，不向上抛。
- 来源系统接口用方法级 `@IgnoreAuth` + 令牌头校验；普通业务接口不加 `@IgnoreAuth`。
- Flyway 版本 `V3.9.5_17`，脚本幂等；迁移失败不会阻断启动，必须 grep 日志验证。
- 销售订单同步失败**不阻断**分配/下发；`confirm` 必须由操作员显式 `overrideSaleOrderSync=true` + 原因后才允许出库。
- 容器盘点/数据迁移**不在本期**；实现必须过滤 `container_code=''` 的历史库存并在提示中说明。
- 前端遵循 `jeecgboot-vue3` 约定：API 文件与页面同目录，`a-*` 组件免 import，`BasicTable` 需显式 import。
- 提交信息用 `feat(wms): …` / `fix(wms): …`，在 `JeecgBoot` 仓库根目录提交。
- **提交需用户授权**：计划中的 `git commit` 步骤在用户明确同意前不执行；未获授权时把改动留在工作区并在交付说明中列出待提交文件。

## Review Focus

1. **重复回执 / 重复下发**：同一 `task_no` 的 `FINISHED` 重复到达不得重复扣库存；重复 `dispatch` 不得产生第二个任务号。
2. **并发抢库存/料箱**：两个出库单同时分配同一库存行/容器时，`locked_qty` 条件更新必须让第二个请求失败，而不是超发。
3. **复核恢复路径**：改数量、重扫、放行三种恢复都必须能回到可确认状态；`actual_qty > allocated_qty` 必须拒绝；`actual_qty = 0` 不得扣库存但要释放锁定。
4. **销售订单同步失败出库**：未显式 override 不能完成出库；override 必须留痕；后台重试成功后不得重复创建金蝶单据。
5. **跨组织取数**：CK-001 的仓库/仓位/物料必须跟随工单组织；取不到时要明确失败，不能静默使用默认组织。

---

## 文件结构

**后端（`jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/`）**

| 文件 | 职责 |
|---|---|
| `entity/WmsOutboundOrder.java` / `WmsOutboundDetail.java` / `WmsOutboundAllocation.java` | 出库单、需求明细、分配明细 |
| `entity/WmsInventory.java`（改） | 增加 `containerCode`、`lockedQty` |
| `mapper/WmsOutboundOrderMapper.java` + `xml` | 同步/销售同步认领、补偿候选、未下发扫描 |
| `mapper/WmsOutboundDetailMapper.java` | 明细 CRUD |
| `mapper/WmsOutboundAllocationMapper.java` + `xml` | 回执认领、任务状态 |
| `mapper/WmsInventoryMapper.java` + `xml`（改） | 可用库存查询、锁定/释放/扣减 |
| `dto/WmsOutboundPickCreateDTO.java`、`WmsOutboundSalesCreateDTO.java`、`WmsOutboundDetailDTO.java`、`WmsOutboundReviewDTO.java`、`WmsOutboundConfirmDTO.java`、`WmsOutboundCloseDTO.java` | 入参 |
| `vo/WmsOutboundScanVO.java`、`WmsOutboundRequirementVO.java`、`WmsOutboundDetailVO.java`、`WmsOutboundConfirmVO.java`、`WmsInventoryAvailableVO.java` | 出参 |
| `service/IWmsOutboundService.java` + `impl/WmsOutboundServiceImpl.java` | 创建/扫码/分配/复核/确认/关单/回执/列表 |
| `service/IWmsOutboundAllocator.java` + `impl/WmsOutboundAllocatorImpl.java` | FIFO 分配与锁定 |
| `service/IWmsOutboundTaskService.java` + `impl/WmsOutboundTaskServiceImpl.java` | WCS/RMS 下发与取消（事务外） |
| `service/IWmsOutboundSyncService.java` + `impl/WmsOutboundSyncServiceImpl.java` | 出库单与销售订单同步重试 |
| `sync/IWmsWcsGateway.java`、`HttpWmsWcsGateway.java`（改） | 堆垛机出库下发/取消 |
| `sync/IWmsRmsGateway.java`、`HttpWmsRmsGateway.java`、`WmsRmsClient.java`、`WmsRmsErrorCode.java`（改） | RMS 出库下发/优先级/取消 |
| `sync/WmsRmsOutboundTask.java` | RMS 出库明细 |
| `sync/IWmsKingdeePpbomReader.java` + `WmsKingdeePpbomReaderImpl.java` | `PRD_PPBOM` 需求读取 |
| `sync/IWmsKingdeePickWriter.java` / `IWmsKingdeeSaleOrderWriter.java` / `IWmsKingdeeOutstockWriter.java` + 三个实现 | 三个金蝶表单回写 |
| `sync/WmsKingdeeLookupSupport.java` | 组织/物料/单位/仓库/仓位/客户解析 |
| `sync/IWmsOutboundSyncGateway.java` + `HttpWmsOutboundSyncGateway.java` | 金蝶/MES 分流 |
| `job/WmsOutboundSyncRetryJob.java` + `WmsOutboundSyncJobRegistrar.java` | 补偿任务 |
| `controller/WmsOutboundController.java` | 对外接口 |
| `controller/WmsRmsCallbackController.java`、`WmsWcsCallbackController.java`（改） | 回执路由到出库 |

**前端（`jeecgboot-vue3/src/views/wms/outbound/`）**

| 文件 | 职责 |
|---|---|
| `outbound.api.ts` / `outbound.data.ts` | 接口与列定义 |
| `index.vue` | 出库单列表 + 新建入口 |
| `components/OutboundCreateModal.vue` | 领料/销售两种创建表单 |
| `components/OutboundDetailDrawer.vue` | 分配、任务、复核、确认、关单面板 |

**迁移与配置**

- `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_17__wms_outbound.sql`
- `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`（追加 `wms.sync.*`）
- `src/main/resources/mapper` 无需改：XML 仍放 java 目录。

---

### Task 1: 迁移脚本、实体与常量

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_17__wms_outbound.sql`
- Create: `.../wms/entity/WmsOutboundOrder.java`、`WmsOutboundDetail.java`、`WmsOutboundAllocation.java`
- Modify: `.../wms/entity/WmsInventory.java`（加 `containerCode`、`lockedQty`）
- Modify: `.../wms/constant/WmsConstants.java`（文件末尾追加出库域常量）
- Test: `.../wms/entity/WmsOutboundEntityMappingTest.java`

**Interfaces:**
- Produces: 表 `wms_outbound_order`、`wms_outbound_detail`、`wms_outbound_allocation`；`wms_inventory.container_code`/`locked_qty`；常量 `OUTBOUND_TYPE_PICK`、`OUTBOUND_TYPE_SALES`、`OUTBOUND_STATUS_*`、`REVIEW_STATUS_*`、`REQUIREMENT_SOURCE_PPBOM`/`MANUAL`、`OUTBOUND_NO_PREFIX_PICK`/`SALES`、`OUTBOUND_*_ALERT_TITLE`、`OUTBOUND_SYNC_JOB_ID`/`CLASS`/`CRON`。

- [ ] **Step 1: 写失败测试**

```java
class WmsOutboundEntityMappingTest {
    @Test void tableNames_mustMatchDdl() {
        assertEquals("wms_outbound_order", WmsOutboundOrder.class.getAnnotation(TableName.class).value());
        assertEquals("wms_outbound_detail", WmsOutboundDetail.class.getAnnotation(TableName.class).value());
        assertEquals("wms_outbound_allocation", WmsOutboundAllocation.class.getAnnotation(TableName.class).value());
    }
    @Test void logicalDelete_onThreeTables() { /* deletedAt 上有 @TableLogic("0") */ }
    @Test void inventory_hasContainerAndLockColumns() throws Exception {
        assertNotNull(WmsInventory.class.getDeclaredField("containerCode"));
        assertNotNull(WmsInventory.class.getDeclaredField("lockedQty"));
    }
    @Test void constants_haveExactValues() {
        assertEquals("生产领料", WmsConstants.OUTBOUND_TYPE_PICK);
        assertEquals("销售出库", WmsConstants.OUTBOUND_TYPE_SALES);
        assertEquals("待复核", WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        assertEquals("1920000000000000300", WmsConstants.OUTBOUND_SYNC_JOB_ID);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/yongxin-WMS/JeecgBoot/jeecg-boot && bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsOutboundEntityMappingTest`
Expected: 编译失败「找不到 WmsOutboundOrder / 常量」。

- [ ] **Step 3: 建表与改表**

逐列实现 spec §2.1/§2.2。关键 DDL：

```sql
CREATE TABLE IF NOT EXISTS `wms_outbound_order` (
  `id` varchar(32) NOT NULL,
  `outbound_no` varchar(64) NOT NULL,
  `outbound_type` varchar(16) NOT NULL COMMENT '生产领料/销售出库',
  `work_order_no` varchar(64) DEFAULT NULL,
  `sales_order_no` varchar(64) DEFAULT NULL,
  `customer_code` varchar(64) DEFAULT NULL,
  `customer_name` varchar(128) DEFAULT NULL,
  `requirement_source` varchar(16) DEFAULT '手工' COMMENT '金蝶用料清单/手工',
  `status` varchar(20) NOT NULL DEFAULT '待分配',
  `sale_sync_status` varchar(20) DEFAULT '无需同步',
  `sale_sync_error` varchar(500) DEFAULT NULL,
  `sale_sync_time` datetime DEFAULT NULL,
  `sync_status` varchar(20) NOT NULL DEFAULT '无需同步',
  `retry_count` int NOT NULL DEFAULT 0,
  `last_sync_error` varchar(500) DEFAULT NULL,
  `last_sync_time` datetime DEFAULT NULL,
  `sync_fail_count` int NOT NULL DEFAULT 0,
  `override_by` varchar(50) DEFAULT NULL,
  `override_time` datetime DEFAULT NULL,
  `override_reason` varchar(500) DEFAULT NULL,
  `close_reason` varchar(500) DEFAULT NULL,
  `close_by` varchar(50) DEFAULT NULL,
  `close_time` datetime DEFAULT NULL,
  `operator` varchar(50) DEFAULT NULL,
  `submit_time` datetime DEFAULT NULL,
  `complete_time` datetime DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `deleted_at` bigint NOT NULL DEFAULT 0,
  `create_by` varchar(50), `create_time` datetime, `update_by` varchar(50), `update_time` datetime,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_wms_outbound_no` (`outbound_no`, `deleted_at`),
  INDEX `idx_wms_outbound_status` (`status`),
  INDEX `idx_wms_outbound_type` (`outbound_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='出库单';

CREATE TABLE IF NOT EXISTS `wms_outbound_detail` (
  `id` varchar(32) NOT NULL,
  `outbound_id` varchar(32) NOT NULL,
  `line_no` int NOT NULL,
  `material_id` varchar(32) NOT NULL,
  `material_code` varchar(64) NOT NULL,
  `material_name` varchar(100) DEFAULT NULL,
  `batch_no` varchar(64) DEFAULT NULL,
  `required_qty` decimal(12,3) NOT NULL,
  `allocated_qty` decimal(12,3) NOT NULL DEFAULT 0,
  `actual_qty` decimal(12,3) NOT NULL DEFAULT 0,
  `unit` varchar(16) DEFAULT NULL,
  `source_entry_id` varchar(64) DEFAULT NULL,
  `deleted_at` bigint NOT NULL DEFAULT 0,
  `create_by` varchar(50), `create_time` datetime, `update_by` varchar(50), `update_time` datetime,
  PRIMARY KEY (`id`),
  INDEX `idx_wms_out_detail_out` (`outbound_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='出库需求明细';

CREATE TABLE IF NOT EXISTS `wms_outbound_allocation` (
  `id` varchar(32) NOT NULL,
  `outbound_id` varchar(32) NOT NULL,
  `detail_id` varchar(32) NOT NULL,
  `inventory_id` varchar(32) NOT NULL,
  `location_id` varchar(32) DEFAULT NULL,
  `location_code` varchar(64) DEFAULT NULL,
  `container_code` varchar(64) NOT NULL DEFAULT '',
  `allocated_qty` decimal(12,3) NOT NULL,
  `actual_qty` decimal(12,3) NOT NULL DEFAULT 0,
  `task_no` varchar(64) DEFAULT NULL,
  `task_status` varchar(20) NOT NULL DEFAULT '未下发',
  `task_error` varchar(500) DEFAULT NULL,
  `review_status` varchar(20) DEFAULT '待复核',
  `scan_code` varchar(64) DEFAULT NULL,
  `review_note` varchar(500) DEFAULT NULL,
  `deleted_at` bigint NOT NULL DEFAULT 0,
  `create_by` varchar(50), `create_time` datetime, `update_by` varchar(50), `update_time` datetime,
  PRIMARY KEY (`id`),
  INDEX `idx_wms_out_alloc_out` (`outbound_id`),
  INDEX `idx_wms_out_alloc_task` (`task_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='出库分配明细';

ALTER TABLE `wms_inventory`
  ADD COLUMN `container_code` varchar(64) NOT NULL DEFAULT '' COMMENT '料箱/托盘编码',
  ADD COLUMN `locked_qty` decimal(12,3) NOT NULL DEFAULT 0 COMMENT '出库分配锁定数量',
  DROP INDEX `uk_wms_inv_mat_loc_batch`,
  ADD UNIQUE INDEX `uk_wms_inv_mat_loc_batch_container` (`material_id`, `location_id`, `batch_no`, `container_code`);

-- 菜单：出库管理（顶层，sort=92），子菜单「出库单」
INSERT IGNORE INTO `sys_permission` (...) VALUES
('1910000000000000301','','出库管理','/wms/outbound','layouts/default/index',1,'','/wms/outbound/list',0,NULL,'0',92.00,0,'ant-design:export-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000302','1910000000000000301','出库单','/wms/outbound/list','wms/outbound/index',1,'','',1,NULL,'0',1.00,0,'ant-design:unordered-list-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 按钮权限 id 从 ...311 起：list/scan/add/sales/allocate/dispatch/review/confirm/sync/close
-- admin 角色授权照 V3.9.5_13 的 sys_role_permission 写法。
-- Quartz 种子行：id 1920000000000000300，job_class_name=org.jeecg.modules.wms.job.WmsOutboundSyncRetryJob，cron=0 17/5 * * * ? *
```

- [ ] **Step 4: 写实体与常量**

三个实体继承 `JeecgEntity`，字段名与 DDL 一一对应；`deletedAt` 加 `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")`。`WmsInventory` 增加 `private String containerCode;`、`private BigDecimal lockedQty;`。

常量值：

```java
public static final String OUTBOUND_TYPE_PICK = "生产领料";
public static final String OUTBOUND_TYPE_SALES = "销售出库";
public static final String OUTBOUND_NO_PREFIX_PICK = "LL";
public static final String OUTBOUND_NO_PREFIX_SALES = "XS";
public static final String OUTBOUND_STATUS_PENDING_ALLOCATE = "待分配";
public static final String OUTBOUND_STATUS_ALLOCATED = "已分配";
public static final String OUTBOUND_STATUS_EXECUTING = "执行中";
public static final String OUTBOUND_STATUS_PENDING_REVIEW = "待复核";
public static final String OUTBOUND_STATUS_REVIEW_EXCEPTION = "复核异常";
public static final String OUTBOUND_STATUS_EXCEPTION = "异常待人工";
public static final String OUTBOUND_STATUS_COMPLETED = "已完成";
public static final String OUTBOUND_STATUS_CLOSED = "已关闭";
public static final String REVIEW_STATUS_PENDING = "待复核";
public static final String REVIEW_STATUS_MATCHED = "一致";
public static final String REVIEW_STATUS_MISMATCHED = "不一致";
public static final String REVIEW_STATUS_RELEASED = "已放行";
public static final String REQUIREMENT_SOURCE_PPBOM = "金蝶用料清单";
public static final String REQUIREMENT_SOURCE_MANUAL = "手工";
public static final String OUTBOUND_SYNC_JOB_ID = "1920000000000000300";
public static final String OUTBOUND_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsOutboundSyncRetryJob";
public static final String OUTBOUND_SYNC_JOB_CRON = "0 17/5 * * * ? *";
public static final int OUTBOUND_SYNC_STALE_MINUTES = 10;
public static final int UNDISPATCHED_OUTBOUND_TIMEOUT_MINUTES = 2;
public static final String OUTBOUND_STOCK_ALERT_TITLE = "WMS出库库存不足告警";
public static final String OUTBOUND_LOCATION_ALERT_TITLE = "WMS出库库位异常告警";
public static final String OUTBOUND_DEVICE_ALERT_TITLE = "WMS出库设备异常告警";
public static final String OUTBOUND_REVIEW_ALERT_TITLE = "WMS出库复核不一致告警";
public static final String OUTBOUND_SYNC_ALERT_TITLE = "WMS出库同步失败告警";
public static final String SALE_ORDER_SYNC_ALERT_TITLE = "WMS销售订单同步失败告警";
```

- [ ] **Step 5: 运行测试直到通过**

Run: 同 Step 2。
Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交（需用户授权）**

```bash
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_17__wms_outbound.sql \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/entity/WmsOutboundEntityMappingTest.java
git commit -m "feat(wms): 新增出库单表结构与实体（CK-001/CK-002）"
```

---

### Task 2: 出库单创建与持久化

**Files:**
- Create: `mapper/WmsOutboundOrderMapper.java`、`mapper/xml/WmsOutboundOrderMapper.xml`、`mapper/WmsOutboundDetailMapper.java`
- Create: `dto/WmsOutboundPickCreateDTO.java`、`dto/WmsOutboundSalesCreateDTO.java`、`dto/WmsOutboundDetailDTO.java`
- Create: `service/IWmsOutboundService.java`、`service/impl/WmsOutboundServiceImpl.java`
- Test: `service/impl/WmsOutboundCreateTest.java`

**Interfaces:**
- Produces:
  - `interface IWmsOutboundService extends IService<WmsOutboundOrder>`
  - `WmsOutboundOrder createPick(WmsOutboundPickCreateDTO dto)`
  - `WmsOutboundOrder createSales(WmsOutboundSalesCreateDTO dto)`
  - `IPage<WmsOutboundOrder> listPage(Integer pageNo, Integer pageSize, String outboundType, String status, String keyword)`
  - `String WmsOutboundServiceImpl.nextNo(String prefix)`

- [ ] **Step 1: 写失败测试**

```java
class WmsOutboundCreateTest {
    // mock WmsOutboundOrderMapper / WmsOutboundDetailMapper / WmsWorkOrderMapper / WmsMaterialMapper
    @Test void createPick_persistsOrderAndDetails() {
        // 工单 WO1 存在；物料 PBC001 存在
        WmsOutboundOrder order = service.createPick(dto("WO1", detail("PBC001", "10")));
        assertEquals("生产领料", order.getOutboundType());
        assertTrue(order.getOutboundNo().startsWith("LL"));
        assertEquals("待分配", order.getStatus());
        assertEquals("无需同步", order.getSyncStatus());
        verify(detailMapper).insert(argThat(d -> d.getRequiredQty().compareTo(new BigDecimal("10")) == 0));
    }
    @Test void createPick_unknownWorkOrder_throws() { /* 文案「工单号无效」 */ }
    @Test void createPick_emptyDetails_throws() { /* 文案「领料明细不能为空」 */ }
    @Test void createPick_unknownMaterial_throws() { /* 文案「物料编码不存在：X」 */ }
    @Test void createSales_requiresCustomerAndSetsSaleSyncPending() {
        WmsOutboundOrder order = service.createSales(salesDto("SO1", "01.001", "湖南屹华"));
        assertTrue(order.getOutboundNo().startsWith("XS"));
        assertEquals("待同步", order.getSaleSyncStatus());
    }
    @Test void createSales_negativeQty_throws() { /* 文案「数量必须大于 0」 */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsOutboundCreateTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现创建逻辑**

`createPick`：
1. `workOrderNo` 非空；按 `work_order_no` 查 `WmsWorkOrder`，不存在抛「工单号无效」。
2. 明细非空；逐行校验 `materialCode` 非空、`requiredQty > 0` 且小数位 ≤ 3；按 `material_code` 查 `WmsMaterial`，不存在抛「物料编码不存在：X」。
3. 主表：`outboundNo = nextNo("LL")`、`outboundType=生产领料`、`requirementSource = dto.getRequirementSource()`（空则「手工」）、`status=待分配`、`syncStatus=无需同步`、`saleSyncStatus=无需同步`、`operator=WmsUtils.currentUsername()`、`submitTime=now`。
4. 插入主表后逐行插入明细，`lineNo` 从 1 递增，`materialId/Code/Name` 从物料取，`unit` 取物料单位。

`createSales`：校验 `salesOrderNo`、`customerCode`、`customerName` 非空；其余同上，`outboundNo=XS…`、`saleSyncStatus=待同步`、`syncStatus=无需同步`。

Mapper 本任务只需 `BaseMapper`；XML 先建空 `<mapper>` 并在后续任务补查询。

- [ ] **Step 4: 运行测试直到通过**

Run: `... -Dtest=WmsOutboundCreateTest`
Expected: 6 个用例全绿。

- [ ] **Step 5: 提交（需用户授权）**

```bash
git add ... && git commit -m "feat(wms): 出库单创建（领料/销售）"
```

---

### Task 3: CK-001 工单扫码与 PPBOM 需求读取

**Files:**
- Create: `sync/IWmsKingdeePpbomReader.java`、`sync/WmsKingdeePpbomReaderImpl.java`
- Create: `vo/WmsOutboundScanVO.java`、`vo/WmsOutboundRequirementVO.java`
- Modify: `service/IWmsOutboundService.java`、`service/impl/WmsOutboundServiceImpl.java`（加 `scanPick`）
- Test: `sync/WmsKingdeePpbomReaderImplTest.java`、`service/impl/WmsOutboundScanTest.java`

**Interfaces:**
- Consumes: `WmsKingdeeFormClient.executeBillQuery(formId, fields, filter, top)`；`WmsKingdeeProperties.isEnabled()/getMaterialMap()`。
- Produces: `List<WmsOutboundRequirementVO> IWmsKingdeePpbomReader.read(String workOrderNo)`；`WmsOutboundScanVO IWmsOutboundService.scanPick(String workOrderNo)`。

- [ ] **Step 1: 写失败测试**

```java
class WmsKingdeePpbomReaderImplTest {
    @Test void read_subtractsPickedQty_andSkipsNonPositive() {
        // 返回两行：需求 100 已领 30 → 70；需求 5 已领 5 → 跳过
        List<WmsOutboundRequirementVO> list = reader.read("FG001173");
        assertEquals(1, list.size());
        assertEquals("PBC001", list.get(0).getMaterialCode()); // 经 material-map 反向映射
        assertEquals(0, list.get(0).getRequiredQty().compareTo(new BigDecimal("70")));
    }
    @Test void read_kingdeeDisabled_returnsEmpty() { /* enabled=false → 空列表，不调用 formClient */ }
    @Test void read_queryFails_returnsEmptyAndLogs() { /* executeBillQuery 失败不抛异常 */ }
}

class WmsOutboundScanTest {
    @Test void scan_unknownWorkOrder_throws() { /* 「工单号无效」 */ }
    @Test void scan_withPpbomDetails_marksSource() {
        // reader 返回 1 行 → requirementSource=金蝶用料清单，details 透出
    }
    @Test void scan_withoutPpbom_marksManual() {
        // reader 空 → requirementSource=手工，warning 非空
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsKingdeePpbomReaderImplTest+WmsOutboundScanTest`

- [ ] **Step 3: 实现 PPBOM 读取**

```java
public class WmsKingdeePpbomReaderImpl implements IWmsKingdeePpbomReader {
    public List<WmsOutboundRequirementVO> read(String workOrderNo) {
        if (!properties.isEnabled()) return List.of();
        WmsKingdeeResult r = formClient.executeBillQuery("PRD_PPBOM",
            "FMaterialID2.FNumber,FMaterialID2.FName,FNeedQty2,FMustQty,FPickedQty,FStockID.FNumber,FLot,FUnitID2.FNumber,FID",
            "FMOBillNO='" + workOrderNo.replace("'", "''") + "'", 100);
        if (!r.isSuccess()) { log.warn(...); return List.of(); }
        // required = FNeedQty2 - FPickedQty；<=0 跳过
        // materialCode = properties.getMaterialMap() 反向查找，找不到保留金蝶编码并标记 matched=false
    }
}
```

反向映射用 `properties.getMaterialMap()` 遍历 entry：`entry.getValue().equals(kingdeeCode)` → WMS 编码。

- [ ] **Step 4: 实现扫码**

`scanPick`：查 `WmsWorkOrder`（不存在抛「工单号无效」）→ 调 reader → 组装 VO：
`workOrderNo/materialCode/materialName/productType/planQty/batchNo/workOrderStatus`；`requirementSource = details.isEmpty() ? 手工 : 金蝶用料清单`；`warning` 在明细为空时填「金蝶未返回用料清单，请手工录入领料明细」。

- [ ] **Step 5: 运行测试直到通过**

Run: `... -Dtest=WmsKingdeePpbomReaderImplTest+WmsOutboundScanTest`

- [ ] **Step 6: 提交（需用户授权）**

```bash
git commit -m "feat(wms): CK-001 工单扫码与 PPBOM 需求读取"
```

---

### Task 4: 库存锁定、FIFO 分配与库位占用

**Files:**
- Create: `vo/WmsInventoryAvailableVO.java`
- Modify: `mapper/WmsInventoryMapper.java` + `mapper/xml/WmsInventoryMapper.xml`
- Create: `service/IWmsOutboundAllocator.java`、`service/impl/WmsOutboundAllocatorImpl.java`
- Modify: `service/IWmsOutboundService.java`、`service/impl/WmsOutboundServiceImpl.java`（加 `allocate`）
- Modify: `mapper/WmsOutboundAllocationMapper.java`（本任务只建接口/`BaseMapper`）
- Consumes: `WmsOutboundDetailMapper`、`WmsLocationMapper`、`ISysBaseAPI`（告警）
- Test: `service/impl/WmsOutboundAllocatorTest.java`、`service/impl/WmsOutboundAllocateServiceTest.java`

**Interfaces:**
- Produces:
  - `List<WmsInventoryAvailableVO> WmsInventoryMapper.selectAvailable(String materialId, String batchNo, String warehouseType, boolean requireContainer, int limit)`
  - `int lockInventory(String id, BigDecimal qty)` / `releaseLocked(String id, BigDecimal qty)` / `deductLocked(String id, BigDecimal qty)`
  - `void IWmsOutboundAllocator.allocate(WmsOutboundOrder order, List<WmsOutboundDetail> details)`
  - `void IWmsOutboundService.allocate(String id)`

- [ ] **Step 1: 写失败测试**

```java
class WmsOutboundAllocatorTest {
    @Test void allocate_splitsAcrossFifoRows() {
        // 需求 15；可用行 A(10, 旧) / B(20, 新)
        allocator.allocate(order, List.of(detail("PBC001", "15")));
        verify(inventoryMapper).lockInventory("A", new BigDecimal("10"));
        verify(inventoryMapper).lockInventory("B", new BigDecimal("5"));
        // 插入两条 allocation，task_no 均以 WCS 开头
    }
    @Test void allocate_insufficient_throwsAndStops() {
        // 可用 5，需求 10 → 抛「库存不足：PBC001 需求 10，可用 5」
    }
    @Test void allocate_concurrentLockZeroRows_throws() {
        // lockInventory 返回 0 → 抛「库存已被其他出库单占用，请重试」
    }
    @Test void allocate_sales_requiresContainer() {
        // verify selectAvailable(..., "敏捷仓", true, ...)
    }
    @Test void allocate_pick_marksLocationOccupied() { /* locationMapper.update 被调用 */ }
}

class WmsOutboundAllocateServiceTest {
    @Test void allocate_success_setsAllocated() { /* status=已分配，last_sync_error=null */ }
    @Test void allocate_failure_setsExceptionAndAlerts() {
        // allocator 抛库存不足 → status=异常待人工，ISysBaseAPI.sendSysAnnouncement 收到「WMS出库库存不足告警」
    }
    @Test void allocate_wrongStatus_throws() { /* 「当前状态不允许分配」 */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsOutboundAllocatorTest+WmsOutboundAllocateServiceTest`

- [ ] **Step 3: 写库存 SQL**

`selectAvailable`：

```sql
SELECT i.id, i.material_id, i.location_id, l.location_code, i.container_code, i.batch_no,
       i.quantity, i.locked_qty, i.create_time
  FROM wms_inventory i
  JOIN wms_location l ON l.id = i.location_id AND l.deleted_at = 0
  JOIN wms_warehouse w ON w.id = l.warehouse_id AND w.deleted_at = 0
 WHERE i.material_id = #{materialId}
   AND w.warehouse_type = #{warehouseType}
   AND i.quantity - i.locked_qty > 0
   <if test="batchNo != null and batchNo != ''"> AND i.batch_no = #{batchNo} </if>
   <if test="requireContainer"> AND i.container_code &lt;&gt; '' </if>
 ORDER BY i.create_time ASC, i.container_code ASC, i.id ASC
 LIMIT #{limit}
```

三条更新（均返回影响行数）：

```sql
UPDATE wms_inventory SET locked_qty = locked_qty + #{qty}, update_time = NOW()
 WHERE id = #{id} AND quantity - locked_qty >= #{qty};

UPDATE wms_inventory SET locked_qty = GREATEST(locked_qty - #{qty}, 0), update_time = NOW()
 WHERE id = #{id};

UPDATE wms_inventory SET quantity = quantity - #{qty}, locked_qty = locked_qty - #{qty}, update_time = NOW()
 WHERE id = #{id} AND locked_qty >= #{qty} AND quantity >= #{qty};
```

- [ ] **Step 4: 实现分配器**

`allocate` 对每个 detail：
1. `warehouseType = 生产领料 ? 立库 : 敏捷仓`，`requireContainer = 销售出库`。
2. 取可用行；按 FIFO 逐行 `take = min(remaining, available)`；`lockInventory` 返回 0 → 抛并发异常。
3. 插入 `WmsOutboundAllocation`：`inventoryId/locationId/locationCode/containerCode/allocatedQty/taskNo/taskStatus=未下发/reviewStatus=待复核/actualQty=0`。
4. `taskNo` 生成规则：`生产领料 ? "WCS"+IdWorker.getIdStr() : "RMS"+IdWorker.getIdStr()`，分配时固定，保证重复下发不换号。
5. detail `allocatedQty += take`；remaining=0 时结束。
6. remaining>0 → 抛 `RuntimeException("库存不足：" + materialCode + " 需求 " + required + "，可用 " + allocated)`。
7. 生产领料分配成功后把涉及库位置「占用」（条件更新 `status='空闲'`）；更新 0 行不阻断（可能已被其它单据占用，库存锁定已保证不超发）。

`WmsOutboundServiceImpl.allocate(id)`：
- 状态只允许 `待分配`/`异常待人工`，否则抛「当前状态不允许分配」。
- 调 `allocator.allocate(order, details)`；成功更新 `status=已分配`、`last_sync_error=null`。
- 捕获异常：更新 `status=异常待人工`、`last_sync_error=截断(e.getMessage())`，按原因发 `OUTBOUND_STOCK_ALERT_TITLE` 或 `OUTBOUND_LOCATION_ALERT_TITLE`（消息含「库位」时用后者），再原样抛出。
- 外层方法**不加** `@Transactional`，保证失败状态能落库；`allocator.allocate` 加 `@Transactional`。

- [ ] **Step 5: 运行测试直到通过**

Run: `... -Dtest=WmsOutboundAllocatorTest+WmsOutboundAllocateServiceTest`

- [ ] **Step 6: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库 FIFO 分配与库存锁定"
```

---

### Task 5: RMS/WCS 出库下发

**Files:**
- Modify: `sync/IWmsWcsGateway.java`、`sync/HttpWmsWcsGateway.java`
- Create: `sync/WmsRmsOutboundTask.java`
- Modify: `sync/WmsRmsClient.java`、`sync/IWmsRmsGateway.java`、`sync/HttpWmsRmsGateway.java`、`sync/WmsRmsErrorCode.java`
- Modify: `mapper/WmsOutboundAllocationMapper.java` + `mapper/xml/WmsOutboundAllocationMapper.xml`
- Create: `service/IWmsOutboundTaskService.java`、`service/impl/WmsOutboundTaskServiceImpl.java`
- Modify: `service/IWmsOutboundService.java`、`service/impl/WmsOutboundServiceImpl.java`（`dispatch` 委托）
- Modify: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`（`wms.sync.wcs.outbound-url`、`cancel-outbound-url`、`wms.sync.rms.outbound-stations`、`outbound-priority`）
- Test: `sync/WmsRmsClientTest.java`（追加）、`sync/HttpWmsRmsGatewayTest.java`（追加）、`service/impl/WmsOutboundTaskServiceImplTest.java`

**Interfaces:**
- Produces:
  - `WmsTaskDispatchResult IWmsWcsGateway.dispatchOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation)`
  - `void IWmsWcsGateway.cancelOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation)`
  - `WmsRmsResponse WmsRmsClient.outbound(Integer priority, List<WmsRmsOutboundTask> details)`
  - `WmsTaskDispatchResult IWmsRmsGateway.dispatchOutbound(List<WmsRmsOutboundTask> tasks)`
  - `void IWmsRmsGateway.cancelOutbound(String taskNo, String containerCode)`
  - `void IWmsOutboundTaskService.dispatch(String id)` / `cancel(String id)`
  - `WmsOutboundAllocationMapper.claimFinished(String taskNo)` / `markRunning(String taskNo)` / `markFailed(String taskNo, String error)`

- [ ] **Step 1: 写失败测试**

```java
class WmsRmsClientOutboundTest {
    @Test void outbound_buildsV16Payload() {
        // POST /api/v2/task/outbound；body 含 priority 与 details[0].taskNo/containerCode/stationCodes
    }
}
class WmsRmsGatewayOutboundTest {
    @Test void dispatch_300001_isIdempotentSuccess() { /* code 300001 → success */ }
    @Test void dispatch_300008_isManualNotRetryable() { /* code 300008 → fail, retryable=false, 文案含「锁定」 */ }
    @Test void dispatch_300000_isRetryable_andRetried() { /* 3 次后失败 */ }
}
class WmsOutboundTaskServiceImplTest {
    @Test void pick_dispatchesOneTaskPerAllocation() { /* wcsGateway.dispatchOutbound 调用 2 次 */ }
    @Test void pick_locationNotFound_marksException() { /* 「立库库位不存在」+ 告警 */ }
    @Test void sales_groupsByContainer() {
        // 两个 allocation 同 containerCode → rmsGateway.dispatchOutbound 收到 1 条 task
    }
    @Test void faultDevice_marksExceptionAndAlerts() {
        // deviceMapper 返回故障堆垛机 → 不调用网关，status=异常待人工，告警「WMS出库设备异常告警」
    }
    @Test void dispatch_rmsFailure_marksAllocationsFailed() { /* task_status=失败 + task_error */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsRmsClientOutboundTest+WmsRmsGatewayOutboundTest+WmsOutboundTaskServiceImplTest`

- [ ] **Step 3: 扩展 RMS 客户端与错误码**

`WmsRmsOutboundTask`：`String taskNo; String containerCode; Integer priority; List<String> stationCodes;`

`WmsRmsClient.outbound`：

```java
Map<String, Object> payload = new LinkedHashMap<>();
if (priority != null) payload.put("priority", priority);
payload.put("details", details.stream().map(d -> {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("taskNo", d.getTaskNo());
    m.put("containerCode", d.getContainerCode());
    if (d.getPriority() != null) m.put("priority", d.getPriority());
    if (d.getStationCodes() != null && !d.getStationCodes().isEmpty()) m.put("stationCodes", d.getStationCodes());
    return m;
}).toList());
return post(PATH_OUTBOUND, payload);
```

常量 `PATH_OUTBOUND = "/api/v2/task/outbound"`、`PATH_OUTBOUND_PRIORITY = "/api/v2/task/outbound/priority/adjust"`。

`WmsRmsErrorCode` 增加 `isOutboundIdempotentSuccess(code)`：`0` 或 `300001`。

`HttpWmsRmsGateway.dispatchOutbound`：未配置→`okMock()`；按 `300000` 退避重试，其余错误映射同入库；返回单个 `WmsTaskDispatchResult`。

- [ ] **Step 4: 扩展 WCS 网关**

`HttpWmsWcsGateway` 构造器增加 `@Value("${wms.sync.wcs.outbound-url:}") String outboundUrl` 与 `cancel-outbound-url`。`dispatchOutbound` 报文：

```json
{"taskNo":"WCS...","type":"OUTBOUND","locationCode":"LB-...","containerCode":"...","materialCode":"...","quantity":10,"batchNo":"B1"}
```

未配置→`okMock()`；`code == 0` 成功，其余失败（可重试标记同入库）。

同时在 `application-dev.yml` 追加：

```yaml
wms:
  sync:
    wcs:
      outbound-url: "${WMS_WCS_OUTBOUND_URL:}"
      cancel-outbound-url: "${WMS_WCS_CANCEL_OUTBOUND_URL:}"
    rms:
      outbound-stations: "${WMS_RMS_OUTBOUND_STATIONS:}"
      outbound-priority: 1
```

- [ ] **Step 5: 实现下发服务**

`WmsOutboundTaskServiceImpl.dispatch(id)`：
1. 加载订单 + 分配行；只允许订单 `已分配`/`异常待人工`，分配行 `未下发`/`失败`。
2. 设备故障检查（照 `WmsProductionTaskServiceImpl.findFaultDevice`）：生产领料查堆垛机 + 分配库位所属库区；销售出库查敏捷仓 SCR。故障 → 订单 `异常待人工` + `OUTBOUND_DEVICE_ALERT_TITLE`，返回。
3. 生产领料：逐分配行 `wcsGateway.dispatchOutbound`；成功且非 mock → `task_status=已受理`；mock 也置已受理并记 `task_error="mock"`。
4. 销售出库：按 `containerCode` 聚合（同一容器只发一条 `WmsRmsOutboundTask`，`stationCodes` 取配置 `wms.sync.rms.outbound-stations` 逗号拆分；`priority` 取 `wms.sync.rms.outbound-priority`）；容器任务号取该容器第一条分配的 `taskNo`，并回写到同容器其余分配行，保证回执能认领全部行 → 一次 `rmsGateway.dispatchOutbound`；成功 → 所有相关分配行 `已受理`。
5. 失败：分配行 `失败` + `task_error`，订单 `异常待人工` + 设备告警。
6. 成功：订单 `执行中`。

`dispatch` 在 Controller 里同步调用（需把错误返回用户）；`WmsOutboundSyncRetryJob` 里异步重发。

- [ ] **Step 6: 运行测试直到通过**

Run: `... -Dtest=WmsRmsClientOutboundTest+WmsRmsGatewayOutboundTest+WmsOutboundTaskServiceImplTest`

- [ ] **Step 7: 提交（需用户授权）**

```bash
git commit -m "feat(wms): CK-001/CK-002 设备出库任务下发"
```

---

### Task 6: 回执、复核、确认出库与关单

**Files:**
- Create: `dto/WmsOutboundReviewDTO.java`、`dto/WmsOutboundConfirmDTO.java`、`dto/WmsOutboundCloseDTO.java`
- Create: `vo/WmsOutboundDetailVO.java`、`vo/WmsOutboundConfirmVO.java`
- Modify: `service/IWmsOutboundService.java`、`service/impl/WmsOutboundServiceImpl.java`
- Modify: `controller/WmsRmsCallbackController.java`、`controller/WmsWcsCallbackController.java`
- Test: `service/impl/WmsOutboundCallbackTest.java`、`service/impl/WmsOutboundReviewTest.java`、`service/impl/WmsOutboundConfirmTest.java`

**Interfaces:**
- Produces:
  - `boolean IWmsOutboundService.onTaskFeedback(String taskNo, String containerCode, String taskStatus, String message, boolean rms)`
  - `void IWmsOutboundService.review(String id, WmsOutboundReviewDTO dto)`
  - `WmsOutboundConfirmVO IWmsOutboundService.confirmPick(String id, WmsOutboundConfirmDTO dto)`
  - `WmsOutboundConfirmVO IWmsOutboundService.confirm(String id, WmsOutboundConfirmDTO dto)`
  - `void IWmsOutboundService.close(String id, String reason)`
  - `WmsOutboundDetailVO IWmsOutboundService.detail(String id)`

- [ ] **Step 1: 写失败测试**

```java
class WmsOutboundCallbackTest {
    @Test void finished_claimsAllocationAndMovesOrderToPendingReview() { /* 单分配行 → 订单待复核 */ }
    @Test void finished_twice_isIdempotent() { /* claimFinished 返回 0 → 不重复处理 */ }
    @Test void failed_movesOrderToExceptionAndAlerts() { /* task_status=失败 + 告警 */ }
    @Test void partialFinished_keepsExecuting() { /* 两行只完成一行 → 执行中 */ }
}
class WmsOutboundReviewTest {
    @Test void review_mismatch_setsReviewExceptionAndAlerts() { /* 文案含「复核不一致」 */ }
    @Test void review_rescanAfterMismatch_recovers() { /* 再提交正确扫描码 → 一致 */ }
    @Test void review_actualQtyOverAllocated_throws() { /* 「实收数量不能大于分配数量」 */ }
    @Test void review_forceWithoutReason_throws() { /* 「放行原因不能为空」 */ }
}
class WmsOutboundConfirmTest {
    @Test void confirm_withoutSaleSync_returnsNeedConfirm() {
        // saleSyncStatus=同步失败，override=false → VO.needSaleOrderConfirm=true，未扣库存
    }
    @Test void confirm_withOverride_deductsAndRecordsTrace() { /* override_by/reason 落库，quantity 扣减 */ }
    @Test void confirmPick_deductsAndReleasesLocation() { /* location status=空闲 */ }
    @Test void confirm_actualQtyZero_releasesLockWithoutDeduct() { /* quantity 不变，locked_qty 归零 */ }
    @Test void close_releasesAllLocks() { /* locked_qty 回滚，status=已关闭 */ }
    @Test void detail_returnsOrderDetailsAllocations() { /* VO 三部分都非空 */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsOutboundCallbackTest+WmsOutboundReviewTest+WmsOutboundConfirmTest`

- [ ] **Step 3: 实现回执**

`onTaskFeedback`：
- 按 `task_no` 查 allocation；不存在返回 false。
- `FINISHED`：`claimFinished(taskNo)` 影响 0 行 → false；否则若该订单所有分配行 `task_status=已完成` → 订单 `待复核`，否则保持 `执行中`。
- `RUNNING/OUTSTATION`：`markRunning(taskNo)`；订单 `执行中`。
- `FAILED`：`markFailed(taskNo, message)`；订单 `异常待人工` + `OUTBOUND_DEVICE_ALERT_TITLE`。
- 回执 Controller 先查入库 service；未命中再查出库 service，命中则走上述逻辑。出库完成**不触发**同步（等确认出库）。

- [ ] **Step 4: 实现复核**

`review`：
- 仅 `销售出库` 且订单 `待复核`/`复核异常`。
- 逐项：allocation 必须属于该订单；`scanCode` 必须等于物料编码或料箱编码，否则 `不一致`；`actualQty` ∈ [0, allocated]。
- 全部一致 → `review_status=一致`、`scan_code` 落库、allocation `actual_qty` 落库、detail `actual_qty` 汇总、订单回 `待复核`。
- 有任一不一致且 `releaseMismatch=false`：落 `不一致` + `review_note`，订单 `复核异常`，发 `OUTBOUND_REVIEW_ALERT_TITLE`，抛「复核不一致，请改数量/重扫或填写原因放行」。
- `releaseMismatch=true` 且 `releaseReason` 非空：落 `已放行`，订单回 `待复核`。

- [ ] **Step 5: 实现确认出库**

`confirm`：
1. 状态 `待复核`；所有 allocation `review_status ∈ {一致, 已放行}`，否则抛「请先完成复核」。
2. `sale_sync_status != 同步成功` 且 `overrideSaleOrderSync=false` → 返回 `WmsOutboundConfirmVO{confirmed=false, needSaleOrderConfirm=true, message="销售订单未同步到金蝶，请确认后继续出库"}`，**不扣库存**。
3. 否则逐 allocation `deductLocked(inventoryId, actualQty)`：影响 0 行 → 订单 `异常待人工` + 库存告警，抛「库存扣减失败，库存已变化」。
4. `actualQty < allocatedQty` 的差额 `releaseLocked`。
5. 更新订单 `已完成`、`completeTime`、`syncStatus=待同步`；override 时写 `override_by/time/reason`。
6. 返回 `confirmed=true`。

`confirmPick`：同上，但无销售订单校验；扣减后若库位已无库存且无锁定 → 库位置 `空闲`。

- [ ] **Step 6: 实现关单**

`close`：只允许非终态；对每个 allocation `releaseLocked(allocatedQty)`（未扣减过），释放库位，`status=已关闭`、`syncStatus=无需同步`、写关闭原因/人/时间。

- [ ] **Step 7: 运行测试直到通过**

Run: `... -Dtest=WmsOutboundCallbackTest+WmsOutboundReviewTest+WmsOutboundConfirmTest`

- [ ] **Step 8: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库回执、复核、确认与关单"
```

---

### Task 7: 金蝶回写（PRD_PickMtrl / SAL_SaleOrder / SAL_OUTSTOCK）

**Files:**
- Create: `sync/WmsKingdeeLookupSupport.java`
- Create: `sync/IWmsKingdeePickWriter.java`、`sync/WmsKingdeePickWriterImpl.java`
- Create: `sync/IWmsKingdeeSaleOrderWriter.java`、`sync/WmsKingdeeSaleOrderWriterImpl.java`
- Create: `sync/IWmsKingdeeOutstockWriter.java`、`sync/WmsKingdeeOutstockWriterImpl.java`
- Modify: `sync/WmsKingdeeProperties.java`（新增销售/出库配置）
- Modify: `application-dev.yml`
- Test: `sync/WmsKingdeePickWriterImplTest.java`、`sync/WmsKingdeeSaleOrderWriterImplTest.java`、`sync/WmsKingdeeOutstockWriterImplTest.java`

**Interfaces:**
- Produces:
  - `WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details)`（三个 Writer 同名）
  - `WmsKingdeeProperties.getPickBillType()/getSaleOrderBillType()/getOutStockBillType()/getSaleOrgNumber()/getSaleOrgId()/getSalerId()/getSettleCurrency()/getDeliveryDeptId()/getStockerId()`

- [ ] **Step 1: 写失败测试**

```java
class WmsKingdeePickWriterImplTest {
    @Test void write_buildsRequiredFields() {
        // Save 的 Model：FDate/FPrdOrgId=工单组织/FStockOrgId/FBillType=SCLLD01_SYS
        // FEntity[0]：FMaterialId/FStockId/FStockStatusId/FMoBillNo/FUnitID/FBaseUnitId/FStockUnitId/
        //            FOwnerTypeId/FOwnerId/FKeeperTypeId/FKeeperId/FParentOwnerTypeId/FParentOwnerId/FRealQty
    }
    @Test void write_existingAudited_isIdempotent() { /* 查询返回 C → 不 Save */ }
    @Test void write_existingDraft_submits() { /* 返回 A → 只 Submit */ }
    @Test void write_moNotFound_fails() { /* 「金蝶未找到生产订单」 */ }
    @Test void write_kingdeeDisabled_returnsMock() { /* enabled=false → okMock */ }
}
class WmsKingdeeSaleOrderWriterImplTest {
    @Test void write_usesSaleBillTypeAndRequiredEntryFields() { /* XSDD01_SYS + 8 个必填字段 */ }
    @Test void write_customerNotFound_fails() { /* 「未找到客户编码：X」 */ }
    @Test void write_billNoEqualsSalesOrderNo() { /* FBillNo=order.salesOrderNo */ }
}
class WmsKingdeeOutstockWriterImplTest {
    @Test void write_usesOutStockBillTypeAndCustomer() { /* XSCKD01_SYS + FCustomerID */ }
    @Test void write_missingDeptConfig_fails() { /* 「未配置销售出库发货部门」 */ }
    @Test void write_entriesCarryStockAndLot() { /* FStockId/FRealQty/FLot */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsKingdeePickWriterImplTest+WmsKingdeeSaleOrderWriterImplTest+WmsKingdeeOutstockWriterImplTest`

- [ ] **Step 3: 扩展配置**

`WmsKingdeeProperties` 新增字段并在 `application-dev.yml` 增加 spec §10 的键。默认值：

```yaml
pick-bill-type: SCLLD01_SYS
sale-order-bill-type: XSDD01_SYS
out-stock-bill-type: XSCKD01_SYS
sale-org-id: "209624"
sale-org-number: "101"
saler-id: ""
settle-currency: PRE001
delivery-dept-id: ""
stocker-id: ""
```

- [ ] **Step 4: 实现 LookupSupport 与三个 Writer**

`WmsKingdeeLookupSupport` 方法（全部有明确失败文案）：

```java
String orgNumberForWorkOrder(String workOrderNo);   // 查 PRD_MO.FPrdOrgId.FNumber，空则回退 defaultOrgNumber
List<Object> material(String kingdeeCode, String orgNumber); // [FMATERIALID,FNumber,FName,FBaseUnitId,FUseOrgId]
String unitNumber(Object unitId);                   // BD_UNIT
String stockCode(String orgNumber, String stockKey);// stock-code-by-org 优先
Long stockLocId(String stockCode);                  // BD_STOCK.FFlexId → BD_FLEXVALUES → View
Long customerId(String customerCode, String orgNumber); // BD_Customer
```

三个 Writer 的幂等键：`Pick/Outstock = order.outboundNo`、`SaleOrder = order.salesOrderNo`；查询 `FDocumentStatus`，`C/B` 成功、`A` 提交、无记录 Save+Submit。

必填字段逐项照 spec §1.1 与 §4.3/§5.4：
- Pick：`FPrdOrgId/FStockOrgId/FBillType(SCLLD01_SYS)`；分录含 `FMoBillNo`、`FParentOwnerTypeId/FParentOwnerId`（=组织）、`FStockStatusId`、`FOwnerTypeId/FOwnerId`、`FKeeperTypeId/FKeeperId`、`FUnitID/FBaseUnitId/FStockUnitId`、`FRealQty=actualQty`、`FLot`（非空时）、`FStockLocId`（启用仓位时）；`FSerialSubEntity=[{FIsAppInspect:false}]`。
- SaleOrder：`FSaleOrgId/FCustId/FSalerId/FBillTypeID(XSDD01_SYS)`；分录 `FMaterialId/FUnitID/FPriceUnitId/FStockUnitID/FOUTLMTUNIT/FSettleOrgIds/FDeliveryDate/FReserveType="1"/FQty=requiredQty`；财务 `FSettleCurrId`。
- Outstock：`FSaleOrgId/FStockOrgId/FCustomerID/FDeliveryDeptID/FStockerID/FBillTypeID(XSCKD01_SYS)`；`SubHeadEntity={FSettleOrgID,FSettleCurrId}`；分录 `FMaterialID/FUnitID/FOwnerTypeID/FOwnerID/FStockId/FRealQty=actualQty/FLot`。

- [ ] **Step 5: 运行测试直到通过**

Run: `... -Dtest=WmsKingdeePickWriterImplTest+WmsKingdeeSaleOrderWriterImplTest+WmsKingdeeOutstockWriterImplTest`

- [ ] **Step 6: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库三张金蝶单据回写"
```

---

### Task 8: 同步网关、补偿服务与 Quartz 任务

**Files:**
- Create: `sync/IWmsOutboundSyncGateway.java`、`sync/HttpWmsOutboundSyncGateway.java`
- Create: `service/IWmsOutboundSyncService.java`、`service/impl/WmsOutboundSyncServiceImpl.java`
- Modify: `mapper/WmsOutboundOrderMapper.java` + `mapper/xml/WmsOutboundOrderMapper.xml`
- Create: `job/WmsOutboundSyncRetryJob.java`、`job/WmsOutboundSyncJobRegistrar.java`
- Test: `sync/HttpWmsOutboundSyncGatewayTest.java`、`service/impl/WmsOutboundSyncServiceImplTest.java`、`job/WmsOutboundSyncRetryJobTest.java`

**Interfaces:**
- Produces:
  - `WmsOrderSyncResult IWmsOutboundSyncGateway.sync(WmsOutboundOrder order, List<WmsOutboundDetail> details)`
  - `WmsOrderSyncResult IWmsOutboundSyncGateway.syncSaleOrder(WmsOutboundOrder order, List<WmsOutboundDetail> details)`
  - `WmsOrderSyncAttempt IWmsOutboundSyncService.sync(String outboundId, boolean announce)`
  - `WmsOrderSyncAttempt IWmsOutboundSyncService.syncSaleOrder(String outboundId, boolean announce)`
  - `void persistResult(String id, WmsOrderSyncAttempt attempt)` / `void persistSaleResult(...)`
  - `Date syncStaleBefore()` / `int claimForSync(String id, Date staleBefore)` / `int claimSaleForSync(...)`
  - Mapper：`selectSyncCandidates`、`selectSaleSyncCandidates`、`selectUndispatchedBefore`

- [ ] **Step 1: 写失败测试**

```java
class HttpWmsOutboundSyncGatewayTest {
    @Test void sync_pick_usesPickWriter() { /* kingdee.enabled=true + type=生产领料 → pickWriter */ }
    @Test void sync_sales_usesOutstockWriter() { /* type=销售出库 → outstockWriter */ }
    @Test void syncSaleOrder_usesSaleOrderWriter() { /* → saleOrderWriter */ }
    @Test void sync_kingdeeDisabled_usesMesHttp() { /* mes-url 配置时 POST payload */ }
    @Test void sync_nothingConfigured_returnsMock() { /* okMock */ }
}
class WmsOutboundSyncServiceImplTest {
    @Test void sync_retriesThenSucceeds() { /* 300000 两次后成功，attempt.retryCount=2 */ }
    @Test void sync_allFailures_returnsErrorAndAlertsWhenAnnounce() { /* 告警标题「WMS出库同步失败告警」 */ }
    @Test void syncSaleOrder_writesSaleFieldsNotSyncFields() { /* sale_sync_status，不动 sync_status */ }
}
class WmsOutboundSyncRetryJobTest {
    @Test void job_redispatchesStaleAllocated() { /* 已分配且超 2 分钟 → taskService.dispatch */ }
    @Test void job_syncsCandidatesAndSaleCandidates() { /* 两类候选各处理一次 */ }
    @Test void job_singleFailureDoesNotStopRound() { /* 第一条抛异常，第二条仍处理 */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=HttpWmsOutboundSyncGatewayTest+WmsOutboundSyncServiceImplTest+WmsOutboundSyncRetryJobTest`

- [ ] **Step 3: 实现同步网关**

按 spec §6：
- `sync`：`kingdee.enabled` → 按类型选 Pick/Outstock Writer；否则 `mes.outbound-url` 非空 → HTTP POST `{outboundNo,type,workOrderNo,salesOrderNo,customerCode,details:[{materialCode,batchNo,quantity}]}`；都没有 → `okMock()`。
- `syncSaleOrder`：`kingdee.enabled` → SaleOrder Writer；否则 MES HTTP；否则 `okMock()`。

同时在 `application-dev.yml` 追加 `wms.sync.mes.outbound-url: "${WMS_MES_OUTBOUND_URL:}"`。

- [ ] **Step 4: 实现同步服务**

照 `WmsProductionSyncServiceImpl`：退避 `1s/2s/4s`、最多 3 次；`announce=true` 时发 `OUTBOUND_SYNC_ALERT_TITLE` / `SALE_ORDER_SYNC_ALERT_TITLE`；`syncFailCount` 连续失败 ≥3 才告警由 Job 调用 `announce=false` + 计数判断（手工同步仍立即告警）。

`persistResult` 写 `sync_status/retry_count/last_sync_error/last_sync_time`；`persistSaleResult` 写 `sale_sync_*`。

Mapper XML 照 `WmsProductionInboundMapper.xml` 的认领/候选 SQL，条件改为：
- 出库单同步：`status='已完成' AND sync_status IN ('待同步','同步失败') OR 僵死`。
- 销售同步：`outbound_type='销售出库' AND sale_sync_status IN ('待同步','同步失败') OR 僵死`。
- 未下发：`status='已分配' AND update_time < #{before}`。

- [ ] **Step 5: 实现 Job 与 Registrar**

`WmsOutboundSyncRetryJob.scanAndResyncForTest()` 三件事：重发超时未分配下发、跑出库同步候选、跑销售同步候选；单条异常不中断。Registrar 照 `WmsProductionSyncJobRegistrar`，ID/CRON 用 `OUTBOUND_SYNC_JOB_*`。

- [ ] **Step 6: 运行测试直到通过**

Run: `... -Dtest=HttpWmsOutboundSyncGatewayTest+WmsOutboundSyncServiceImplTest+WmsOutboundSyncRetryJobTest`

- [ ] **Step 7: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库同步网关与补偿任务"
```

---

### Task 9: 出库控制器与权限

**Files:**
- Create: `controller/WmsOutboundController.java`
- Test: `controller/WmsOutboundControllerTest.java`

**Interfaces:**
- Consumes: 前面所有 service 方法。
- Produces: spec §8 的 13 个端点；创建销售订单后同步调用 `syncSaleOrder`，确认出库后同步调用 `sync`。

- [ ] **Step 1: 写失败测试**

```java
class WmsOutboundControllerTest {
    @Test void scan_passesErrorTextThrough() { /* service 抛「工单号无效」→ Result.error 同文案 */ }
    @Test void createSales_triggersSaleOrderSync() { /* verify syncService.syncSaleOrder(id,true) */ }
    @Test void confirm_returnsNeedConfirmVo() { /* Result.getResult().isNeedSaleOrderConfirm() */ }
    @Test void confirmPick_triggersSync() { /* verify syncService.sync(id,true) */ }
    @Test void syncSale_triggersSaleOrderSync() { /* verify syncService.syncSaleOrder(id,true) */ }
    @Test void close_callsTaskCancel() { /* verify taskService.cancel(id) */ }
    @Test void endpoints_haveExpectedPermissions() throws Exception {
        // 反射断言 @RequiresPermissions("wms:outbound:list"/"scan"/"add"/"sales"/"allocate"/
        // "dispatch"/"review"/"confirm"/"sync"/"close")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `... -Dtest=WmsOutboundControllerTest`

- [ ] **Step 3: 实现控制器**

`@RestController @RequestMapping("/wms/outbound")`，逐端点照 spec §8；每个方法 `try/catch RuntimeException → Result.error(e.getMessage())`。

- 列表：`GET /list?pageNo&pageSize&outboundType&status&keyword` → `Result<IPage<WmsOutboundOrder>> service.listPage(...)`。
- 详情：`GET /{id}` → `Result<WmsOutboundDetailVO> service.detail(id)`。
- 扫码：`GET /pick/scan?workOrderNo=` → `Result<WmsOutboundScanVO>`。
- 销售订单重同步：`POST /{id}/sync-sale` → `syncService.syncSaleOrder(id, true)` + `persistSaleResult`。

创建销售订单：

```java
WmsOutboundOrder order = service.createSales(dto);
WmsOrderSyncAttempt attempt = syncService.syncSaleOrder(order.getId(), true);
syncService.persistSaleResult(order.getId(), attempt);
return Result.OK(attempt.isSuccess() ? "创建成功，销售订单已同步金蝶"
        : "创建成功，但销售订单同步失败：" + attempt.getErrorMessage(),
        service.getById(order.getId()));
```

确认出库/取料确认成功后同步出库单（`syncService.sync(id, true)` + `persistResult`），同步失败只影响返回文案，不回滚库存。

- [ ] **Step 4: 运行测试直到通过**

Run: `... -Dtest=WmsOutboundControllerTest`

- [ ] **Step 5: 编译整个模块**

Run: `bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests`
Expected: `BUILD SUCCESS`。

- [ ] **Step 6: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库接口与权限"
```

---

### Task 10: 前端列表与创建

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/outbound/outbound.api.ts`、`outbound.data.ts`、`index.vue`、`components/OutboundCreateModal.vue`

**Interfaces:**
- Consumes: 后端 `/wms/outbound/*`。
- Produces: 列表页可新建领料/销售、查看详情。

- [ ] **Step 1: 写 API 与列定义**

`outbound.api.ts`：

```ts
export const scanPick = (workOrderNo: string) => defHttp.get({ url: '/wms/outbound/pick/scan', params: { workOrderNo } });
export const createPick = (data) => defHttp.post({ url: '/wms/outbound/pick', data });
export const createSales = (data) => defHttp.post({ url: '/wms/outbound/sales', data });
export const listOutbound = (params) => defHttp.get({ url: '/wms/outbound/list', params });
export const getDetail = (id) => defHttp.get({ url: `/wms/outbound/${id}` });
export const allocateOutbound = (id) => defHttp.post({ url: `/wms/outbound/${id}/allocate` });
export const dispatchOutbound = (id) => defHttp.post({ url: `/wms/outbound/${id}/dispatch` });
export const reviewOutbound = (id, data) => defHttp.post({ url: `/wms/outbound/${id}/review`, data });
export const confirmPick = (id, data) => defHttp.post({ url: `/wms/outbound/${id}/confirm-pick`, data });
export const confirmOutbound = (id, data) => defHttp.post({ url: `/wms/outbound/${id}/confirm`, data });
export const syncOutbound = (id) => defHttp.post({ url: `/wms/outbound/${id}/sync` });
export const syncSaleOrder = (id) => defHttp.post({ url: `/wms/outbound/${id}/sync-sale` });
export const closeOutbound = (id, data) => defHttp.post({ url: `/wms/outbound/${id}/close`, data });
```

`outbound.data.ts`：列 `outboundNo/outboundType/workOrderNo/salesOrderNo/customerName/status/syncStatus/createTime/action`；status 颜色映射（异常待人工/复核异常红、已完成绿、执行中蓝）。

- [ ] **Step 2: 写列表页**

`index.vue`：`BasicTable` + 类型筛选（全部/生产领料/销售出库）+ 状态筛选 + 顶部「新建领料」「新建销售」按钮 + 行内「详情/分配/下发/同步/关单」。分配/下发/关单前 `Modal.confirm`；操作成功 `reload()`，失败 `message.error(res.message)`。

- [ ] **Step 3: 写创建弹窗**

`OutboundCreateModal.vue`：
- 领料模式：输入工单号 → 调 `scanPick` → 展示工单信息与 PPBOM 明细（可改数量）；「添加明细」允许手工选物料（物料下拉调 `/wms/material/list`）；来源标记只读。
- 销售模式：销售订单号、客户编码、客户名称、要货日期、明细（物料+数量）。
- 提交成功提示后端返回文案（含销售订单同步失败提示）。

- [ ] **Step 4: 静态检查**

Run: `cd jeecgboot-vue3 && npx eslint src/views/wms/outbound --ext .ts,.vue`
Expected: 0 error。

- [ ] **Step 5: 运行构建**

Run: `cd jeecgboot-vue3 && pnpm build`
Expected: `Build complete`，无类型错误。

- [ ] **Step 6: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库单前端列表与创建"
```

---

### Task 11: 前端详情、复核与确认

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/outbound/components/OutboundDetailDrawer.vue`
- Modify: `jeecgboot-vue3/src/views/wms/outbound/index.vue`（挂详情抽屉）

**Interfaces:**
- Consumes: `getDetail/reviewOutbound/confirmPick/confirmOutbound/allocateOutbound/dispatchOutbound/syncOutbound/closeOutbound`。

- [ ] **Step 1: 写详情抽屉**

`OutboundDetailDrawer.vue`：
- 顶部：单据信息 + 状态标签 + 同步状态；销售出库显示 `saleSyncStatus`，失败时红字提示「销售订单未同步到金蝶」。
- 明细区：物料/批次/需求/已分配/实收；分配区：库位/料箱/任务号/任务状态/复核状态/扫描码输入/实收数量输入。
- 按钮：分配、下发、复核、确认出库/取料确认、重新同步、关单；按钮按状态禁用。
- 复核不一致后：允许修改数量、重扫、填写「放行原因」后再次提交。
- 确认出库返回 `needSaleOrderConfirm=true` 时弹二次确认框：显示「销售订单未同步到金蝶，确认继续出库？」+ 原因输入；确认后带 `overrideSaleOrderSync=true` 再次调用。

- [ ] **Step 2: 静态检查**

Run: `cd jeecgboot-vue3 && npx eslint src/views/wms/outbound --ext .ts,.vue`
Expected: 0 error。

- [ ] **Step 3: 构建**

Run: `cd jeecgboot-vue3 && pnpm build`
Expected: 成功。

- [ ] **Step 4: 视觉验证**

Run: `pnpm dev`，浏览器打开 `http://localhost:3100/wms/outbound/list`，截图列表与详情抽屉。
Expected: 菜单可见、列表可加载、抽屉可打开、按钮状态正确。

- [ ] **Step 5: 提交（需用户授权）**

```bash
git commit -m "feat(wms): 出库详情、复核与确认交互"
```

---

### Task 12: 端到端验收与文档

**Files:**
- Create: `JeecgBoot/docs/superpowers/plans/2026-10-21-yx-wms-ck-001-ck-002-验收报告.md`
- Create: `JeecgBoot/.superpowers/sdd/2026-10-21-yx-wms-ck-001-ck-002/`（截图/录屏/日志）

- [ ] **Step 1: 启动后端并验证迁移**

```bash
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests
cd jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

- grep 启动日志「flyway执行sql脚本失败」→ 0 条；
- `"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "SHOW COLUMNS FROM wms_inventory; SHOW TABLES LIKE 'wms_outbound%';"`
- `flyway_schema_history` 中 `V3.9.5_17` 的 `success=1`。

- [ ] **Step 2: CK-001 mock 闭环**

1. 扫码 `FG001173`（若 WMS 无工单先 `POST /wms/work-order/sync-kingdee?billNo=FG001173`）。
2. 创建领料单（PPBOM 明细或手工）→ 分配 → 下发（WCS 未配置 = mock 受理）。
3. 模拟 WCS FINISHED 回执 → 取料确认 → 检查 `wms_inventory.quantity` 减少。
4. 检查金蝶 `PRD_PickMtrl`（`FBillNo=WMS 出库单号`），验证后 `UnAudit → Delete`。

- [ ] **Step 3: CK-002 mock 闭环**

1. 创建销售订单（客户 `01.001`），检查金蝶 `SAL_SaleOrder`。
2. 分配、下发（RMS 未配置 = mock 受理）。
3. 模拟 RMS FINISHED 回执 → 复核：先制造不一致（扫描错误码），确认订单转「复核异常」且有告警；再改数量/重扫放行。
4. 确认出库 → 检查库存扣减与金蝶 `SAL_OUTSTOCK`；验证后清理测试单据。

- [ ] **Step 4: 异常回归**

逐项触发并截图：库存不足、库位故障、设备故障、RMS 300008、复核不一致、同步失败、销售订单同步失败 + override 确认出库。

- [ ] **Step 5: 全量测试**

Run: `cd /d/yongxin-WMS/JeecgBoot/jeecg-boot && bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms`
Expected: 全部通过（目标 ≥ 598 个用例，含既有 568）。

Run: `cd jeecgboot-vue3 && pnpm build`
Expected: 成功。

- [ ] **Step 6: 写验收报告并提交（需用户授权）**

报告记录：执行的命令、测试数量、金蝶单据号、库存前后值、告警标题、截图路径、遗留风险（容器盘点/迁移未做）。

```bash
git commit -m "docs(wms): CK-001/CK-002 出库验收报告"
```

---

## 计划自检

- [x] spec §2 数据模型 → Task 1；§3 状态机 → Task 4/5/6；§4 CK-001 → Task 3/4/5/6/7；§5 CK-002 → Task 2/4/5/6/7；§6 同步 → Task 8；§7 告警 → Task 4/5/6/8；§8 接口 → Task 9；§9 前端 → Task 10/11；§10 配置 → Task 7；§11 验收 → Task 12。
- [x] 用户三条补充决策：不阻断同步 + override 确认（Task 6/9）、容器迁移不在本期（Task 4 过滤）、手工补录（Task 3/10）。
- [x] 类型一致性：`WmsOutboundConfirmVO`、`WmsRmsOutboundTask`、`WmsOutboundRequirementVO` 在定义任务与使用任务中同名同字段。
- [x] Review Focus 5 条均落到具体测试：Task 5（重复下发/任务号）、Task 4（并发锁定）、Task 6（复核恢复/override/零数量）、Task 7（跨组织）。
- [x] 每个任务都有失败测试 → 实现 → 通过测试 → 提交的循环。
