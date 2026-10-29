# KC-004 库存查询与报表导出 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给永鑫WMS 补上「库存台账」查询页与异步 Excel 导出，让仓管员能按物料/批次/库位查当前库存，让财务能月底导几十万行全量台账。

**Architecture:** 查询走一条四表 join 的 MyBatis XML（分页与导出分批共用同一段 SQL 与排序）；导出落 `wms_export_task` 任务表，由专用线程池在只读事务内用 AutoPoi `exportBigExcel` 按唯一键游标分批流式写 xlsx；任务只对导出人可见，7 天后由定时任务连记录带文件物理清理。

**Tech Stack:** Spring Boot 3.x / Java 17 / MyBatis-Plus / Shiro / AutoPoi 2.0.7（`ExcelExportUtil.exportBigExcel`）/ Quartz（JDBC 存储）/ Flyway / Vue 3 + Ant Design Vue 4。

**Spec:** `docs/superpowers/specs/2026-10-29-yx-wms-kc-004-inventory-query-export-design.md`

## Global Constraints

- 后端在 `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`，包 `org.jeecg.modules.wms`；Mapper XML 放 `src/main/java/org/jeecg/modules/wms/mapper/xml/`（**不是** resources）。
- 业务表统一 `wms_` 前缀；本次新表 `wms_export_task`。
- 接口一律返回 `Result<T>`（`Result.OK(...)` / `Result.error(...)`），写端点加 `@RequiresPermissions`，查询/导出加 `@AutoLog`。
- 拒绝文案**逐字透出**：controller 里自己 `try/catch → Result.error(e.getMessage())`，不要依赖全局异常处理器（它会加前缀）。
- 审计字段**没有 `MetaObjectHandler`**，一律用 `WmsUtils.fillCreate/fillUpdate`；线程池里**没有 Shiro Subject**，`create_by` 必须在 Controller 线程内定好传下去。
- 状态值/类型值全部提常量到 `WmsConstants`，禁止魔法值；数量和金额用 `BigDecimal`；日志用 SLF4J 占位符。
- 单测：JUnit 5 + Mockito，构造器注入，**不连数据库**（项目现有风格见 `WmsInventoryWarningServiceImplTest`）。
- 后端命令一律走 `bash /d/tools/m.sh -P dev ...`（`mvn` 不在 PATH）。单模块单测：
  `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=<TestClassName>`
- 改完 wms 模块必须 `install` 再重启后端，否则新接口 404（见 CLAUDE.md 本地运行约定）。
- 前端在 `jeecgboot-vue3`，别名 `/@/`，页面用 `a-card` + `a-table`（不用 `BasicTable`）。
- 提交信息 Conventional Commits + `wms` scope，从 `JeecgBoot` 仓库根提交：`feat(wms): ...`

## Review Focus

以下几类输入/失败模式，spec 隐含要求但容易被漏测，写进对应任务的测试里：

1. **导出线程没有登录人** —— 任务在后台线程更新时若去取 Shiro Subject 会拿到 null，`create_by` 变空后任务对所有人不可见。→ Task 4/6
2. **导出过程中"删任务""删文件"并发** —— 下载与清理撞上正在写的文件。→ Task 6/7/8
3. **长时间只读事务占连接** —— 导出跑几分钟把连接池吃光，正常业务接口超时。→ Task 6
4. **查询条件全空的首屏** —— 几十万行不带任何过滤的首屏分页。→ Task 3
5. **文件在下载前被清理掉** —— 用户点到下载时文件已不在，要给人话，不能 500。→ Task 7

---

## Task 1: Flyway 迁移脚本（建表 + 索引 + 菜单权限）

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_42__wms_inventory_ledger.sql`

**Interfaces:**
- Consumes: 无
- Produces: 表 `wms_export_task`（列见下）；`wms_inventory` 新索引 `idx_wms_inv_batch`；菜单/按钮权限 ID `1910000000000000701/0702/0711/0712`

- [ ] **Step 1: 建 `wms_export_task` 表 + 库存批次索引 + 菜单权限**

脚本分 5 段，照 `V3.9.5_37__wms_inventory_warning.sql` 的写法（`INSERT IGNORE`、`information_schema` 判存在）。

```sql
-- ================================================================
-- KC-004 库存查询与报表导出：导出任务表 + 库存批次索引 + 菜单权限
-- 设计：docs/superpowers/specs/2026-10-29-yx-wms-kc-004-inventory-query-export-design.md
-- ================================================================

-- ------------------------------------------------ 1) 导出任务表（通用，非库存专用）
CREATE TABLE IF NOT EXISTS `wms_export_task` (
  `id`            varchar(32)   NOT NULL COMMENT '主键',
  `task_type`     varchar(32)   NOT NULL COMMENT '任务类型：INVENTORY_LEDGER 库存台账',
  `query_params`  text          NOT NULL COMMENT '查询条件 JSON 快照（含格式版本）',
  `status`        varchar(16)   NOT NULL COMMENT '状态：PENDING 排队 / RUNNING 生成中 / SUCCESS 成功 / FAILED 失败',
  `file_name`     varchar(255)  DEFAULT NULL COMMENT '下载文件名',
  `file_path`     varchar(500)  DEFAULT NULL COMMENT '相对导出根目录的路径',
  `file_size`     bigint        DEFAULT NULL COMMENT '文件字节数',
  `row_count`     bigint        DEFAULT NULL COMMENT '导出数据行数',
  `snapshot_time` datetime      DEFAULT NULL COMMENT '一致性快照时点（事务开始时间）',
  `finish_time`   datetime      DEFAULT NULL COMMENT '完成时间',
  `expire_time`   datetime      DEFAULT NULL COMMENT '清理时点：任何终态完成时写 finish_time+7天',
  `error_msg`     varchar(1000) DEFAULT NULL COMMENT '失败原因（截断）',
  `create_by`     varchar(50)   DEFAULT NULL COMMENT '导出人账号',
  `create_time`   datetime      DEFAULT NULL COMMENT '提交时间',
  `update_by`     varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_export_owner` (`create_by`, `create_time`) USING BTREE,
  INDEX `idx_wms_export_status` (`status`) USING BTREE,
  INDEX `idx_wms_export_expire` (`expire_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='导出任务';

-- ------------------------------------------------ 2) wms_inventory 补批次号索引
-- 唯一键 (material_id, location_id, batch_no, container_code) 的第二列是 location_id，
-- 单独按 batch_no 过滤用不上；台账的批次检索要它。
SET @idx_inv_batch := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'idx_wms_inv_batch'
);
SET @ddl_inv_batch := IF(@idx_inv_batch = 0,
    'ALTER TABLE `wms_inventory` ADD INDEX `idx_wms_inv_batch` (`batch_no`) USING BTREE',
    'SELECT 1');
PREPARE stmt_inv_batch FROM @ddl_inv_batch;
EXECUTE stmt_inv_batch;
DEALLOCATE PREPARE stmt_inv_batch;

-- ------------------------------------------------ 3) 菜单：库存查询（sort=97）+ 库存台账
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000701','','库存查询','/wms/inventoryLedger','layouts/default/index',1,'','/wms/inventoryLedger/list',0,NULL,'0',97.00,0,'ant-design:profile-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000702','1910000000000000701','库存台账','/wms/inventoryLedger/list','wms/inventoryLedger/index',1,'','',1,NULL,'0',1.00,0,'ant-design:table-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 4) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000711','1910000000000000702','库存-查询',NULL,NULL,0,NULL,'',2,'wms:inventory:ledger','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000712','1910000000000000702','库存-导出',NULL,NULL,0,NULL,'',2,'wms:inventory:export','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 5) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000701','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000701',NULL,NOW(),'127.0.0.1'),
('1920000000000000702','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000702',NULL,NOW(),'127.0.0.1'),
('1920000000000000711','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000711',NULL,NOW(),'127.0.0.1'),
('1920000000000000712','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000712',NULL,NOW(),'127.0.0.1');
```

- [ ] **Step 2: 启动后端让 Flyway 应用脚本，然后确认表与索引**

先启动后端（`cd jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`）让它跑完迁移，再执行：

```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot --default-character-set=utf8mb4 yongxin_wms -e "SHOW CREATE TABLE wms_export_task\G" | head -40
```
Expected: 表存在，三个索引 `idx_wms_export_owner` / `idx_wms_export_status` / `idx_wms_export_expire` 都在。

- [ ] **Step 3: 校验迁移成功（Flyway 失败不会阻断启动，必须显式查）**

Run:
```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot --default-character-set=utf8mb4 yongxin_wms -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```
Expected: 最新一行 `3.9.5_42`、`success = 1`；同时启动日志里 grep「flyway执行sql脚本失败」计数为 0。

- [ ] **Step 4: Commit**

```bash
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_42__wms_inventory_ledger.sql
git commit -m "feat(wms): KC-004 建导出任务表与库存台账菜单"
```

---

## Task 2: 台账查询 VO、DTO 与 Mapper SQL

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/WmsInventoryLedgerVO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsInventoryLedgerQueryDTO.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsInventoryMapper.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/xml/WmsInventoryMapper.xml`

**Interfaces:**
- Consumes: Task 1 的表与索引
- Produces:
  - `IPage<WmsInventoryLedgerVO> selectLedgerPage(Page<WmsInventoryLedgerVO> page, @Param("q") WmsInventoryLedgerQueryDTO q)`
  - `List<WmsInventoryLedgerVO> selectLedgerCursor(@Param("q") WmsInventoryLedgerQueryDTO q, @Param("lastMaterialId") String, @Param("lastLocationId") String, @Param("lastBatchNo") String, @Param("lastContainerCode") String, @Param("limit") int)`
  - `WmsInventoryLedgerQueryDTO` getters: `getMaterialKeyword/getBatchNo/getLocationCode/getWarehouseId/getOnlyPositive/getPageNo/getPageSize`

- [ ] **Step 1: 写 `WmsInventoryLedgerVO`**

字段与 `@Excel` 列顺序（`orderNum` 决定导出列序，`id`/`firstInboundTime` 之外的都要导出）：

```java
public class WmsInventoryLedgerVO {
    private String id;                      // 行键，不导出
    @Excel(name = "物料编码", width = 20, orderNum = "1")  private String materialCode;
    @Excel(name = "物料名称", width = 25, orderNum = "2")  private String materialName;
    @Excel(name = "规格", width = 20, orderNum = "3")      private String spec;
    @Excel(name = "单位", width = 8,  orderNum = "4")      private String unit;
    @Excel(name = "批次号", width = 20, orderNum = "5")    private String batchNo;
    @Excel(name = "仓库编码", width = 15, orderNum = "6")  private String warehouseCode;
    @Excel(name = "仓库名称", width = 20, orderNum = "7")  private String warehouseName;
    @Excel(name = "仓库状态", width = 10, orderNum = "8", replace = {"启用_1", "停用_0"}) private String warehouseStatus;
    @Excel(name = "库位编码", width = 20, orderNum = "9")  private String locationCode;
    @Excel(name = "料箱/托盘", width = 18, orderNum = "10") private String containerCode;
    @Excel(name = "库存数量", width = 12, orderNum = "11") private BigDecimal quantity;
    @Excel(name = "锁定数量", width = 12, orderNum = "12") private BigDecimal lockedQty;
    @Excel(name = "可用量", width = 12, orderNum = "13")   private BigDecimal availableQty;
    @Excel(name = "首次入库时间", width = 20, orderNum = "14", format = "yyyy-MM-dd HH:mm:ss") private Date firstInboundTime;
    @Excel(name = "库龄(天)", width = 10, orderNum = "15") private Integer stockAgeDays;
    // 内部游标用，不导出
    private String materialId;
    private String locationId;
}
```

`materialId` / `locationId` 是游标推进用的，不加 `@Excel`。

- [ ] **Step 2: 写 `WmsInventoryLedgerQueryDTO`**

```java
public class WmsInventoryLedgerQueryDTO {
    private String materialKeyword;
    private String batchNo;
    private String locationCode;
    private String warehouseId;
    private Boolean onlyPositive = Boolean.TRUE;
    private Integer pageNo = 1;
    private Integer pageSize = 20;
}
```

- [ ] **Step 3: 在 `WmsInventoryMapper` 加两个方法签名**

```java
IPage<WmsInventoryLedgerVO> selectLedgerPage(Page<WmsInventoryLedgerVO> page,
                                             @Param("q") WmsInventoryLedgerQueryDTO q);

List<WmsInventoryLedgerVO> selectLedgerCursor(@Param("q") WmsInventoryLedgerQueryDTO q,
                                              @Param("lastMaterialId") String lastMaterialId,
                                              @Param("lastLocationId") String lastLocationId,
                                              @Param("lastBatchNo") String lastBatchNo,
                                              @Param("lastContainerCode") String lastContainerCode,
                                              @Param("limit") int limit);
```

- [ ] **Step 4: 在 `WmsInventoryMapper.xml` 加 `<sql>` 片段 + 两个 `<select>`**

排序与游标必须用唯一键 `uk_wms_inv_mat_loc_batch_container` 的四列（见 spec §3）。公共部分抽 `<sql id="ledgerFrom">`。

```xml
<sql id="ledgerFrom">
  FROM wms_inventory i
  JOIN wms_material  m ON m.id = i.material_id  AND m.deleted_at = 0
  JOIN wms_location  l ON l.id = i.location_id  AND l.deleted_at = 0
  JOIN wms_warehouse w ON w.id = l.warehouse_id AND w.deleted_at = 0
</sql>

<sql id="ledgerWhere">
  <where>
    <if test="q.onlyPositive != null and q.onlyPositive">
      AND i.quantity &lt;&gt; 0
    </if>
    <if test="q.materialKeyword != null and q.materialKeyword != ''">
      AND (m.material_code LIKE CONCAT('%', #{q.materialKeyword}, '%')
        OR m.material_name LIKE CONCAT('%', #{q.materialKeyword}, '%'))
    </if>
    <if test="q.batchNo != null and q.batchNo != ''">
      AND i.batch_no LIKE CONCAT('%', #{q.batchNo}, '%')
    </if>
    <if test="q.locationCode != null and q.locationCode != ''">
      AND l.location_code LIKE CONCAT('%', #{q.locationCode}, '%')
    </if>
    <if test="q.warehouseId != null and q.warehouseId != ''">
      AND w.id = #{q.warehouseId}
    </if>
  </where>
</sql>

<sql id="ledgerColumns">
  i.id, i.material_id AS materialId, i.location_id AS locationId,
  m.material_code AS materialCode, m.material_name AS materialName, m.spec, m.unit,
  i.batch_no AS batchNo,
  w.warehouse_code AS warehouseCode, w.warehouse_name AS warehouseName, w.status AS warehouseStatus,
  l.location_code AS locationCode, i.container_code AS containerCode,
  i.quantity, i.locked_qty AS lockedQty,
  (i.quantity - i.locked_qty) AS availableQty,
  i.first_inbound_time AS firstInboundTime,
  DATEDIFF(NOW(), i.first_inbound_time) AS stockAgeDays
</sql>
```

`selectLedgerPage`：`<include refid="ledgerColumns"/>` + `ledgerFrom` + `ledgerWhere` +
`ORDER BY i.material_id, i.location_id, i.batch_no, i.container_code`。

`selectLedgerCursor`：同列同 where，再加游标条件与 `LIMIT`：

```xml
<if test="lastMaterialId != null">
  AND (i.material_id, i.location_id, i.batch_no, i.container_code) &gt;
      (#{lastMaterialId}, #{lastLocationId}, #{lastBatchNo}, #{lastContainerCode})
</if>
ORDER BY i.material_id, i.location_id, i.batch_no, i.container_code
LIMIT #{limit}
```

两种返回类型都要能映射：MyBatis 用 `resultType` + `AS` 别名（`mapUnderscoreToCamelCase` 对 `first_inbound_time` 已生效，但游标列显式别名更稳）。

- [ ] **Step 5: 用真实库验证两个 SQL（Mapper XML 无法单测，必须实跑）**

准备 3 行可识别的测试数据后执行（把 `<...>` 换成实际条件）：

```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot --default-character-set=utf8mb4 yongxin_wms -e "
SELECT i.material_id, i.location_id, i.batch_no, i.container_code, i.quantity
  FROM wms_inventory i
  JOIN wms_material  m ON m.id = i.material_id  AND m.deleted_at = 0
  JOIN wms_location  l ON l.id = i.location_id  AND l.deleted_at = 0
  JOIN wms_warehouse w ON w.id = l.warehouse_id AND w.deleted_at = 0
 ORDER BY i.material_id, i.location_id, i.batch_no, i.container_code
 LIMIT 5;"
```
Expected: 返回按四列升序的行；再执行带游标条件 `AND (i.material_id, i.location_id, i.batch_no, i.container_code) > ('<上一批末行的四个值>')` 的那次，结果**严格接在上一批之后、无重复无遗漏**。

- [ ] **Step 6: 编译通过**

Run: `bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/{vo/WmsInventoryLedgerVO.java,dto/WmsInventoryLedgerQueryDTO.java,mapper/WmsInventoryMapper.java,mapper/xml/WmsInventoryMapper.xml}
git commit -m "feat(wms): KC-004 库存台账查询 SQL 与出参"
```

---

## Task 3: 台账查询 Service 与 Controller

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsInventoryLedgerService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsInventoryLedgerServiceImpl.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsInventoryLedgerController.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsInventoryLedgerQueryTest.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`

**Interfaces:**
- Consumes: `WmsInventoryMapper.selectLedgerPage`
- Produces:
  - `IWmsInventoryLedgerService.queryPage(WmsInventoryLedgerQueryDTO q)` → `IPage<WmsInventoryLedgerVO>`
  - `WmsInventoryLedgerQueryDTO normalize(WmsInventoryLedgerQueryDTO q)`（静态工具方法，供导出侧复用）

- [ ] **Step 1: 在 `WmsConstants` 加台账相关常量**

```java
// ==================== KC-004 库存台账 ====================
/** 台账分页上限：查询接口是给人看的，导出走异步任务。 */
public static final int INVENTORY_LEDGER_MAX_PAGE_SIZE = 200;
/** 导出每批从库里取的行数。 */
public static final int INVENTORY_LEDGER_EXPORT_BATCH_SIZE = 5000;
/** xlsx 单 sheet 数据行上限（含表头共 1048576）。 */
public static final int INVENTORY_LEDGER_EXCEL_MAX_ROWS = 1048575;
```

- [ ] **Step 2: 写失败测试 `WmsInventoryLedgerQueryTest`**

```java
class WmsInventoryLedgerQueryTest {

    @Test
    void pageSizeCapAt200() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageSize(5000);
        assertEquals(200, WmsInventoryLedgerServiceImpl.normalize(q).getPageSize());
    }

    @Test
    void pageNoAndPageSizeDefaultWhenNull() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageNo(null);
        q.setPageSize(null);
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertEquals(1, n.getPageNo());
        assertEquals(20, n.getPageSize());
    }

    @Test
    void pageNoBelowOneFallsBackToOne() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageNo(0);
        assertEquals(1, WmsInventoryLedgerServiceImpl.normalize(q).getPageNo());
    }

    @Test
    void onlyPositiveDefaultsTrue() {
        assertEquals(Boolean.TRUE,
                WmsInventoryLedgerServiceImpl.normalize(new WmsInventoryLedgerQueryDTO()).getOnlyPositive());
    }

    @Test
    void blankKeywordTrimmedToNull() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setMaterialKeyword("   ");
        q.setBatchNo("");
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertNull(n.getMaterialKeyword());
        assertNull(n.getBatchNo());
    }

    @Test
    void queryPagePassesNormalizedParamsToMapper() {
        WmsInventoryMapper mapper = mock(WmsInventoryMapper.class);
        when(mapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());
        WmsInventoryLedgerServiceImpl service = new WmsInventoryLedgerServiceImpl(mapper);
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageSize(9999);
        service.queryPage(q);
        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor = ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(mapper).selectLedgerPage(any(), captor.capture());
        assertEquals(200, captor.getValue().getPageSize());
    }

    @Test
    void emptyResultReturnsZeroTotalNotNullList() {
        WmsInventoryMapper mapper = mock(WmsInventoryMapper.class);
        Page<WmsInventoryLedgerVO> empty = new Page<>(1, 20);
        empty.setRecords(Collections.emptyList());
        when(mapper.selectLedgerPage(any(), any())).thenReturn(empty);
        IPage<WmsInventoryLedgerVO> page = new WmsInventoryLedgerServiceImpl(mapper)
                .queryPage(new WmsInventoryLedgerQueryDTO());
        assertEquals(0L, page.getTotal());
        assertNotNull(page.getRecords());
    }
}
```

`blankKeywordTrimmedToNull` 是 Review Focus #4 的配套：条件全空时不能拼出 `LIKE '%%'` 让索引失效、也不能让 `=""` 这种情况走到 SQL 里。

- [ ] **Step 3: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsInventoryLedgerQueryTest`
Expected: 编译失败 / `WmsInventoryLedgerServiceImpl` 不存在。

- [ ] **Step 4: 实现 Service（含 `normalize`）**

`normalize` 是 `public static`：pageNo/`pageSize` 空或越界时回落到 1/20，`pageSize` 夹到 `INVENTORY_LEDGER_MAX_PAGE_SIZE`，
`onlyPositive` 为 null 时取 true，字符串参数 `trim` 后空串转 null。
`queryPage` 先 `normalize`，再 `mapper.selectLedgerPage(new Page<>(pageNo, pageSize), q)`。

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 3
Expected: 7 个用例全绿。

- [ ] **Step 6: 写 Controller**

```java
@Tag(name = "WMS-库存台账")
@RestController
@RequestMapping("/wms/inventoryLedger")
public class WmsInventoryLedgerController {

    @AutoLog(value = "库存-台账查询", operateType = 1)
    @Operation(summary = "库存台账-分页查询")
    @GetMapping(value = "/page")
    @RequiresPermissions("wms:inventory:ledger")
    public Result<IPage<WmsInventoryLedgerVO>> page(WmsInventoryLedgerQueryDTO query) { ... }
}
```

查询参数用 GET 绑定（`pageNo`/`pageSize` 由 DTO 默认值兜底）。返回 `Result.OK(service.queryPage(query))`。

- [ ] **Step 7: 重启后端并用真实接口验证**

Run（先 `install` 再重启，见全局约束）：

```bash
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests
curl -s "http://localhost:8081/jeecg-boot/wms/inventoryLedger/page?pageNo=1&pageSize=5" -H "X-Access-Token: <登录拿到的 token>"
```
Expected: `{"success":true,...,"result":{"records":[...],"total":N}}`；`total` 与实际 `SELECT COUNT(*)` 一致。
用不存在的物料关键字再请求一次，Expected: `total: 0`、`records: []`。

- [ ] **Step 8: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/{service,dto,controller,constant} jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsInventoryLedgerQueryTest.java
git commit -m "feat(wms): KC-004 库存台账分页查询接口"
```

---

## Task 4: 导出任务实体、创建与任务列表

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsExportTask.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsExportTaskMapper.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsExportTaskService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsExportTaskServiceImpl.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/WmsExportProperties.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsExportExecutorConfig.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsExportTaskCreateTest.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsInventoryLedgerController.java`

**Interfaces:**
- Consumes: Task 1 的 `wms_export_task`；Task 3 的 `WmsInventoryLedgerQueryDTO`
- Produces:
  - `WmsExportTask`（字段同表）
  - `String IWmsExportTaskService.createLedgerExport(WmsInventoryLedgerQueryDTO q, String operator)` → 返回 taskId
  - `IPage<WmsExportTask> IWmsExportTaskService.pageMine(String operator, int pageNo, int pageSize)`
  - `WmsExportProperties.getDir()` → `String`；`getRetentionDays()` → `int`
  - Bean 名 `wmsExportExecutor`（`ThreadPoolTaskExecutor`）

- [ ] **Step 1: 在 `WmsConstants` 加导出常量**

```java
// ==================== KC-004 导出任务 ====================
public static final String EXPORT_TASK_TYPE_INVENTORY_LEDGER = "INVENTORY_LEDGER";
public static final String EXPORT_STATUS_PENDING = "PENDING";
public static final String EXPORT_STATUS_RUNNING = "RUNNING";
public static final String EXPORT_STATUS_SUCCESS = "SUCCESS";
public static final String EXPORT_STATUS_FAILED = "FAILED";
/** 导出文件保留天数。 */
public static final int EXPORT_RETENTION_DAYS = 7;
/** 错误信息落库长度上限。 */
public static final int EXPORT_ERROR_MSG_MAX_LEN = 1000;
/** 清理任务 ID 与 cron。 */
public static final String EXPORT_TASK_CLEAN_JOB_ID = "1920000000000000400";
public static final String EXPORT_TASK_CLEAN_JOB_CRON = "0 0 3 * * ?";
```

- [ ] **Step 2: 写 `WmsExportTask` 实体**

继承 `JeecgEntity`（自带 `id/createBy/createTime/updateBy/updateTime`），`@TableName("wms_export_task")`，
字段与 Task 1 的列一一对应：`taskType/queryParams/status/fileName/filePath/fileSize/rowCount/snapshotTime/finishTime/expireTime/errorMsg`。
**不加 `deleted_at`**（任务 7 天物理删除）。

- [ ] **Step 3: 写失败测试 `WmsExportTaskCreateTest`**

```java
@Test
void createPersistsPendingTaskWithOperator() {
    // mapper.insert 被调用一次；captured.status == PENDING、createBy == "zhangsan"、expireTime == null
}

@Test
void rejectsWhenSameUserHasRunningTask() {
    // mapper.selectCount 返回 1（该用户已有 PENDING/RUNNING）→ assertThrows(RuntimeException)
    // 异常文案 == "你已有导出任务正在进行，请等待完成后再提交"
    // 且 verify(mapper, never()).insert(any())
}

@Test
void rejectedByPoolMarksTaskFailed() {
    // executor.execute 抛 RejectedExecutionException
    // 断言：mapper.updateById 被调用，status == FAILED、
    //       errorMsg == "导出任务队列已满，请稍后重试"、
    //       finishTime != null、expireTime == finishTime + 7 天
}

@Test
void taskListForcesCurrentOperator() {
    // pageMine("zhangsan", 1, 10) → 捕获 LambdaQueryWrapper 用到的 create_by 值
    // 断言查询条件里 create_by 只有 "zhangsan"，不接受外部传入的账号
}

@Test
void queryParamsSerializedWithVersion() {
    // 捕获 insert 的 entity.queryParams，解析 JSON 后断言含 "v":1 与 materialKeyword 原值
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsExportTaskCreateTest`
Expected: 编译失败。

- [ ] **Step 5: 实现 `WmsExportProperties` 与线程池 Bean**

```java
@Data @Component @ConfigurationProperties(prefix = "wms.export")
public class WmsExportProperties {
    /** 导出文件根目录；空则用 jeecg.path.upload + "/wms-export"。 */
    private String dir = "";
    private int retentionDays = WmsConstants.EXPORT_RETENTION_DAYS;
    private Clean clean = new Clean();   // enabled 默认 true
    @Data public static class Clean { private boolean enabled = true; private String cron = WmsConstants.EXPORT_TASK_CLEAN_JOB_CRON; }
}
```

线程池 Bean：

```java
@Bean("wmsExportExecutor")
public ThreadPoolTaskExecutor wmsExportExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2); e.setMaxPoolSize(2); e.setQueueCapacity(20);
    e.setThreadNamePrefix("wms-export-");
    e.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    e.setWaitForTasksToCompleteOnShutdown(true);
    e.setAwaitTerminationSeconds(60);
    e.initialize();
    return e;
}
```

**不用 `@Async`**：项目里没有 `@EnableAsync`，加它是全局开关。

- [ ] **Step 6: 实现 `WmsExportTaskServiceImpl.createLedgerExport`**

顺序必须是：①按 `createBy` 查进行中任务（`in(PENDING, RUNNING)`）有则抛
`new RuntimeException("你已有导出任务正在进行，请等待完成后再提交")`；②`WmsUtils.fillCreate` 后 `insert`；
③`executor.execute(...)`，捕获 `RejectedExecutionException` → 更新为 FAILED（文案见测试）+ 抛
`new RuntimeException("导出任务较多，请稍后再试")`。

`operator` 由 **Controller 传入**（Controller 里 `WmsUtils.currentUsername()`），Service 内部不碰 Shiro。

- [ ] **Step 7: 跑测试确认通过**

Run: 同 Step 4
Expected: 5 个用例全绿。

- [ ] **Step 8: Controller 加两个端点**

```java
@AutoLog(value = "库存-台账导出", operateType = 1)
@PostMapping("/export")
@RequiresPermissions("wms:inventory:export")
public Result<String> export(@RequestBody WmsInventoryLedgerQueryDTO query) {
    try {
        return Result.OK("导出任务已提交", service.createLedgerExport(query, WmsUtils.currentUsername()));
    } catch (RuntimeException e) {
        return Result.error(e.getMessage());
    }
}

@GetMapping("/export/task/page")
@RequiresPermissions("wms:inventory:export")
public Result<IPage<WmsExportTask>> taskPage(@RequestParam(defaultValue = "1") Integer pageNo,
                                            @RequestParam(defaultValue = "10") Integer pageSize) { ... }
```

任务列表**必须**用 `WmsUtils.currentUsername()`，**不要**从请求参数取账号。

- [ ] **Step 9: Commit**

```bash
git add -A jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms
git commit -m "feat(wms): KC-004 导出任务表与创建接口"
```

---

## Task 5: 导出分页数据源（游标 + 行数上限）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsLedgerExportPageSource.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/exporter/WmsLedgerExportPageSourceTest.java`

**Interfaces:**
- Consumes: `WmsInventoryMapper.selectLedgerCursor`
- Produces: `WmsLedgerExportPageSource implements IExcelExportServer`，构造
  `(WmsInventoryMapper mapper, WmsInventoryLedgerQueryDTO query, int batchSize, int maxRows)`；
  `selectListForExcelExport(Object ctx, int page)` 返回 `List<Object>`，数据取完返回 `null`；
  `getExportedCount()` → `long`

- [ ] **Step 1: 写失败测试**

```java
@Test
void firstCallPassesNullCursorAndReturnsBatch() { /* mapper 返回 2 行 → 结果 size=2，捕获 lastMaterialId 为 null */ }

@Test
void secondCallPassesLastRowAsCursor() {
    // 第一次返回 [r1(m1,l1,b1,c1), r2(m2,l2,b2,c2)]，第二次调用时
    // 捕获的 lastMaterialId/lastLocationId/lastBatchNo/lastContainerCode 分别等于 r2 的四个值
}

@Test
void emptyBatchEndsIteration() { /* 第二次 mapper 返回空 → 返回 null */ }

@Test
void throwsWhenExceedingMaxRows() {
    // batchSize=2, maxRows=3；第一批返回 2 行、第二批返回 2 行 → 抛 IllegalStateException
    // 文案必须含 "Excel 单表上限"
}

@Test
void exportedCountAccumulates() { /* 两批 2+2 → getExportedCount() == 4 */ }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsLedgerExportPageSourceTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 `WmsLedgerExportPageSource`**

持有 `lastRow`、`exported`。每次调用：以 `lastRow` 的四列为游标调 `selectLedgerCursor`；
结果空则回 `null`；否则 `exported += size`，若 `exported > maxRows` 抛
`new IllegalStateException("导出行数超过 Excel 单表上限（" + maxRows + " 行），请缩小查询条件后分批导出")`；
把最后一行记为 `lastRow`，返回 `List<Object>`。

**行数检查必须在追加前做**——超限时不能先把行塞进工作簿再抛错。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2
Expected: 5 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add -A jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src
git commit -m "feat(wms): KC-004 导出游标分批数据源"
```

---

## Task 6: 导出执行器（只读事务 + AutoPoi 写入 + 终态回写）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsExcelWriter.java`（接口）
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/AutoPoiExcelWriter.java`（实现）
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsLedgerExportService.java`（`@Transactional(readOnly = true)` 的生成方法）
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsExportTaskRunner.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/exporter/WmsExportTaskRunnerTest.java`

**Interfaces:**
- Consumes: Task 5 的 `WmsLedgerExportPageSource`、Task 4 的 `WmsExportTaskMapper`
- Produces:
  - `interface WmsExcelWriter { File write(ExportParams params, Class<?> clazz, IExcelExportServer server, Object ctx, File target); }`
  - `WmsLedgerExportService.generate(WmsInventoryLedgerQueryDTO q, File target)` → `long rowCount`（**只读事务**）
  - `WmsExportTaskRunner.execute(String taskId, String operator)`（供线程池调用，无事务）

- [ ] **Step 1: 写失败测试 `WmsExportTaskRunnerTest`**

用 mock 的 `WmsExportTaskMapper`、`WmsExcelWriter`、`WmsLedgerExportService`。

```java
@Test
void successPathWritesFileMetaAndExpireTime() { /* status=SUCCESS，fileName/fileSize/rowCount/finishTime/expireTime 均非空 */ }

@Test
void failurePathWritesFailedStatusAndTruncatedError() {
    // service.generate 抛 RuntimeException(1500 字文案)
    // 断言 status=FAILED、errorMsg.length() == 1000、expireTime != null
}

@Test
void runnerNeverReadsShiroSubject() {
    // 用一个「subject 为 null」的场景跑成功路径：不抛异常即证明没依赖 Shiro
    // 同时断言 update id 用的是任务自己的 createBy
}

@Test
void tempFileRenamedOnlyOnSuccess() { /* 失败时 .part 文件被删除、最终文件名不存在 */ }

@Test
void missingTaskIsSkippedSilently() { /* taskId 查不到 → 不抛异常、不写文件 */ }
```

`runnerNeverReadsShiroSubject`、`tempFileRenamedOnlyOnSuccess` 分别对应 Review Focus #1 和 #2。

- [ ] **Step 2: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsExportTaskRunnerTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 `WmsExcelWriter` 与 `AutoPoiExcelWriter`**

```java
public interface WmsExcelWriter {
    File write(ExportParams params, Class<?> clazz, IExcelExportServer server, Object ctx, File target) throws IOException;
}
```

`AutoPoiExcelWriter` 里：

```java
Workbook wb = ExcelExportUtil.exportBigExcel(params, clazz, server, ctx);
try (OutputStream out = Files.newOutputStream(target.toPath())) {
    wb.write(out);
} finally {
    wb.close();
    if (wb instanceof SXSSFWorkbook sxssf) { sxssf.dispose(); }  // 必须，否则临时文件堆积
}
return target;
```

`ExportParams` 用 `new ExportParams("库存台账（快照 " + 时间 + "）", "永鑫WMS", "库存台账", ExcelType.XSSF)`。

- [ ] **Step 4: 实现 `WmsLedgerExportService.generate`**

方法体**必须**标 `@Transactional(readOnly = true)` 且在**独立 Bean** 里（同 Bean 自调用注解不生效）。
方法内：记录 `snapshotTime = new Date()`（返回给调用方回写任务）、构造 `WmsLedgerExportPageSource`、
调 `excelWriter.write(...)` 写 `target`、返回 `source.getExportedCount()`。

导出目录与文件命名放在这里：
`WmsExportProperties.getDir()` 为空时用 `jeecg.path.upload` + `/wms-export`；
文件名 `库存台账_yyyyMMddHHmmss_<taskId前8位>.xlsx`。

- [ ] **Step 5: 实现 `WmsExportTaskRunner.execute`**

流程：查任务 → 不存在直接返回 → 置 `RUNNING` → 解析 `query_params` 为 DTO → 生成到 `<taskId>.part`
→ 成功则改名 + 回写 `SUCCESS`（`fileName/fileSize/rowCount/snapshotTime/finishTime/expireTime`）
→ 任何 `Exception` 则删 `.part` + 回写 `FAILED`（`errorMsg` 截断 1000、`finishTime`、`expireTime`）。
更新一律走 `updateById`，`updateBy` 用任务自己的 `createBy`。

- [ ] **Step 6: 跑测试确认通过**

Run: 同 Step 2
Expected: 5 个用例全绿。

- [ ] **Step 7: 单机跑一次真导出（31 行现有数据）**

启动后端 → 登录 → `POST /wms/inventoryLedger/export` 带 `{}` → 轮询
`GET /wms/inventoryLedger/export/task/page`，Expected: 任务变 `SUCCESS`、`rowCount = 31`、
`filePath` 指向的文件真实存在且能打开、列头是中文且顺序符合 Task 2 的 `orderNum`。

- [ ] **Step 8: Commit**

```bash
git add -A jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src
git commit -m "feat(wms): KC-004 异步导出执行器与 AutoPoi 大导出"
```

---

## Task 7: 下载、重试、删除接口

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsExportFileResolver.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsExportTaskOperateTest.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsExportTaskService.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsExportTaskServiceImpl.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsInventoryLedgerController.java`

**Interfaces:**
- Consumes: Task 4 的 Service、Task 6 的 `WmsExportTaskRunner`
- Produces:
  - `WmsExportTask IWmsExportTaskService.requireOwned(String taskId, String operator)` → 不存在/非本人抛 `RuntimeException`
  - `void IWmsExportTaskService.retry(String taskId, String operator)`
  - `void IWmsExportTaskService.deleteTask(String taskId, String operator)`
  - `File WmsExportFileResolver.resolve(WmsExportTask task)` → 文件对象（不校验存在）

- [ ] **Step 1: 写失败测试 `WmsExportTaskOperateTest`**

```java
@Test void otherUserCannotDownload()      // createBy="lisi"，operator="zhangsan" → 抛 "无权访问该导出任务"
@Test void downloadFailedTaskRejected()   // status=FAILED → 抛 "任务未成功完成，无法下载"
@Test void downloadMissingFileGivesHumanMessage()  // 文件不存在 → 抛 "导出文件已过期或已被清理，请重新导出"
@Test void retryOnlyFromFailed()          // PENDING/RUNNING → "任务正在处理中"；SUCCESS → "任务已完成，请直接下载"
@Test void retryResetsToPending()         // FAILED → 状态回 PENDING、errorMsg/fileName/filePath/fileSize/rowCount 清空
@Test void deleteRejectedWhileRunning()   // PENDING/RUNNING → "任务处理中，无法删除"
@Test void deleteRemovesRecordAndFile()   // SUCCESS → deleteById 被调用；文件被删
```

`downloadMissingFileGivesHumanMessage` 对应 Review Focus #5。

- [ ] **Step 2: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsExportTaskOperateTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 Service 三个方法 + `WmsExportFileResolver`**

`requireOwned`：查不到或 `createBy` 不等于 `operator` 都抛「无权访问该导出任务」（**不区分**"不存在"和"别人的"，
避免探测他人任务 ID）。`retry` 复用 Task 4 的线程池提交逻辑（含拒绝时的回滚到 FAILED）。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2
Expected: 7 个用例全绿。

- [ ] **Step 5: Controller 加三个端点**

```java
@GetMapping("/export/task/{id}/download")
@RequiresPermissions("wms:inventory:export")
public void download(@PathVariable("id") String id, HttpServletResponse response) {
    try {
        WmsExportTask task = service.requireOwned(id, WmsUtils.currentUsername());
        if (!SUCCESS.equals(task.getStatus())) { responseError(response, "任务未成功完成，无法下载"); return; }
        File f = resolver.resolve(task);
        if (!f.exists()) { responseError(response, "导出文件已过期或已被清理，请重新导出"); return; }
        // 注意：FileDownloadUtils 的第一个 String 参数是**文件全路径**，不是目录
        FileDownloadUtils.downloadFile(response, f.getAbsolutePath(), task.getFileName());
    } catch (RuntimeException e) { responseError(response, e.getMessage()); }
}
```

`responseError` 把 `Result.error(msg)` 以 `application/json;charset=UTF-8` 写回——因为前端用
`handleExportXlsx` 走 blob，它会读响应体判断是不是错误 JSON。

`retry` / `delete` 是普通 `Result` 返回。

- [ ] **Step 6: 端到端验证"别人的任务下不下来"**

用 A 登录导出一次，拿 taskId；用 B 登录后 `GET /wms/inventoryLedger/export/task/<id>/download`，
Expected: 返回 `{"success":false,"message":"无权访问该导出任务"}`，HTTP 200 + JSON body。

- [ ] **Step 7: Commit**

```bash
git add -A jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src
git commit -m "feat(wms): KC-004 导出任务下载/重试/删除"
```

---

## Task 8: 清理定时任务与启动恢复

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/WmsExportTaskCleanJob.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/WmsExportTaskCleanJobRegistrar.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/exporter/WmsExportTaskRecovery.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/job/WmsExportTaskCleanJobTest.java`
- Create: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/exporter/WmsExportTaskRecoveryTest.java`

**Interfaces:**
- Consumes: `WmsExportTaskMapper`、`WmsExportProperties`、`WmsExportFileResolver`
- Produces: `WmsExportTaskCleanJob.execute(JobExecutionContext)`；`WmsExportTaskRecovery.onStartup()`（`@EventListener(ApplicationReadyEvent.class)`）

- [ ] **Step 1: 写失败测试**

`WmsExportTaskCleanJobTest`：
```java
@Test void deletesRecordsPastExpireTime()      // 只删 expire_time < now 的
@Test void keepsUnexpiredRecords()             // 未过期的（含 PENDING 无 expireTime 的）不删
@Test void deletesFilesBeforeRecords()         // 先删文件再删记录；文件删除失败也要继续删记录
@Test void missingFileIsNotAnError()           // 文件已不存在 → 不抛异常
```

`WmsExportTaskRecoveryTest`：
```java
@Test void runningBecomesFailed()              // RUNNING → FAILED，errorMsg=="服务重启，任务中断，请重试"
@Test void pendingBecomesFailed()              // PENDING → FAILED，文案同上
@Test void recoverySetsFinishAndExpireTime()   // 两个字段均被写
@Test void noTasksMeansNoUpdate()              // 查不到进行中任务时不发生 update/delete
```

- [ ] **Step 2: 跑测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsExportTaskCleanJobTest,WmsExportTaskRecoveryTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现清理 Job 与恢复器**

清理：查 `expire_time < now` → 逐条删文件（`Files.deleteIfExists`，异常只记 warn）→ 批量删记录。
按批处理（每批 200 条）避免一次拉全表。

恢复：`ApplicationReadyEvent` 里把 `RUNNING`/`PENDING` 全置 `FAILED`，补 `finishTime=now`、`expireTime=now+7d`。

- [ ] **Step 4: 实现 `WmsExportTaskCleanJobRegistrar`**

照抄 `WmsInventoryWarningScanJobRegistrar`：开关 `wms.export.clean.enabled`，关闭时
`scheduler.deleteJob(jobKey)` **主动摘除**（Quartz 是 JDBC 存储，只改开关不摘除会照跑）；
注册前用 `WmsJobRegistrarSupport.isScheduled` 判断残留；异常只 `log.warn` 不阻断启动。

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 2
Expected: 8 个用例全绿。

- [ ] **Step 6: 手工验证清理**

```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "UPDATE wms_export_task SET expire_time = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE status='SUCCESS' LIMIT 1;"
```
重启后端 → 手动触发清理 Job（或等 cron）→ Expected: 该行消失、`file_path` 指向的文件也不在了。

- [ ] **Step 7: Commit**

```bash
git add -A jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src
git commit -m "feat(wms): KC-004 导出任务清理与重启恢复"
```

---

## Task 9: 前端库存台账页面

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/inventoryLedger/inventoryLedger.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/inventoryLedger/inventoryLedger.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/inventoryLedger/index.vue`

**Interfaces:**
- Consumes: 后端 `/wms/inventoryLedger/page`、`/export`、`/export/task/page`、`/export/task/{id}/download`、`/export/task/{id}/retry`、`/export/task/{id}`；既有 `/wms/warehouse/list`
- Produces: 页面组件 `wms/inventoryLedger/index`（菜单已在 Task 1 指向它）

- [ ] **Step 1: 写 `inventoryLedger.api.ts`**

```ts
enum Api {
  page = '/wms/inventoryLedger/page',
  exportTask = '/wms/inventoryLedger/export',
  taskPage = '/wms/inventoryLedger/export/task/page',
  taskBase = '/wms/inventoryLedger/export/task',
}
export const queryLedger = (params) => defHttp.get({ url: Api.page, params });
export const createExportTask = (params) => defHttp.post({ url: Api.exportTask, params });
export const queryTaskPage = (params) => defHttp.get({ url: Api.taskPage, params });
export const retryTask = (id: string) => defHttp.post({ url: `${Api.taskBase}/${id}/retry` });
export const deleteTask = (id: string) => defHttp.delete({ url: `${Api.taskBase}/${id}` });
```

下载**不走**这里的函数，直接用 `useMethods().handleExportXlsx(name, url, params)`——它已处理 blob、
文件名后缀和「响应体是错误 JSON」的判定。

- [ ] **Step 2: 写 `inventoryLedger.data.ts`**

`columns` 与后端 `orderNum` 一致：物料编码/名称/规格/单位/批次号/仓库编码/仓库名称/**仓库状态**（tag 渲染）/
库位编码/料箱托盘/库存数量/锁定数量/可用量/首次入库时间/库龄(天)。

`taskColumns`：状态（tag）/行数/提交时间/完成时间/过期时间/失败原因/操作。

状态色：`PENDING`+`RUNNING` 蓝、`SUCCESS` 绿、`FAILED` 红。
仓库状态：`1` 绿「启用」、`0` 灰「停用」。

- [ ] **Step 3: 写 `index.vue`**

结构见 spec §8。要点逐条落实：

- 查询按钮 `v-auth="'wms:inventory:ledger'"`，导出与任务操作 `v-auth="'wms:inventory:export'"`。
- 仓库下拉用 `/wms/warehouse/list`，**包含停用仓**，文案 `编码 名称（停用）`。
- 表格空态：`total === 0` 且已查询过 → `未找到匹配的库存记录`；未查询 → `请设置查询条件后点击查询`。
- 点导出 → `createMessage.success('导出任务已提交，可在下方任务列表查看进度')`，然后刷新任务列表。
- 任务列表轮询：`PENDING`/`RUNNING` 存在时 `setInterval` 5 秒刷新；**`onUnmounted` 里必须
  `clearInterval`**，全部终态时也要主动停。
- 下载：`handleExportXlsx(row.fileName, '/wms/inventoryLedger/export/task/' + row.id + '/download')`。
- 过期行：`expireTime < now` 时下载按钮 `disabled` + tooltip「文件已过期（7 天）」。
- 删除前 `Modal.confirm`。

- [ ] **Step 4: 通过类型检查与 lint**

Run: `cd jeecgboot-vue3 && npx eslint src/views/wms/inventoryLedger --ext .ts,.vue`
Expected: 无 error（warning 可接受）。

- [ ] **Step 5: 构建通过**

Run: `cd jeecgboot-vue3 && pnpm build`
Expected: 构建成功，无与 `inventoryLedger` 相关的报错。

- [ ] **Step 6: 浏览器实测**

`pnpm dev`（3100）→ 登录 → 菜单「库存查询 / 库存台账」：
1. 空条件查询 → 有数据；
2. 输入不存在的物料 → 「未找到匹配的库存记录」；
3. 按仓库筛选 → 结果里仓库状态列正确；
4. 点导出 → 出现「排队中/生成中」任务 → 转「成功」后可下载，文件能打开且列头中文正确；
5. 切到别的页面再回来 → 确认没有残留的轮询定时器（Network 面板无持续请求）。

- [ ] **Step 7: Commit**

```bash
git add jeecgboot-vue3/src/views/wms/inventoryLedger
git commit -m "feat(wms): KC-004 库存台账前端页面"
```

---

## Task 10: 大数据量验证与验收报告

**Files:**
- Create: `docs/superpowers/plans/2026-10-29-yx-wms-kc-004-验收报告.md`

- [ ] **Step 1: 造 30 万行库存数据**

在**测试库**用递归 CTE 或存储过程把 `wms_inventory` 复制到 30 万行（每行改 `id`、`batch_no`、
`container_code` 保证唯一键不冲突），记下实际行数。

- [ ] **Step 2: 全量导出并观测**

导出全量 → 记录：任务耗时、`rowCount` 是否等于表内行数、文件大小、**导出期间 JVM 堆峰值不逼近上限**、
导出期间普通查询接口仍能在 1 秒内返回（Review Focus #3）。

- [ ] **Step 3: 并发与归属验证**

- 同一账号连点导出 3 次 → 第 2、3 次返回「你已有导出任务正在进行」。
- A 导出、B 下载 → 返回「无权访问该导出任务」。

- [ ] **Step 4: 清理与恢复验证**

- 把某成功任务的 `expire_time` 改成昨天 → 跑清理 Job → 记录与文件都消失。
- 导出进行中重启后端 → 任务变 FAILED 且可重试成功。

- [ ] **Step 5: 全量单测回归**

Run: `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms`
Expected: 全绿（基线 863 例 + 本次新增约 30 例）。

- [ ] **Step 6: 写验收报告**

按既有格式（参照 `2026-10-28-yx-wms-inventory-warning-batch-验收报告.md`）写：
做了什么、接口清单、DDL、测试命令与实际输出、遗留问题、下一步。

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/plans/2026-10-29-yx-wms-kc-004-验收报告.md
git commit -m "docs(wms): KC-004 验收报告"
```
