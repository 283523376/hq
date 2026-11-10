# YX-WMS-BD-003 库区/库位与设备绑定 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在永鑫 WMS 运行时中实现蓝图流程 `YX-WMS-BD-003`：库区 CRUD、按规则批量生成库位、库位属性维护（有物料时禁改）、设备归属库区绑定、设备离线告警三链路。

**Architecture:** 后端在既有 `jeecg-boot-module-wms` 内新增三组 CRUD（库区/库位/设备），完全对齐既有 `WmsMaterial` / `WmsSupplier` 范式；库位编码规则抽成无依赖的纯工具类 `WmsLocationCodeGenerator`，使其可被穷举测试且与库区配置解耦；批量生成用 `INSERT IGNORE` 做幂等而非长事务；设备离线告警复用 BD-002 已验证的 `ISysBaseAPI.sendSysAnnouncement` 链路，由定时任务注册器自注册。

**Tech Stack:** JeecgBoot 3.9.5 / Spring Boot 4.1.0 / Java 17 / MyBatis-Plus 3.5.16 / Shiro+JWT / MySQL 8 / Redis / Flyway / Quartz / Knife4j / JUnit 5 + Mockito / Vue 3 + Vben Admin / pnpm

**Spec:** `docs/superpowers/specs/2026-10-15-yx-wms-bd-003-warehouse-location-device-design.md`

## Global Constraints

- **构建命令（本机 mvn 不在 PATH）**：一律 `bash /d/tools/m.sh …`。根 pom 同时 `activeByDefault` 了 `dev` 与 `SpringCloud`，**必须显式 `-P dev`**，否则反应堆会拉进 `jeecg-server-cloud` 且 `@profile.name@` 解析不了。
- **测试默认被跳过**：只要跑测试就必须带 `-DskipTests=false`。
- 业务代码统一在包 `org.jeecg.modules.wms` 下；**业务表一律 `wms_` 前缀**。
- 所有接口统一返回 `Result<T>`（`org.jeecg.common.api.vo.Result`）；控制器继承 `JeecgController<Entity, IService>`。
- 实体：`@Data @EqualsAndHashCode(callSuper = false) @Accessors(chain = true) @TableName("wms_xxx")`，继承 `JeecgEntity`。
- **本项目未启用 MyBatis-Plus 自动填充**（无 `MetaObjectHandler`）：新增用 `WmsUtils.fillCreate(entity)`、更新用 `WmsUtils.fillUpdate(entity)`，**必须显式调用**。
- Mapper XML 放 `src/main/java/org/jeecg/modules/wms/mapper/xml/`（不是 `resources`）；`mapper-locations` 已在全部 profile 配置好，**不要改 yml**。
- 禁止魔法值，一律提取到 `WmsConstants`；日志用 SLF4J 占位符 `log.info("…{}", arg)`，禁止字符串拼接。
- 枚举列用**中文字面量**存储（与既有 `category_type` / `order_type` 一致），不引数据字典。
- **库位编码段宽只由库位自身数字决定**：`max(2, len(value))`，**只补不截**，**不得引用库区维度**（spec §5.2，本轮修正的核心）。
- **库区维度可自由增减，不得加任何守卫**（spec §5.2）。
- 测试风格：JUnit 5 + Mockito，`spy(new XxxServiceImpl())` + `WmsTestSupport.inject(...)`，**不启 Spring 上下文、不连库**。
- 前端路径 `/@/…` 为 Vben 别名；页面照 `src/views/wms/material/index.vue` 同构。

## Review Focus

以下 5 类输入/失败模式，spec 隐含要求但**没有任何单测天然覆盖**，最可能咬到使用者。每条都在「归属任务」里指定了对应测试：

1. **库区编码含超长或特殊字符** → 拼出的 `location_code` 超过 `varchar(64)` 被截断/丢弃，而 `INSERT IGNORE` 会**静默吞掉**这行 → 用户看到「生成 0 个」却不知道原因。（Task 5）
2. **`/wms/location/list` 传一个不存在的 `warehouseType`** → 库区 id 集合为空 → MyBatis-Plus 拼出 `IN ()` → SQL 语法错误 500，而不是「无匹配记录」。（Task 7）
3. **设备 `warehouse_id` 指向已逻辑删除的库区** → 列表回填库区名、解绑、绑定校验都可能 NPE。（Task 9）
4. **`/reportStatus` 传不存在的 `deviceCode`** → 应返回明确错误，而不是 NPE 500。（Task 9）
5. **同一库区并发两次 `/generate` 同一范围** → 唯一索引 + `INSERT IGNORE` 必须保证最终条数正确且两次都返回成功（不抛重复键异常）。（Task 5）

---

## 文件结构

### 后端新增（`jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/`）

| 路径 | 职责 |
| --- | --- |
| `util/WmsLocationCodeGenerator.java` | **纯函数**：库位编码生成。不依赖 Spring、不依赖库区配置 |
| `entity/WmsWarehouse.java` `WmsLocation.java` `WmsDevice.java` | 三张表的实体 |
| `dto/WmsLocationGenDTO.java` `WmsLocationAttrDTO.java` `WmsDeviceBindDTO.java` `WmsDeviceStatusDTO.java` | 入参 |
| `vo/WmsLocationGenResultVO.java` `WmsDeviceBindResultVO.java` | 出参 |
| `mapper/WmsWarehouseMapper.java` `WmsLocationMapper.java` `WmsDeviceMapper.java` | `BaseMapper` |
| `mapper/xml/WmsLocationMapper.xml` | 唯一一条自定义 SQL：`insertIgnoreBatch` |
| `service/I*.java` + `service/impl/*ServiceImpl.java` | 4 组服务（库区/库位/设备/告警） |
| `job/WmsDeviceOfflineScanJob.java` + `WmsDeviceOfflineScanJobRegistrar.java` | 离线扫描 + 启动自注册 |
| `controller/WmsWarehouseController.java` `WmsLocationController.java` `WmsDeviceController.java` | REST |

### 后端修改

| 路径 | 改动 |
| --- | --- |
| `constant/WmsConstants.java` | 追加库区类型 / 库位状态 / 设备类型 / 设备状态 / 生成上限 / 离线阈值与节流 / 扫描任务常量 |

### 后端测试（`jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/`）

`util/WmsLocationCodeGeneratorTest.java`、`service/impl/WmsWarehouseServiceImplTest.java`、
`service/impl/WmsLocationServiceImplTest.java`、`service/impl/WmsDeviceServiceImplTest.java`、
`service/impl/WmsDeviceAlertServiceImplTest.java`、`job/WmsDeviceOfflineScanJobTest.java`、
`controller/WmsWarehouseControllerTest.java`、`controller/WmsLocationControllerTest.java`、
`controller/WmsDeviceControllerTest.java`

### Flyway

`jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_5__wms_warehouse_location_device.sql`

### 前端新增（`jeecgboot-vue3/src/views/wms/`）

| 路径 | 职责 |
| --- | --- |
| `warehouse/index.vue` | 左树右表：库区树 + 库位列表 + 越界提示条 |
| `warehouse/warehouse.api.ts` `warehouse.data.ts` | 库区接口与列/表单定义 |
| `warehouse/location.api.ts` `location.data.ts` | 库位接口与列/表单定义 |
| `warehouse/components/WarehouseTree.vue` | 两层库区树 + 库区增删改 |
| `warehouse/components/WarehouseModal.vue` | 库区表单（含维度） |
| `warehouse/components/GenerateModal.vue` | 批量生成（实时预计数 + 超限禁用） |
| `warehouse/components/LocationAttrModal.vue` | 库位属性（承重/状态） |
| `warehouse/components/LocationModal.vue` | 单个库位新增/编辑 |
| `device/index.vue` `device.api.ts` `device.data.ts` | 设备管理页 |
| `device/components/DeviceModal.vue` | 设备表单 |
| `device/components/DeviceBindModal.vue` | 设备绑定库区 |

---

## 任务依赖图

```
T1(迁移+常量)
 └─► T2(数据层: 三实体 + 三 Mapper + insertIgnoreBatch SQL)
      ├─► T3(编码生成器, 纯函数) ──────────────┐
      ├─► T4(库区服务+控制器) ─────────────────┤
      └─► T5(库位服务: generate 守卫/分批/幂等) ─┤ (T3 与 T5 都产出编码)
            T6(库位服务: CRUD/属性/释放/删除守卫)
            T7(库位控制器 + 库区越界计数)
            T8(告警服务) ─► T9(设备服务) ─► T10(设备控制器) ─► T11(离线扫描 Job+注册器)
            T12(前端 库区与库位页) ─► T13(前端 设备页) ─► T14(E2E 验收)
```

依赖要点：
- **T3 必须早于 T5**（T5 调 `WmsLocationCodeGenerator.locationCode(...)`）。
- **T4 必须晚于 T2**（`removeWarehouse` 要查 `wms_location` / `wms_device` 是否引用该库区）。
- **T6 必须晚于 T5**（同一个 Service 类，T6 在 T5 已建的文件上加方法）。
- **T7 必须晚于 T4 与 T6**（要同时改库位控制器与库区控制器的越界计数）。
- **T8 必须早于 T9**（T9 依赖告警服务的节流判定）。
- T12 / T13 只依赖接口已定（T1–T11），可与后端并行，但建议串行以便用真实接口联调。

---

## Task 1: 数据库迁移脚本与常量

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_5__wms_warehouse_location_device.sql`
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: 表 `wms_warehouse` / `wms_location` / `wms_device`；常量
  `WAREHOUSE_TYPE_STEREO/AGILE/LINE_SIDE`、`LOCATION_STATUS_IDLE/OCCUPIED/FAULT`、
  `DEVICE_TYPE_STACKER/SCR/SORTING_UNIT/CHARGER`、`DEVICE_STATUS_ONLINE/OFFLINE/FAULT`、
  `LOCATION_GEN_MAX_PER_REQUEST`、`LOCATION_GEN_BATCH_SIZE`、`LOCATION_CODE_MAX_LENGTH`、
  `DEVICE_OFFLINE_THRESHOLD_SECONDS`、`DEVICE_ALERT_THROTTLE_MINUTES`、`DEVICE_ALERT_TITLE`、
  `DEVICE_SCAN_JOB_ID`、`DEVICE_SCAN_JOB_CLASS`、`DEVICE_SCAN_JOB_CRON`、
  `DEVICE_SCAN_JOB_ENABLED_KEY`、`DEVICE_SCAN_BATCH`

- [ ] **Step 1: 写迁移脚本**

创建 `V3.9.5_5__wms_warehouse_location_device.sql`，内容：

```sql
-- ============================================================================
-- 永鑫WMS · 库区/库位与设备绑定（蓝图流程 YX-WMS-BD-003）
-- 内容：wms_warehouse / wms_location / wms_device 建表 + 菜单权限 + 离线扫描任务种子
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
-- ============================================================================

-- ---------------------------------------------------------------- 建表 DDL
CREATE TABLE IF NOT EXISTS `wms_warehouse` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `warehouse_code` varchar(64) NOT NULL COMMENT '库区编码',
  `warehouse_name` varchar(100) NOT NULL COMMENT '库区名称',
  `warehouse_type` varchar(20)  NOT NULL COMMENT '库区类型：立库/敏捷仓/线边柜',
  `row_count`     int          DEFAULT NULL COMMENT '生成上限-排数（可自由增减，不参与编码计算）',
  `col_count`     int          DEFAULT NULL COMMENT '生成上限-列数（可自由增减，不参与编码计算）',
  `layer_count`   int          DEFAULT NULL COMMENT '生成上限-层数（可自由增减，不参与编码计算）',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_warehouse_code` (`warehouse_code`) USING BTREE,
  INDEX `idx_wms_warehouse_name` (`warehouse_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库区';

CREATE TABLE IF NOT EXISTS `wms_location` (
  `id`             varchar(32)  NOT NULL COMMENT '主键',
  `location_code`  varchar(64)  NOT NULL COMMENT '库位编码',
  `warehouse_id`   varchar(32)  NOT NULL COMMENT '库区ID',
  `row_no`         int          NOT NULL COMMENT '排',
  `col_no`         int          NOT NULL COMMENT '列',
  `layer_no`       int          NOT NULL COMMENT '层',
  `max_weight`     decimal(12,3) DEFAULT NULL COMMENT '承重',
  `status`         varchar(10)  NOT NULL DEFAULT '空闲' COMMENT '状态：空闲/占用/故障',
  `del_flag`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`      varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_location_code` (`location_code`) USING BTREE,
  UNIQUE INDEX `uk_wms_location_grid` (`warehouse_id`,`row_no`,`col_no`,`layer_no`) USING BTREE,
  INDEX `idx_wms_location_warehouse` (`warehouse_id`) USING BTREE,
  INDEX `idx_wms_location_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库位';

CREATE TABLE IF NOT EXISTS `wms_device` (
  `id`             varchar(32)  NOT NULL COMMENT '主键',
  `device_code`    varchar(64)  NOT NULL COMMENT '设备编码',
  `device_name`    varchar(100) NOT NULL COMMENT '设备名称',
  `device_type`    varchar(20)  NOT NULL COMMENT '设备类型：堆垛机/SCR/分拣单元/充电桩',
  `warehouse_id`   varchar(32)  DEFAULT NULL COMMENT '归属库区ID，NULL=未绑定',
  `status`         varchar(10)  NOT NULL DEFAULT '离线' COMMENT '状态：在线/离线/故障',
  `last_heartbeat` datetime     DEFAULT NULL COMMENT '最近一次心跳（或显式置在线）时刻',
  `last_alert_time` datetime    DEFAULT NULL COMMENT '最近一次离线告警时刻（节流用）',
  `del_flag`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`      varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_device_code` (`device_code`) USING BTREE,
  INDEX `idx_wms_device_warehouse` (`warehouse_id`) USING BTREE,
  INDEX `idx_wms_device_type_status` (`device_type`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='设备';

-- ---------------------------------------------------------------- 菜单权限
-- 菜单（menu_type=1），父节点 = 1910000000000000001（V3.9.5_4 已改名为「基础管理」）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000051','1910000000000000001','库区与库位','/wms/warehouse','wms/warehouse/index',1,'','',1,NULL,'0',4.00,0,'ant-design:apartment-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000052','1910000000000000001','设备管理','/wms/device','wms/device/index',1,'','',1,NULL,'0',5.00,0,'ant-design:deployment-unit-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000061','1910000000000000051','库区-新增',NULL,NULL,0,NULL,'',2,'wms:warehouse:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000062','1910000000000000051','库区-编辑',NULL,NULL,0,NULL,'',2,'wms:warehouse:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000063','1910000000000000051','库区-删除',NULL,NULL,0,NULL,'',2,'wms:warehouse:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000064','1910000000000000051','库位-生成',NULL,NULL,0,NULL,'',2,'wms:location:generate','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000065','1910000000000000051','库位-属性',NULL,NULL,0,NULL,'',2,'wms:location:attr','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000066','1910000000000000051','库位-释放',NULL,NULL,0,NULL,'',2,'wms:location:release','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000067','1910000000000000051','库位-删除',NULL,NULL,0,NULL,'',2,'wms:location:delete','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000068','1910000000000000052','设备-新增',NULL,NULL,0,NULL,'',2,'wms:device:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000069','1910000000000000052','设备-编辑',NULL,NULL,0,NULL,'',2,'wms:device:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000070','1910000000000000052','设备-删除',NULL,NULL,0,NULL,'',2,'wms:device:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000071','1910000000000000052','设备-绑定',NULL,NULL,0,NULL,'',2,'wms:device:bind','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000051','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000051',NULL,NOW(),'127.0.0.1'),
('1920000000000000052','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000052',NULL,NOW(),'127.0.0.1'),
('1920000000000000061','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000061',NULL,NOW(),'127.0.0.1'),
('1920000000000000062','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000062',NULL,NOW(),'127.0.0.1'),
('1920000000000000063','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000063',NULL,NOW(),'127.0.0.1'),
('1920000000000000064','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000064',NULL,NOW(),'127.0.0.1'),
('1920000000000000065','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000065',NULL,NOW(),'127.0.0.1'),
('1920000000000000066','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000066',NULL,NOW(),'127.0.0.1'),
('1920000000000000067','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000067',NULL,NOW(),'127.0.0.1'),
('1920000000000000068','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000068',NULL,NOW(),'127.0.0.1'),
('1920000000000000069','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000069',NULL,NOW(),'127.0.0.1'),
('1920000000000000070','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000070',NULL,NOW(),'127.0.0.1'),
('1920000000000000071','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000071',NULL,NOW(),'127.0.0.1');

-- ---------------------------------------------------------------- 离线扫描任务种子
-- 注意：本行只让任务在「定时任务管理」UI 可见（便于用「立即执行」手工触发）。
-- 真正把任务注册进调度器的是 WmsDeviceOfflineScanJobRegistrar（ApplicationReadyEvent 自注册），
-- 因为 sys_quartz_job 表只是注册表元数据，全库没有任何启动加载器。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`) VALUES
('1920000000000000102','org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob','0 7/5 * * * ? *',NULL,'WMS设备离线扫描告警任务','0','admin',NOW(),NULL,NULL,0);
```

- [ ] **Step 2: 追加常量到 `WmsConstants.java`**

在 `WmsConstants.java` 的 `SYNC_JOB_ENABLED_KEY` 常量之后（类结束大括号之前）追加：

```java
    // ---------------------------------------------------------------- 库区类型

    /** 库区类型-立库（半成品立库，24 层 × 6 列量级）。 */
    public static final String WAREHOUSE_TYPE_STEREO = "立库";

    /** 库区类型-敏捷仓（成品敏捷仓，料箱 10 层堆叠）。 */
    public static final String WAREHOUSE_TYPE_AGILE = "敏捷仓";

    /** 库区类型-线边柜（格子柜，不适用排/列/层网格）。 */
    public static final String WAREHOUSE_TYPE_LINE_SIDE = "线边柜";

    // ---------------------------------------------------------------- 库位状态

    /** 库位状态-空闲。 */
    public static final String LOCATION_STATUS_IDLE = "空闲";

    /** 库位状态-占用（视为库位内有物料，禁止修改属性）。 */
    public static final String LOCATION_STATUS_OCCUPIED = "占用";

    /** 库位状态-故障。 */
    public static final String LOCATION_STATUS_FAULT = "故障";

    // ---------------------------------------------------------------- 库位批量生成

    /** 单次生成请求的库位条数上限。 */
    public static final int LOCATION_GEN_MAX_PER_REQUEST = 50000;

    /** 批量生成时每批 INSERT IGNORE 的条数。 */
    public static final int LOCATION_GEN_BATCH_SIZE = 1000;

    /** 库位编码长度上限，与 wms_location.location_code 列宽一致。 */
    public static final int LOCATION_CODE_MAX_LENGTH = 64;

    // ---------------------------------------------------------------- 设备类型

    /** 设备类型-堆垛机。 */
    public static final String DEVICE_TYPE_STACKER = "堆垛机";

    /** 设备类型-SCR 机器人。 */
    public static final String DEVICE_TYPE_SCR = "SCR";

    /** 设备类型-分拣单元。 */
    public static final String DEVICE_TYPE_SORTING_UNIT = "分拣单元";

    /** 设备类型-充电桩。 */
    public static final String DEVICE_TYPE_CHARGER = "充电桩";

    // ---------------------------------------------------------------- 设备状态

    /** 设备状态-在线。 */
    public static final String DEVICE_STATUS_ONLINE = "在线";

    /** 设备状态-离线。 */
    public static final String DEVICE_STATUS_OFFLINE = "离线";

    /** 设备状态-故障。 */
    public static final String DEVICE_STATUS_FAULT = "故障";

    // ---------------------------------------------------------------- 设备离线告警

    /** 心跳静默超过该秒数即判离线（默认 600 秒 = 10 分钟）。 */
    public static final int DEVICE_OFFLINE_THRESHOLD_SECONDS = 600;

    /** 同一设备两次离线告警之间的最小间隔（分钟），防轰炸。 */
    public static final int DEVICE_ALERT_THROTTLE_MINUTES = 30;

    /** 设备离线告警的消息标题。 */
    public static final String DEVICE_ALERT_TITLE = "WMS设备离线告警";

    /** 离线扫描每轮最多处理的设备数。 */
    public static final int DEVICE_SCAN_BATCH = 200;

    /** 设备离线扫描任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行、注册器三处一致。 */
    public static final String DEVICE_SCAN_JOB_ID = "1920000000000000102";

    /** 设备离线扫描任务类名；必须与 Flyway 种子行的 job_class_name 一致。 */
    public static final String DEVICE_SCAN_JOB_CLASS = "org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob";

    /**
     * 设备离线扫描任务 cron：每 5 分钟，落在 :07 / :12 / …
     *
     * <p>刻意与订单补偿任务（{@link #SYNC_JOB_CRON}，落在 :03 / :08 / …）错开 4 分钟，
     * 避免两轮任务同时打库。
     */
    public static final String DEVICE_SCAN_JOB_CRON = "0 7/5 * * * ? *";

    /** 设备离线扫描任务启停开关的配置键；默认 true。 */
    public static final String DEVICE_SCAN_JOB_ENABLED_KEY = "wms.device.job.enabled";

    /** 设备告警收件人配置键。 */
    public static final String DEVICE_ALERT_TO_USER_KEY = "wms.device.alert.to-user";
```

- [ ] **Step 3: 编译**

Run: `bash /d/tools/m.sh -P dev clean package -pl jeecg-boot-module/jeecg-boot-module-wms -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 起动应用并**验证迁移真的生效**（Flyway 会静默吞错！）**

Run:
```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run
```
Expected（必须**逐条核对**，不能只看「启动成功」）：
1. 启动日志中出现 `Migrating schema ... to version 3.9.5.5 - wms_warehouse_location_device`；
2. 日志中**没有** `FlywayException` / `SQLSyntaxErrorException` / `Migration ... failed`；
3. 用 SQL 客户端（或 `mysql` 命令行）确认：
   ```sql
   SHOW TABLES LIKE 'wms_%';   -- 应含 wms_warehouse / wms_location / wms_device
   SELECT COUNT(*) FROM sys_permission WHERE id LIKE '19100000000000000__' AND parent_id IN ('1910000000000000051','1910000000000000052');
   -- 应为 11
   ```
> **本环境实测：脚本报错时应用照常启动**。若表不存在，表现为后续接口 500 而非启动失败。故这一步必须真的查表，不能只信日志。

- [ ] **Step 5: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_5__wms_warehouse_location_device.sql \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java
git commit -m "feat(wms): BD-003 建表迁移与常量（库区/库位/设备）"
```

---

## Task 2: 数据层（三实体 + 三 Mapper + 批量插入 SQL）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsWarehouse.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsLocation.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsDevice.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsWarehouseMapper.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsLocationMapper.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsDeviceMapper.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/xml/WmsLocationMapper.xml`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/entity/WmsEntityMappingTest.java`

**Interfaces:**
- Consumes: Task 1 的常量与表
- Produces:
  - `WmsWarehouse`：`warehouseCode/warehouseName/warehouseType/rowCount/colCount/layerCount/status` + `@TableField(exist=false) Long outOfEnvelopeCount`
  - `WmsLocation`：`locationCode/warehouseId/rowNo/colNo/layerNo/maxWeight/status` + `@TableField(exist=false) String warehouseName`
  - `WmsDevice`：`deviceCode/deviceName/deviceType/warehouseId/status/lastHeartbeat/lastAlertTime` + `@TableField(exist=false) String warehouseName`
  - `WmsLocationMapper.insertIgnoreBatch(@Param("list") List<WmsLocation> list) : int`

> **为什么这个任务存在**：三个 Service 互相引用（`WmsWarehouseServiceImpl.removeWarehouse` 要查库位与设备），
> 若把实体分散到各自的服务任务里会造成先有鸡还是先有蛋的死锁。故把**纯数据层**一次建齐。
> 数据类本身无行为，故测试落在「**映射配置正确性**」上 —— 一个拼错的 `@TableName`
> 会让每条 SQL 在运行时 500，而这个测试几十毫秒就能抓到。

- [ ] **Step 1: 写失败测试（映射配置）**

创建 `src/test/java/org/jeecg/modules/wms/entity/WmsEntityMappingTest.java`：

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 表名与列映射的配置断言。
 *
 * <p>这些类没有行为，但它们上面一个拼错的 {@code @TableName} 会让运行期每条 SQL 都 500。
 * 本测试把该风险的发现时间从「接口报错」提前到「编译后 50 毫秒」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsEntityMappingTest {

    @Test
    void tableNames_mustMatchDdl() {
        assertEquals("wms_warehouse", WmsWarehouse.class.getAnnotation(TableName.class).value());
        assertEquals("wms_location", WmsLocation.class.getAnnotation(TableName.class).value());
        assertEquals("wms_device", WmsDevice.class.getAnnotation(TableName.class).value());
    }

    /** 展示用字段必须标 exist=false，否则 QueryGenerator 会拼出不存在的列导致 SQL 报错。 */
    @Test
    void nonPersistentFields_mustBeMarkedExistFalse() throws Exception {
        assertTrue(isNonPersistent(WmsLocation.class, "warehouseName"), "WmsLocation.warehouseName");
        assertTrue(isNonPersistent(WmsDevice.class, "warehouseName"), "WmsDevice.warehouseName");
        assertTrue(isNonPersistent(WmsWarehouse.class, "outOfEnvelopeCount"), "WmsWarehouse.outOfEnvelopeCount");
    }

    @Test
    void mappers_mustBePresent() {
        assertNotNull(WmsWarehouseMapper.class);
        assertNotNull(WmsLocationMapper.class);
        assertNotNull(WmsDeviceMapper.class);
    }

    private static boolean isNonPersistent(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        TableField tf = field.getAnnotation(TableField.class);
        return tf != null && !tf.exist();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsEntityMappingTest' -DskipTests=false`
Expected: 编译失败 —— `WmsWarehouse` / `WmsLocation` / `WmsDevice` 不存在（`cannot find symbol`）。

- [ ] **Step 3: 写三个实体**

`entity/WmsWarehouse.java`：

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库区")
@TableName("wms_warehouse")
public class WmsWarehouse extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库区编码，如 LB / MJ / XB。 */
    @Excel(name = "库区编码", width = 20)
    @Schema(description = "库区编码")
    private String warehouseCode;

    /** 库区名称。 */
    @Excel(name = "库区名称", width = 20)
    @Schema(description = "库区名称")
    private String warehouseName;

    /** 库区类型：立库 / 敏捷仓 / 线边柜。 */
    @Excel(name = "库区类型", width = 15)
    @Schema(description = "库区类型：立库/敏捷仓/线边柜")
    private String warehouseType;

    /**
     * 生成上限-排数。
     *
     * <p><b>只用于限制批量生成能开到哪</b>，不参与库位编码计算、也不约束任何已有数据，
     * 故可随时自由增减（spec §5.2）。
     */
    @Schema(description = "生成上限-排数")
    private Integer rowCount;

    /** 生成上限-列数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-列数")
    private Integer colCount;

    /** 生成上限-层数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-层数")
    private Integer layerCount;

    /** 状态：1启用 0停用。 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 位于当前维度之外的库位数（非持久化，由服务/控制器回填）。
     *
     * <p>缩维后已有库位可能落在新声明的包络之外。系统**不拦截**这种不一致，
     * 而是把它显示出来，由运维决定是补回维度还是删库位（spec §5.2）。
     */
    @TableField(exist = false)
    @Schema(description = "位于当前维度之外的库位数")
    private Long outOfEnvelopeCount;

    /** 删除状态：0正常 1已删除。 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
```

`entity/WmsLocation.java`：

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.math.BigDecimal;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库位")
@TableName("wms_location")
public class WmsLocation extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库位编码，如 LB-R01-C06-L24。全局唯一。 */
    @Excel(name = "库位编码", width = 24)
    @Schema(description = "库位编码")
    private String locationCode;

    /** 所属库区ID。 */
    @Schema(description = "所属库区ID")
    private String warehouseId;

    /** 排。 */
    @Excel(name = "排", width = 8)
    @Schema(description = "排")
    private Integer rowNo;

    /** 列。 */
    @Excel(name = "列", width = 8)
    @Schema(description = "列")
    private Integer colNo;

    /** 层。 */
    @Excel(name = "层", width = 8)
    @Schema(description = "层")
    private Integer layerNo;

    /** 承重（kg）。 */
    @Excel(name = "承重", width = 12)
    @Schema(description = "承重")
    private BigDecimal maxWeight;

    /** 状态：空闲 / 占用 / 故障。{@code 占用} 视为库位内有物料，禁止修改属性。 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：空闲/占用/故障")
    private String status;

    /** 所属库区名称（非持久化，列表展示用，交给 QueryGenerator 前必须先置空）。 */
    @TableField(exist = false)
    @Schema(description = "所属库区名称")
    private String warehouseName;

    /** 删除状态：0正常 1已删除。 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
```

`entity/WmsDevice.java`：

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.util.Date;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "设备")
@TableName("wms_device")
public class WmsDevice extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 设备编码。 */
    @Excel(name = "设备编码", width = 20)
    @Schema(description = "设备编码")
    private String deviceCode;

    /** 设备名称。 */
    @Excel(name = "设备名称", width = 20)
    @Schema(description = "设备名称")
    private String deviceName;

    /** 设备类型：堆垛机 / SCR / 分拣单元 / 充电桩。 */
    @Excel(name = "设备类型", width = 15)
    @Schema(description = "设备类型：堆垛机/SCR/分拣单元/充电桩")
    private String deviceType;

    /** 归属库区ID；NULL = 未绑定。 */
    @Schema(description = "归属库区ID，NULL=未绑定")
    private String warehouseId;

    /** 状态：在线 / 离线 / 故障。 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：在线/离线/故障")
    private String status;

    /**
     * 最近一次心跳（或显式置在线）时刻。
     *
     * <p><b>判定离线的唯一依据</b>。为 NULL 表示该设备从未上线过，
     * 离线扫描会跳过它 —— 否则新建设备会在 10 分钟后被误报离线（spec §6.4）。
     */
    @Schema(description = "最近一次心跳时刻")
    private Date lastHeartbeat;

    /** 最近一次离线告警时刻，用于告警节流。恢复在线时清空。 */
    @Schema(description = "最近一次离线告警时刻")
    private Date lastAlertTime;

    /** 归属库区名称（非持久化，列表展示用，交给 QueryGenerator 前必须先置空）。 */
    @TableField(exist = false)
    @Schema(description = "归属库区名称")
    private String warehouseName;

    /** 删除状态：0正常 1已删除。 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
```

- [ ] **Step 4: 写三个 Mapper 与批量插入 XML**

`mapper/WmsWarehouseMapper.java`：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsWarehouse;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface WmsWarehouseMapper extends BaseMapper<WmsWarehouse> {
}
```

`mapper/WmsDeviceMapper.java`：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsDevice;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface WmsDeviceMapper extends BaseMapper<WmsDevice> {
}
```

`mapper/WmsLocationMapper.java`：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsLocation;

import java.util.List;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface WmsLocationMapper extends BaseMapper<WmsLocation> {

    /**
     * 一批 INSERT IGNORE，返回**实际插入的行数**。
     *
     * <p>重复坐标（命中 {@code uk_wms_location_code} 或 {@code uk_wms_location_grid}）被跳过、
     * 不计入返回行数。服务层据此算 {@code skipped = 请求总数 - 实际插入数}，
     * 使「跳过」是**数据库告诉我们的真值**，而不是服务端猜的。
     *
     * @param list 同一库区、已算好编码的库位列表，长度不得为 0
     * @return 实际插入行数
     */
    int insertIgnoreBatch(@Param("list") List<WmsLocation> list);
}
```

`mapper/xml/WmsLocationMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.jeecg.modules.wms.mapper.WmsLocationMapper">

    <!--
      批量插入并跳过重复。
      wms_location 上有两个唯一索引（uk_wms_location_code / uk_wms_location_grid），
      靠 INSERT IGNORE + 它们，使「重跑同一生成范围」成为幂等操作 —— 这是
      批量生成不做整体回滚（spec §5.5.1）的安全基础。
      返回值 = 实际插入行数，重复行不计。
    -->
    <insert id="insertIgnoreBatch">
        INSERT IGNORE INTO wms_location
            (id, location_code, warehouse_id, row_no, col_no, layer_no,
             max_weight, status, del_flag, create_by, create_time, update_by, update_time)
        VALUES
        <foreach collection="list" item="it" separator=",">
            (#{it.id}, #{it.locationCode}, #{it.warehouseId}, #{it.rowNo}, #{it.colNo}, #{it.layerNo},
             #{it.maxWeight}, #{it.status}, 0, #{it.createBy}, #{it.createTime}, #{it.updateBy}, #{it.updateTime})
        </foreach>
    </insert>

</mapper>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsEntityMappingTest' -DskipTests=false`
Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

> 若报 `Could not resolve dependencies`，先跑一次
> `bash /d/tools/m.sh -P dev install -pl jeecg-boot-base-core,jeecg-module-system/jeecg-system-api/jeecg-system-local-api -am -DskipTests`。

- [ ] **Step 6: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/entity/
git commit -m "feat(wms): BD-003 数据层（库区/库位/设备实体与 Mapper）"
```

---

## Task 3: 库位编码生成器（纯函数，验收标准①的核心）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/util/WmsLocationCodeGenerator.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/util/WmsLocationCodeGeneratorTest.java`

**Interfaces:**
- Consumes: 无（**刻意不依赖任何东西** —— 这正是设计要点）
- Produces:
  - `WmsLocationCodeGenerator.MIN_SEGMENT_WIDTH : int = 2`
  - `static String padSegment(Integer value)` — 补零到 `max(2, 自身位数)`，只补不截；null 抛 `IllegalArgumentException`
  - `static String locationCode(String warehouseCode, Integer rowNo, Integer colNo, Integer layerNo)` — 形如 `LB-R01-C06-L24`

> **这是本 Spec 本轮修正的落点。** 初稿把段宽定义成 `max(2, 库区该维度上限的位数)`，
> 于是库区配置被绑进了编码，只好反过来禁止改配置。需求方要求维度可自由增减，
> 故段宽改由**库位自身的数字**决定 —— 本类因此**不接受任何库区参数**。
> 这不是省事，是**把可变的东西从接口签名里排除掉**，让「配置改了编码就变」在类型层面无法发生。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/util/WmsLocationCodeGeneratorTest.java`：

```java
package org.jeecg.modules.wms.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @Description: 库位编码生成规则单测（验收标准①的核心）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationCodeGeneratorTest {

    // ------------------------------------------------ 已确认的样例必须逐字符一致

    /** 半成品立库：需求方确认过的样例，不得漂移。 */
    @Test
    void shouldMatchApprovedStereoSample() {
        assertEquals("LB-R01-C06-L24", WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24));
    }

    @Test
    void shouldMatchApprovedAgileSample() {
        assertEquals("MJ-R01-C60422-L10", WmsLocationCodeGenerator.locationCode("MJ", 1, 60422, 10));
    }

    @Test
    void shouldMatchLowLayerSample() {
        assertEquals("LB-R01-C06-L01", WmsLocationCodeGenerator.locationCode("LB", 1, 6, 1));
    }

    // ------------------------------------------------ 补零：只补不截

    @Test
    void shouldPadSingleDigitToTwo() {
        assertEquals("01", WmsLocationCodeGenerator.padSegment(1));
        assertEquals("06", WmsLocationCodeGenerator.padSegment(6));
        assertEquals("09", WmsLocationCodeGenerator.padSegment(9));
    }

    @Test
    void shouldNotPadTwoDigits() {
        assertEquals("10", WmsLocationCodeGenerator.padSegment(10));
        assertEquals("24", WmsLocationCodeGenerator.padSegment(24));
        assertEquals("99", WmsLocationCodeGenerator.padSegment(99));
    }

    /** 关键：超过 2 位**不得截断**，否则 100 层与 10 层会撞成同一个编码。 */
    @Test
    void shouldNeverTruncateBeyondTwoDigits() {
        assertEquals("100", WmsLocationCodeGenerator.padSegment(100));
        assertEquals("106", WmsLocationCodeGenerator.padSegment(106));
        assertEquals("60422", WmsLocationCodeGenerator.padSegment(60422));
    }

    // ------------------------------------------------ 单射性 → 唯一性不依赖外部配置

    /**
     * 编码是坐标的单射：不同坐标必得不同编码。
     *
     * <p>这一条替代了「靠库区配置保证唯一」的旧思路 —— 唯一性现在是编码规则自身的性质，
     * 与库区怎么配无关。若哪天有人把 {@code padSegment} 改成会截断的实现，本用例立刻红。
     */
    @Test
    void shouldBeInjectiveOnCoordinates() {
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 6),
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 606));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 10),
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 100));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24),
                WmsLocationCodeGenerator.locationCode("LB", 1, 60, 24));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24),
                WmsLocationCodeGenerator.locationCode("MJ", 1, 6, 24));
    }

    // ------------------------------------------------ 幂等：同输入必同输出

    @Test
    void shouldBeIdempotentForSameCoordinates() {
        assertEquals(
                WmsLocationCodeGenerator.locationCode("LB", 3, 7, 15),
                WmsLocationCodeGenerator.locationCode("LB", 3, 7, 15));
    }

    // ------------------------------------------------ 非法输入

    @Test
    void shouldRejectNullSegments() {
        assertThrows(IllegalArgumentException.class, () -> WmsLocationCodeGenerator.padSegment(null));
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode("LB", null, 6, 24));
    }

    @Test
    void shouldRejectBlankWarehouseCode() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode("  ", 1, 6, 24));
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode(null, 1, 6, 24));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationCodeGeneratorTest' -DskipTests=false`
Expected: 编译失败 —— `WmsLocationCodeGenerator` 不存在（`cannot find symbol`）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/org/jeecg/modules/wms/util/WmsLocationCodeGenerator.java`：

```java
package org.jeecg.modules.wms.util;

/**
 * 库位编码生成器。
 *
 * <p><b>纯函数</b>：不依赖 Spring、不依赖库区配置、不做任何 IO，因此可被穷举测试。
 *
 * <p><b>规则</b>：{@code {库区编码}-R{排}-C{列}-L{层}}，每段补零到
 * {@code max(2, 该数字自身的位数)}，形如 {@code LB-R01-C06-L24}。
 *
 * <p><b>为什么不引用库区维度上限</b>：本设计的初稿曾用
 * {@code max(2, 库区该维度上限的位数)} 计算段宽，于是「库区配置」被绑进了编码 ——
 * 配置一改，同一个库位的编码就变了，为保住编码稳定只好反过来禁止改配置
 * （禁缩维、禁跨段宽扩容）。需求方明确要求<b>所有维度可自由增减</b>，
 * 故把库区维度从编码规则里彻底移除：段宽只由库位自身的数字决定。
 *
 * <p>补零是 {@code %0Nd} 语义，<b>只补不截</b>：值 6 → {@code "06"}，值 106 → {@code "106"}。
 * 该映射对坐标是单射，故不同坐标必得不同编码 —— <b>唯一性不依赖任何外部配置</b>，
 * 幂等性也随之免费获得（同一坐标在任何时刻、任何请求范围下都算出同一编码）。
 *
 * @author 永鑫WMS
 */
public final class WmsLocationCodeGenerator {

    /** 每段最小宽度。低于 2 位会让 {@code C1} 与 {@code C01} 这类写法混用。 */
    public static final int MIN_SEGMENT_WIDTH = 2;

    private WmsLocationCodeGenerator() {
    }

    /**
     * 把单个坐标数字补零到 {@code max(MIN_SEGMENT_WIDTH, 自身位数)}。只补不截。
     *
     * @param value 坐标值，不可为 null
     * @return 补零后的字符串
     * @throws IllegalArgumentException value 为 null 时
     */
    public static String padSegment(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("库位坐标不能为空");
        }
        String raw = String.valueOf(value);
        int width = Math.max(MIN_SEGMENT_WIDTH, raw.length());
        StringBuilder padded = new StringBuilder();
        for (int i = raw.length(); i < width; i++) {
            padded.append('0');
        }
        return padded.append(raw).toString();
    }

    /**
     * 生成库位编码。
     *
     * @param warehouseCode 库区编码，不可为空
     * @param rowNo         排
     * @param colNo         列
     * @param layerNo       层
     * @return 形如 {@code LB-R01-C06-L24} 的编码
     * @throws IllegalArgumentException 库区编码为空、或任一坐标段为 null 时
     */
    public static String locationCode(String warehouseCode, Integer rowNo, Integer colNo, Integer layerNo) {
        if (warehouseCode == null || warehouseCode.trim().isEmpty()) {
            throw new IllegalArgumentException("库区编码不能为空");
        }
        return warehouseCode
                + "-R" + padSegment(rowNo)
                + "-C" + padSegment(colNo)
                + "-L" + padSegment(layerNo);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationCodeGeneratorTest' -DskipTests=false`
Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

> **本测试不覆盖的内容（诚实声明）**：「同一坐标在库区层数配 24 / 9999 / 10 三种情形下编码相同」
> 这一条**无法在此测试表达** —— 本类的签名里根本没有库区参数，该性质由**类型系统**保证，
> 而不是由断言保证。真正的行为级验证放在 Task 5（那里有 `WmsWarehouse` 参与）
> 与 Task 14 的 E2E 步骤 18。

- [ ] **Step 5: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/util/WmsLocationCodeGenerator.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/util/WmsLocationCodeGeneratorTest.java
git commit -m "feat(wms): BD-003 库位编码生成器（段宽只由库位自身决定，与库区配置解耦）"
```

---

## Task 4: 库区服务与控制器

**Files:**
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`（追加 1 个常量）
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsWarehouseService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsWarehouseServiceImpl.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsWarehouseController.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsWarehouseServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 `WmsWarehouse` / `WmsWarehouseMapper` / `WmsLocationMapper` / `WmsDeviceMapper`；`WmsUtils.fillCreate/fillUpdate`
- Produces（后续任务依赖，签名必须逐字一致）：
  ```java
  public interface IWmsWarehouseService extends IService<WmsWarehouse> {
      List<SelectTreeModel> queryTreeList();
      boolean checkCodeExists(String code, String id);
      void addWarehouse(WmsWarehouse warehouse);
      void editWarehouse(WmsWarehouse warehouse);
      void disableWarehouse(String id);
      void removeWarehouse(String id);
      List<String> queryIdsByType(String warehouseType);
      WmsWarehouse requireEnabledWarehouse(String warehouseId);   // ← Task 5 / Task 9 复用
  }
  ```

- [ ] **Step 1: 追加常量**

在 `WmsConstants.java` 的 `WAREHOUSE_TYPE_LINE_SIDE` 之后追加：

```java
    /**
     * 库区树中「库区类型」节点的 key 前缀。
     *
     * <p>类型节点不是真实记录，其 key 由类型名合成；加前缀是为了**避免与库区真实 id 撞键**
     * （否则前端树选中类型节点时会被当成某个库区的 id）。
     */
    public static final String WAREHOUSE_TYPE_NODE_PREFIX = "type:";
```

- [ ] **Step 2: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsWarehouseServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库区业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsWarehouseServiceImplTest {

    private static WmsWarehouse warehouse(String id, String code, String name, String type) {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode(code).setWarehouseName(name).setWarehouseType(type);
        w.setId(id);
        return w;
    }

    /** 造一个注入了 mapper 的 spy，mapper 默认「无引用」，避免个别用例漏配导致 NPE。 */
    private static WmsWarehouseServiceImpl newService() {
        WmsWarehouseServiceImpl service = spy(new WmsWarehouseServiceImpl());
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsDeviceMapper deviceMapper = mock(WmsDeviceMapper.class);
        when(locationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(deviceMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        WmsTestSupport.inject(service, "wmsLocationMapper", locationMapper);
        WmsTestSupport.inject(service, "wmsDeviceMapper", deviceMapper);
        return service;
    }

    // ------------------------------------------------------------ 编码唯一性（蓝图步骤 3）

    @Test
    void addWarehouse_shouldRejectDuplicateCode() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addWarehouse(warehouse(null, "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO)));
        assertEquals("库区编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    @Test
    void addWarehouse_shouldDefaultStatusToEnable() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addWarehouse(warehouse(null, "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO));

        ArgumentCaptor<WmsWarehouse> captor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(service).save(captor.capture());
        assertEquals(WmsConstants.STATUS_ENABLE, captor.getValue().getStatus());
        assertTrue(captor.getValue().getCreateTime() != null, "审计字段应由 WmsUtils.fillCreate 填入");
    }

    /** 编辑时唯一性校验必须排除自身，否则原地保存会被自己挡住。 */
    @Test
    void editWarehouse_shouldExcludeSelfOnCodeCheck() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(1L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        // count 返回 1 表示「该编码已被别人占用」→ 应拒绝
        WmsWarehouse self = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        assertThrows(RuntimeException.class, () -> service.editWarehouse(self));
        verify(service, never()).updateById(any());

        doReturn(0L).when(service).count(any(Wrapper.class));
        service.editWarehouse(self);
        verify(service).updateById(any());
    }

    // ------------------------------------------------------------ 库区树（蓝图步骤 2）

    @Test
    void queryTreeList_shouldGroupByWarehouseType() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(Arrays.asList(
                warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO),
                warehouse("2", "MJ", "成品敏捷仓", WmsConstants.WAREHOUSE_TYPE_AGILE),
                warehouse("3", "LB2", "立库二号", WmsConstants.WAREHOUSE_TYPE_STEREO)
        )).when(service).list(any(Wrapper.class));

        List<SelectTreeModel> tree = service.queryTreeList();

        assertEquals(2, tree.size(), "两个类型 → 两个顶层节点");
        SelectTreeModel first = tree.get(0);
        assertEquals(WmsConstants.WAREHOUSE_TYPE_STEREO, first.getTitle());
        assertEquals("type:立库", first.getKey(), "类型节点 key 必须带前缀，避免与库区真实 id 撞键");
        assertEquals(WmsConstants.ROOT_PID_VALUE, first.getParentId());
        assertFalse(first.isLeaf());
        assertEquals(2, first.getChildren().size());
        assertEquals("1", first.getChildren().get(0).getKey());
        assertEquals("半成品立库", first.getChildren().get(0).getTitle());
        assertTrue(first.getChildren().get(0).isLeaf());
    }

    @Test
    void queryTreeList_shouldReturnEmptyWhenNoWarehouse() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        assertTrue(service.queryTreeList().isEmpty());
    }

    // ------------------------------------------------------------ 维度自由增减（spec §5.2 核心）

    /**
     * 需求方明确要求：**所有维度可自由调节**（可大也可小），不得有任何守卫。
     *
     * <p>本用例是对初稿设计的**反向断言** —— 初稿曾禁止缩维与跨段宽扩容。若哪天有人
     * 「为了编码稳定」又把这层守卫加回来，本用例会红。
     */
    @Test
    void editWarehouse_shouldAllowShrinkingAndGrowingDimensionsFreely() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        WmsWarehouse shrinking = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        shrinking.setRowCount(1).setColCount(6).setLayerCount(10);   // 由 24 缩到 10
        service.editWarehouse(shrinking);

        WmsWarehouse growing = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        growing.setRowCount(1).setColCount(60422).setLayerCount(9999); // 扩到跨段宽
        service.editWarehouse(growing);

        verify(service, org.mockito.Mockito.times(2)).updateById(any());
    }

    /** 维度清空也应保存成功（线边柜等不适用网格的库区），只是不允许生成库位（Task 5 守卫）。 */
    @Test
    void editWarehouse_shouldAllowNullDimensions() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        WmsWarehouse lineSide = warehouse("1", "XB", "线边柜", WmsConstants.WAREHOUSE_TYPE_LINE_SIDE);
        lineSide.setRowCount(null).setColCount(null).setLayerCount(null);
        service.editWarehouse(lineSide);

        verify(service).updateById(any());
    }

    // ------------------------------------------------------------ 删除守卫

    @Test
    void removeWarehouse_shouldRejectWhenLocationsExist() {
        WmsWarehouseServiceImpl service = newService();
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        when(locationMapper.selectCount(any(Wrapper.class))).thenReturn(144L);
        WmsTestSupport.inject(service, "wmsLocationMapper", locationMapper);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeWarehouse("1"));
        assertEquals("该库区下存在库位，不允许删除", ex.getMessage());
        verify(service, never()).removeById(any(String.class));
    }

    @Test
    void removeWarehouse_shouldRejectWhenDevicesBound() {
        WmsWarehouseServiceImpl service = newService();
        WmsDeviceMapper deviceMapper = mock(WmsDeviceMapper.class);
        when(deviceMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        WmsTestSupport.inject(service, "wmsDeviceMapper", deviceMapper);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeWarehouse("1"));
        assertEquals("该库区已绑定设备，不允许删除", ex.getMessage());
    }

    @Test
    void removeWarehouse_shouldDeleteWhenNothingReferences() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(true).when(service).removeById(any(String.class));
        service.removeWarehouse("1");
        verify(service).removeById("1");
    }

    // ------------------------------------------------------------ requireEnabledWarehouse（Task 5 / Task 9 复用）

    @Test
    void requireEnabledWarehouse_shouldRejectMissing() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(null).when(service).getById("nope");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.requireEnabledWarehouse("nope"));
        assertEquals("库区不存在或已停用", ex.getMessage());
    }

    @Test
    void requireEnabledWarehouse_shouldRejectDisabled() {
        WmsWarehouseServiceImpl service = newService();
        WmsWarehouse disabled = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        disabled.setStatus(WmsConstants.STATUS_DISABLE);
        doReturn(disabled).when(service).getById("1");
        assertThrows(RuntimeException.class, () -> service.requireEnabledWarehouse("1"));
    }

    @Test
    void requireEnabledWarehouse_shouldReturnEnabled() {
        WmsWarehouseServiceImpl service = newService();
        WmsWarehouse enabled = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        enabled.setStatus(WmsConstants.STATUS_ENABLE);
        doReturn(enabled).when(service).getById("1");
        assertEquals("1", service.requireEnabledWarehouse("1").getId());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsWarehouseServiceImplTest' -DskipTests=false`
Expected: 编译失败 —— `IWmsWarehouseService` / `WmsWarehouseServiceImpl` 不存在。

- [ ] **Step 4: 写服务接口与实现**

`service/IWmsWarehouseService.java`：

```java
package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.entity.WmsWarehouse;

import java.util.List;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsWarehouseService extends IService<WmsWarehouse> {

    /** 两层库区树：库区类型 → 库区。 */
    List<SelectTreeModel> queryTreeList();

    /** 库区编码唯一性校验（蓝图步骤 3）。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    void addWarehouse(WmsWarehouse warehouse);

    void editWarehouse(WmsWarehouse warehouse);

    void disableWarehouse(String id);

    /** 逻辑删除；库区下存在库位或已绑定设备时拒绝。 */
    void removeWarehouse(String id);

    /** 某库区类型下的全部库区 id，供库位列表按类型过滤。 */
    List<String> queryIdsByType(String warehouseType);

    /** 取启用中的库区，不存在或已停用则抛异常。批量生成与设备绑定共用此守卫。 */
    WmsWarehouse requireEnabledWarehouse(String warehouseId);
}
```

`service/impl/WmsWarehouseServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsWarehouseServiceImpl extends ServiceImpl<WmsWarehouseMapper, WmsWarehouse>
        implements IWmsWarehouseService {

    @Autowired
    private WmsLocationMapper wmsLocationMapper;

    @Autowired
    private WmsDeviceMapper wmsDeviceMapper;

    @Override
    public List<SelectTreeModel> queryTreeList() {
        List<WmsWarehouse> all = this.list(new QueryWrapper<WmsWarehouse>()
                .orderByAsc("warehouse_type")
                .orderByAsc("warehouse_code"));
        Map<String, List<WmsWarehouse>> byType = new LinkedHashMap<>();
        for (WmsWarehouse warehouse : all) {
            byType.computeIfAbsent(warehouse.getWarehouseType(), key -> new ArrayList<>()).add(warehouse);
        }
        List<SelectTreeModel> tree = new ArrayList<>();
        for (Map.Entry<String, List<WmsWarehouse>> entry : byType.entrySet()) {
            String typeKey = WmsConstants.WAREHOUSE_TYPE_NODE_PREFIX + entry.getKey();
            SelectTreeModel typeNode = new SelectTreeModel();
            typeNode.setKey(typeKey);
            typeNode.setValue(typeKey);
            typeNode.setTitle(entry.getKey());
            typeNode.setParentId(WmsConstants.ROOT_PID_VALUE);
            typeNode.setLeaf(false);
            List<SelectTreeModel> children = new ArrayList<>();
            for (WmsWarehouse warehouse : entry.getValue()) {
                SelectTreeModel node = new SelectTreeModel();
                node.setKey(warehouse.getId());
                node.setValue(warehouse.getId());
                node.setTitle(warehouse.getWarehouseName());
                node.setParentId(typeKey);
                node.setChildren(new ArrayList<>());
                node.setLeaf(true);
                children.add(node);
            }
            typeNode.setChildren(children);
            tree.add(typeNode);
        }
        return tree;
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsWarehouse> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("warehouse_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addWarehouse(WmsWarehouse warehouse) {
        if (checkCodeExists(warehouse.getWarehouseCode(), null)) {
            throw new RuntimeException("库区编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(warehouse.getStatus())) {
            warehouse.setStatus(WmsConstants.STATUS_ENABLE);
        }
        WmsUtils.fillCreate(warehouse);
        this.save(warehouse);
    }

    /**
     * 编辑库区。
     *
     * <p><b>刻意不校验维度变更</b>：{@code row_count/col_count/layer_count} 只用于限制
     * 批量生成的范围，不参与库位编码计算（见 {@code WmsLocationCodeGenerator}），
     * 因此可自由增减。需求方明确要求「所有维度可以自由调节」。
     * 缩维后若已有库位落在新维度之外，系统**不拦截**，只在库区详情回填越界数量供人查看。
     */
    @Override
    public void editWarehouse(WmsWarehouse warehouse) {
        if (checkCodeExists(warehouse.getWarehouseCode(), warehouse.getId())) {
            throw new RuntimeException("库区编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(warehouse);
        this.updateById(warehouse);
    }

    @Override
    public void disableWarehouse(String id) {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId(id);
        warehouse.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(warehouse);
        this.updateById(warehouse);
    }

    @Override
    public void removeWarehouse(String id) {
        long locationCount = wmsLocationMapper.selectCount(
                new QueryWrapper<WmsLocation>().eq("warehouse_id", id));
        if (locationCount > 0) {
            throw new RuntimeException("该库区下存在库位，不允许删除");
        }
        long deviceCount = wmsDeviceMapper.selectCount(
                new QueryWrapper<WmsDevice>().eq("warehouse_id", id));
        if (deviceCount > 0) {
            throw new RuntimeException("该库区已绑定设备，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public List<String> queryIdsByType(String warehouseType) {
        if (oConvertUtils.isEmpty(warehouseType)) {
            return Collections.emptyList();
        }
        return this.list(new QueryWrapper<WmsWarehouse>()
                        .select("id")
                        .eq("warehouse_type", warehouseType))
                .stream()
                .map(WmsWarehouse::getId)
                .collect(Collectors.toList());
    }

    @Override
    public WmsWarehouse requireEnabledWarehouse(String warehouseId) {
        WmsWarehouse warehouse = oConvertUtils.isEmpty(warehouseId) ? null : this.getById(warehouseId);
        if (warehouse == null || !WmsConstants.STATUS_ENABLE.equals(warehouse.getStatus())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        return warehouse;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsWarehouseServiceImplTest' -DskipTests=false`
Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

- [ ] **Step 6: 写控制器**

`controller/WmsWarehouseController.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-库区")
@RestController
@RequestMapping("/wms/warehouse")
@Slf4j
public class WmsWarehouseController extends JeecgController<WmsWarehouse, IWmsWarehouseService> {

    @Operation(summary = "库区-库区树")
    @GetMapping(value = "/treeList")
    public Result<List<SelectTreeModel>> treeList() {
        return Result.OK(service.queryTreeList());
    }

    @Operation(summary = "库区-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsWarehouse>> queryPageList(WmsWarehouse wmsWarehouse,
                                                     @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                     @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                     HttpServletRequest req) {
        QueryWrapper<WmsWarehouse> queryWrapper = QueryGenerator.initQueryWrapper(wmsWarehouse, req.getParameterMap());
        queryWrapper.orderByAsc("warehouse_code");
        Page<WmsWarehouse> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    @Operation(summary = "库区-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsWarehouse> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsWarehouse entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.OK(entity);
    }

    @Operation(summary = "库区-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("库区编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("库区编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "库区-添加", operateType = 2)
    @Operation(summary = "库区-添加")
    @PostMapping(value = "/add")
    public Result<WmsWarehouse> add(@RequestBody WmsWarehouse wmsWarehouse) {
        try {
            service.addWarehouse(wmsWarehouse);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-编辑", operateType = 3)
    @Operation(summary = "库区-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsWarehouse> edit(@RequestBody WmsWarehouse wmsWarehouse) {
        try {
            service.editWarehouse(wmsWarehouse);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-停用", operateType = 3)
    @Operation(summary = "库区-停用")
    @PostMapping(value = "/disable")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableWarehouse(id);
        return Result.OK("停用成功！");
    }

    @AutoLog(value = "库区-删除", operateType = 4)
    @Operation(summary = "库区-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeWarehouse(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-批量删除", operateType = 4)
    @Operation(summary = "库区-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        try {
            for (String id : ids.split(",")) {
                service.removeWarehouse(id);
            }
            return Result.OK("批量删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }
}
```

- [ ] **Step 7: 编译并提交**

Run: `bash /d/tools/m.sh -P dev clean package -pl jeecg-boot-module/jeecg-boot-module-wms -am -DskipTests`
Expected: `BUILD SUCCESS`

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsWarehouseController.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/
git commit -m "feat(wms): BD-003 库区服务与控制器（维度可自由增减，无守卫）"
```

---

## Task 5: 库位批量生成（**验收标准①的核心**）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsLocationGenDTO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/WmsLocationGenResultVO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsLocationService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImpl.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 `WmsLocation` / `WmsLocationMapper.insertIgnoreBatch`；Task 3 的 `WmsLocationCodeGenerator.locationCode(...)`；Task 4 的 `IWmsWarehouseService.requireEnabledWarehouse(String)`；Task 1 的 `LOCATION_GEN_MAX_PER_REQUEST` / `LOCATION_GEN_BATCH_SIZE` / `LOCATION_CODE_MAX_LENGTH` / `LOCATION_STATUS_IDLE`
- Produces:
  ```java
  public interface IWmsLocationService extends IService<WmsLocation> {
      WmsLocationGenResultVO generate(WmsLocationGenDTO dto);   // T6 再往本接口加方法
  }
  ```
  `WmsLocationGenDTO`：`warehouseId,rowFrom,rowTo,colFrom,colTo,layerFrom,layerTo`（均为 `Integer`，`warehouseId` 为 `String`）
  `WmsLocationGenResultVO`：`int generated`、`int skipped`，有 `@AllArgsConstructor` 与 `@NoArgsConstructor`

**关键实现注意（两个真坑）**

1. **自定义 XML 插入不会自动填充主键。** MyBatis-Plus 的 `IdType.ASSIGN_ID` 只在走 MP 自己的
   `insert` 时生效；`insertIgnoreBatch` 是手写 SQL，**必须在服务层显式 `IdWorker.getIdStr()` 设 id**，
   否则主键为 null → 插入失败或被 `INSERT IGNORE` 静默吞掉。
2. **审计字段不要逐行调 `WmsUtils.fillCreate`**：它每次都查 `SecurityUtils`，5 万行就是 5 万次查。
   改为在方法开头取一次 `WmsUtils.currentUsername()` 与 `new Date()`，逐行复用。
   （单条新增仍走 `fillCreate`，见 Task 6。）

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库位批量生成单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationServiceImplTest {

    private static WmsWarehouse warehouse(String code, Integer rows, Integer cols, Integer layers) {
        WmsWarehouse w = new WmsWarehouse()
                .setWarehouseCode(code)
                .setWarehouseName(code + "库区")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO)
                .setRowCount(rows).setColCount(cols).setLayerCount(layers)
                .setStatus(WmsConstants.STATUS_ENABLE);
        w.setId("W1");
        return w;
    }

    private static WmsLocationGenDTO range(int rowFrom, int rowTo, int colFrom, int colTo, int layerFrom, int layerTo) {
        WmsLocationGenDTO dto = new WmsLocationGenDTO();
        dto.setWarehouseId("W1");
        dto.setRowFrom(rowFrom).setRowTo(rowTo)
           .setColFrom(colFrom).setColTo(colTo)
           .setLayerFrom(layerFrom).setLayerTo(layerTo);
        return dto;
    }

    /**
     * 造一个服务：mapper 的 insertIgnoreBatch 如实返回「本批长度」（模拟全部是新行），
     * 库区守卫返回给定库区。
     */
    private static WmsLocationServiceImpl newService(WmsWarehouse warehouse) {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        WmsTestSupport.inject(service, "baseMapper", mapper);

        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse);
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);
        return service;
    }

    /** 取回注入到 {@code ServiceImpl.baseMapper}（另一包的 protected 字段）的 mock，供 verify 用。 */
    private static WmsLocationMapper mapperOf(WmsLocationServiceImpl service) {
        return (WmsLocationMapper) ReflectionTestUtils.getField(service, "baseMapper");
    }

    // ------------------------------------------------------------ 验收标准①：144 个立库库位

    @Test
    void generate_shouldCreateAllLocationsInRange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 6, 1, 24));

        assertEquals(144, result.getGenerated());
        assertEquals(0, result.getSkipped());
        verify(mapperOf(service), times(1)).insertIgnoreBatch(anyList());
    }

    /** 首末编码必须与需求方确认的样例一致。 */
    @Test
    void generate_shouldProduceApprovedCodes() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        ArgumentCaptor<List<WmsLocation>> captor = ArgumentCaptor.forClass(List.class);

        service.generate(range(1, 1, 1, 6, 1, 24));

        verify(mapperOf(service)).insertIgnoreBatch(captor.capture());
        List<WmsLocation> rows = captor.getValue();
        assertEquals(144, rows.size());
        assertEquals("LB-R01-C01-L01", rows.get(0).getLocationCode(), "首个库位");
        assertEquals("LB-R01-C06-L24", rows.get(143).getLocationCode(), "末个库位");
        assertEquals("1", rows.get(0).getWarehouseId());
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, rows.get(0).getStatus());
        assertNotNull(rows.get(0).getId(),
                "自定义 XML 插入不会自动填主键，必须在服务层显式设 id；为 null 会被 INSERT IGNORE 静默吞掉");
    }

    // ------------------------------------------------------------ 编码与库区配置解耦（spec §5.2）

    /**
     * **本轮修正的核心断言**：同一个坐标，在库区层数配 24 / 9999 / 10 三种情形下，
     * 生成的编码必须**完全相同**。
     *
     * <p>初稿用 {@code max(2, 库区该维度上限的位数)} 算段宽，本用例在初稿下会红
     * （24 → {@code L24}，9999 → {@code L0024}），故不得不靠禁止改维度来绕过。
     * 现在段宽只由库位自身数字决定，该性质免费成立。
     */
    @Test
    void generate_shouldProduceSameCodeRegardlessOfWarehouseDimensions() {
        String codeAt24 = firstCodeOf(24);
        String codeAt9999 = firstCodeOf(9999);
        String codeAt10 = firstCodeOf(10);

        assertEquals("LB-R01-C06-L10", codeAt24);
        assertEquals(codeAt24, codeAt9999, "层数上限改成 9999 不得改变已有库位的编码");
        assertEquals(codeAt24, codeAt10, "层数上限改成 10 不得改变已有库位的编码");
    }

    private static String firstCodeOf(int layerCount) {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, layerCount));
        ArgumentCaptor<List<WmsLocation>> captor = ArgumentCaptor.forClass(List.class);
        service.generate(range(1, 1, 6, 6, 10, 10));
        verify(mapperOf(service)).insertIgnoreBatch(captor.capture());
        return captor.getValue().get(0).getLocationCode();
    }

    // ------------------------------------------------------------ 幂等（重跑同范围）

    @Test
    void generate_shouldBeIdempotentWhenMapperSkipsEverything() {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList())).thenReturn(0);   // 全部已存在 → 跳过
        WmsTestSupport.inject(service, "baseMapper", mapper);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse("LB", 1, 6, 24));
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 6, 1, 24));

        assertEquals(0, result.getGenerated());
        assertEquals(144, result.getSkipped());
    }

    // ------------------------------------------------------------ 分批（每批 1000）

    @Test
    void generate_shouldSplitIntoBatchesOfOneThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 2500, 1));

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 2500, 1, 1));

        assertEquals(2500, result.getGenerated());
        verify(mapperOf(service), times(3)).insertIgnoreBatch(anyList());   // 1000 + 1000 + 500
    }

    // ------------------------------------------------------------ 守卫

    /** Review Focus #3：漏填维度时必须明确报错，而不是算出满屏 null 段。 */
    @Test
    void generate_shouldRejectWarehouseWithoutDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", null, null, null));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 1, 1, 1)));
        assertEquals("库区未配置网格维度，请先在库区上填写排 / 列 / 层上限", ex.getMessage());
    }

    @Test
    void generate_shouldRejectZeroDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", 0, 6, 24));
        assertThrows(RuntimeException.class, () -> service.generate(range(1, 1, 1, 1, 1, 1)));
    }

    @Test
    void generate_shouldRejectInvalidRange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(2, 1, 1, 6, 1, 24)));
        assertEquals("库位范围不合法", ex.getMessage());
    }

    @Test
    void generate_shouldRejectZeroOrNegativeBound() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        assertThrows(RuntimeException.class, () -> service.generate(range(0, 1, 1, 6, 1, 24)));
    }

    /** 包络守卫：不得超过库区声明的上限。 */
    @Test
    void generate_shouldRejectBeyondLayerEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 6, 1, 30)));
        assertEquals("第 30 层超出库区层数上限 24", ex.getMessage());
    }

    @Test
    void generate_shouldRejectBeyondColEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 7, 1, 24)));
        assertEquals("第 7 列超出库区列数上限 6", ex.getMessage());
    }

    /** 5 万上限守卫，报错文案必须含「本次 N 个」与「按层 / 列分批」。 */
    @Test
    void generate_shouldRejectOverFiftyThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 60422, 10));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 60422, 1, 10)));
        assertEquals("单次最多生成 50000 个库位，本次 604220 个，请按层 / 列分批", ex.getMessage());
        verify(mapperOf(service), times(0)).insertIgnoreBatch(anyList());
    }

    /** 恰好 5 万应放行（边界不能写成 {@code >=}）。 */
    @Test
    void generate_shouldAllowExactlyFiftyThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 50000, 1));
        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 50000, 1, 1));
        assertEquals(50000, result.getGenerated());
    }

    /** Review Focus #1：编码超长必须在插入前拦住，否则 INSERT IGNORE 会静默丢行。 */
    @Test
    void generate_shouldRejectWhenCodeWouldExceedColumnWidth() {
        StringBuilder longCode = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            longCode.append('X');
        }
        WmsLocationServiceImpl service = newService(warehouse(longCode.toString(), 1, 6, 24));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 6, 1, 24)));
        assertTrue(ex.getMessage().startsWith("库区编码过长"), "实际文案：" + ex.getMessage());
        verify(mapperOf(service), times(0)).insertIgnoreBatch(anyList());
    }

    // ------------------------------------------------------------ 部分失败：不整体回滚，但必须报出已生成数

    @Test
    void generate_shouldReportPartialCountOnFailure() {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList()))
                .thenReturn(1000)                                        // 第 1 批成功
                .thenThrow(new RuntimeException("Deadlock found"));      // 第 2 批炸
        WmsTestSupport.inject(service, "baseMapper", mapper);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse("MJ", 1, 2500, 1));
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 2500, 1, 1)));

        assertTrue(ex.getMessage().startsWith("已生成 1000 个后失败："), "实际文案：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("重跑同一范围可安全续做"), "必须告诉使用者恢复方式");
    }

    @Test
    void generate_shouldRejectBlankWarehouseId() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        WmsLocationGenDTO dto = range(1, 1, 1, 6, 1, 24);
        dto.setWarehouseId(null);
        assertThrows(RuntimeException.class, () -> service.generate(dto));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationServiceImplTest' -DskipTests=false`
Expected: 编译失败 —— `WmsLocationGenDTO` / `WmsLocationGenResultVO` / `IWmsLocationService` / `WmsLocationServiceImpl` 不存在。

- [ ] **Step 3: 写入参与出参**

`dto/WmsLocationGenDTO.java`：

```java
package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 库位批量生成入参（含闭区间的排/列/层范围）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "库位批量生成入参")
public class WmsLocationGenDTO {

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "起始排")
    private Integer rowFrom;

    @Schema(description = "结束排（含）")
    private Integer rowTo;

    @Schema(description = "起始列")
    private Integer colFrom;

    @Schema(description = "结束列（含）")
    private Integer colTo;

    @Schema(description = "起始层")
    private Integer layerFrom;

    @Schema(description = "结束层（含）")
    private Integer layerTo;
}
```

`vo/WmsLocationGenResultVO.java`：

```java
package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 库位批量生成结果
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "库位批量生成结果")
public class WmsLocationGenResultVO {

    /** 本次实际插入的条数。 */
    @Schema(description = "本次实际插入的条数")
    private int generated;

    /** 因已存在而跳过的条数。{@code generated + skipped} 恒等于请求总数。 */
    @Schema(description = "因已存在而跳过的条数")
    private int skipped;
}
```

- [ ] **Step 4: 写服务接口与生成实现**

`service/IWmsLocationService.java`：

```java
package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsLocationService extends IService<WmsLocation> {

    /**
     * 按闭区间范围批量生成库位。
     *
     * <p><b>幂等</b>：靠 {@code wms_location} 的两个唯一索引与 {@code INSERT IGNORE}，
     * 重跑同一范围是安全的 —— 已存在的被跳过并计入 {@link WmsLocationGenResultVO#getSkipped()}。
     *
     * <p><b>不做整体回滚</b>（spec §5.5.1）：某批失败即停止，异常文案里带上**已生成条数**与恢复方式。
     * 单次最多 5 万行，开一个大事务会把连接池拖死。
     *
     * @param dto 库区与范围
     * @return 实际生成数与跳过数
     */
    WmsLocationGenResultVO generate(WmsLocationGenDTO dto);
}
```

`service/impl/WmsLocationServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsLocationCodeGenerator;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsLocationServiceImpl extends ServiceImpl<WmsLocationMapper, WmsLocation>
        implements IWmsLocationService {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Override
    public WmsLocationGenResultVO generate(WmsLocationGenDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWarehouseId())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(dto.getWarehouseId());
        validateDimensions(warehouse);
        long total = validateRange(dto, warehouse);

        // 审计字段取一次复用：逐行调 WmsUtils.fillCreate 会做 5 万次 SecurityUtils 查询
        String username = WmsUtils.currentUsername();
        Date now = new Date();
        int generated = 0;
        List<WmsLocation> batch = new ArrayList<>(WmsConstants.LOCATION_GEN_BATCH_SIZE);
        try {
            for (int row = dto.getRowFrom(); row <= dto.getRowTo(); row++) {
                for (int col = dto.getColFrom(); col <= dto.getColTo(); col++) {
                    for (int layer = dto.getLayerFrom(); layer <= dto.getLayerTo(); layer++) {
                        batch.add(buildLocation(warehouse, row, col, layer, username, now));
                        if (batch.size() >= WmsConstants.LOCATION_GEN_BATCH_SIZE) {
                            generated += baseMapper.insertIgnoreBatch(batch);
                            batch.clear();
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                generated += baseMapper.insertIgnoreBatch(batch);
            }
        } catch (Exception e) {
            log.error("库区[{}]批量生成库位失败，已生成 {} 个，范围 R{}~{} C{}~{} L{}~{}：{}",
                    warehouse.getWarehouseCode(), generated,
                    dto.getRowFrom(), dto.getRowTo(),
                    dto.getColFrom(), dto.getColTo(),
                    dto.getLayerFrom(), dto.getLayerTo(), e.getMessage(), e);
            throw new RuntimeException("已生成 " + generated + " 个后失败：" + e.getMessage()
                    + "。重跑同一范围可安全续做（已生成的会自动跳过）。");
        }
        int skipped = (int) (total - generated);
        log.info("库区[{}]批量生成库位完成：请求 {} 个，新生成 {} 个，跳过 {} 个",
                warehouse.getWarehouseCode(), total, generated, skipped);
        return new WmsLocationGenResultVO(generated, skipped);
    }

    /**
     * 维度守卫。
     *
     * <p>库区维度可以自由增减（spec §5.2），但**为空或非正数时不允许生成** ——
     * 否则会把 NULL 当成上限，算出满屏 {@code null} 段。
     * 线边柜这类不适用网格模型的库区就是靠这条挡住的。
     */
    private void validateDimensions(WmsWarehouse warehouse) {
        if (warehouse.getRowCount() == null || warehouse.getRowCount() <= 0
                || warehouse.getColCount() == null || warehouse.getColCount() <= 0
                || warehouse.getLayerCount() == null || warehouse.getLayerCount() <= 0) {
            throw new RuntimeException("库区未配置网格维度，请先在库区上填写排 / 列 / 层上限");
        }
    }

    /**
     * 范围与包络守卫，返回请求总数。
     *
     * <p>库区维度只是「生成上限」，不参与编码计算，故这里只做边界与上限校验。
     */
    private long validateRange(WmsLocationGenDTO dto, WmsWarehouse warehouse) {
        Integer rowFrom = dto.getRowFrom();
        Integer rowTo = dto.getRowTo();
        Integer colFrom = dto.getColFrom();
        Integer colTo = dto.getColTo();
        Integer layerFrom = dto.getLayerFrom();
        Integer layerTo = dto.getLayerTo();
        if (rowFrom == null || rowTo == null || colFrom == null || colTo == null
                || layerFrom == null || layerTo == null) {
            throw new RuntimeException("库位范围不合法");
        }
        if (rowFrom < 1 || colFrom < 1 || layerFrom < 1
                || rowFrom > rowTo || colFrom > colTo || layerFrom > layerTo) {
            throw new RuntimeException("库位范围不合法");
        }
        if (rowTo > warehouse.getRowCount()) {
            throw new RuntimeException("第 " + rowTo + " 排超出库区排数上限 " + warehouse.getRowCount());
        }
        if (colTo > warehouse.getColCount()) {
            throw new RuntimeException("第 " + colTo + " 列超出库区列数上限 " + warehouse.getColCount());
        }
        if (layerTo > warehouse.getLayerCount()) {
            throw new RuntimeException("第 " + layerTo + " 层超出库区层数上限 " + warehouse.getLayerCount());
        }
        long total = (long) (rowTo - rowFrom + 1) * (colTo - colFrom + 1) * (layerTo - layerFrom + 1);
        if (total > WmsConstants.LOCATION_GEN_MAX_PER_REQUEST) {
            throw new RuntimeException("单次最多生成 " + WmsConstants.LOCATION_GEN_MAX_PER_REQUEST
                    + " 个库位，本次 " + total + " 个，请按层 / 列分批");
        }
        // 编码长度只随各段数字位数单调增长，故取 (rowTo, colTo, layerTo) 即最坏情形
        String longestCode = WmsLocationCodeGenerator.locationCode(
                warehouse.getWarehouseCode(), rowTo, colTo, layerTo);
        if (longestCode.length() > WmsConstants.LOCATION_CODE_MAX_LENGTH) {
            throw new RuntimeException("库区编码过长，生成的库位编码将超过 "
                    + WmsConstants.LOCATION_CODE_MAX_LENGTH + " 字符（示例：" + longestCode
                    + "），请缩短库区编码或缩小范围");
        }
        return total;
    }

    /**
     * 造一条库位。
     *
     * <p><b>id 必须显式生成</b>：{@code insertIgnoreBatch} 是手写 XML，
     * MyBatis-Plus 的 {@code IdType.ASSIGN_ID} 不会生效，不设就是 null。
     */
    private WmsLocation buildLocation(WmsWarehouse warehouse, int row, int col, int layer,
                                      String username, Date now) {
        WmsLocation location = new WmsLocation();
        location.setId(IdWorker.getIdStr());
        location.setLocationCode(WmsLocationCodeGenerator.locationCode(
                warehouse.getWarehouseCode(), row, col, layer));
        location.setWarehouseId(warehouse.getId());
        location.setRowNo(row);
        location.setColNo(col);
        location.setLayerNo(layer);
        location.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        location.setDelFlag(0);
        location.setCreateBy(username);
        location.setCreateTime(now);
        location.setUpdateBy(username);
        location.setUpdateTime(now);
        return location;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationServiceImplTest' -DskipTests=false`
Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

> 若 `generate_shouldReportPartialCountOnFailure` 报「第 2 批没被调用」，检查循环里的 flush 条件
> 是否写成 `> ` 而不是 `>=`（每批 1000，满 1000 立即 flush）。

- [ ] **Step 6: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImplTest.java
git commit -m "feat(wms): BD-003 库位批量生成（编码规则/幂等/分批/5万与包络守卫）"
```

---

## Task 6: 库位属性、释放与删除守卫（**约束②**）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsLocationAttrDTO.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsLocationService.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImpl.java`
- Modify: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImplTest.java`

**Interfaces:**
- Consumes: Task 5 的 `WmsLocationServiceImpl`；Task 4 的 `IWmsWarehouseService.requireEnabledWarehouse`
- Produces（Task 7 的控制器要用，签名必须逐字一致）：
  ```java
  List<IWmsLocationService 新增方法>
      boolean checkCodeExists(String code, String id);
      void addLocation(WmsLocation location);
      void editLocation(WmsLocation location);
      void updateAttr(WmsLocationAttrDTO dto);
      void release(String id);
      void removeLocation(String id);
      long countOutOfEnvelope(String warehouseId);        // ← Task 7 用
  ```
  `WmsLocationAttrDTO`：`String id`、`BigDecimal maxWeight`、`String status`
- **顺带重构**：把 Task 5 里内联的维度/包络校验抽成
  `private void requireWithinEnvelope(WmsWarehouse warehouse, int row, int col, int layer)`，
  由 `validateRange` 与 `addLocation` 共用（DRY）。Task 5 已有的三个包络用例必须仍然绿。

- [ ] **Step 1: 写失败测试（追加到既有测试类）**

在 `WmsLocationServiceImplTest.java` 末尾（最后一个 `}` 之前）追加：

```java
    // ============================================================ Task 6: 属性 / 释放 / 删除

    private static WmsLocation location(String id, String status) {
        WmsLocation l = new WmsLocation().setLocationCode("LB-R01-C06-L24")
                .setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(24).setStatus(status);
        l.setId(id);
        return l;
    }

    /** 给已有 service 加一个「按 id 返回给定库位」的桩。 */
    private static void stubGetById(WmsLocationServiceImpl service, WmsLocation existing) {
        org.mockito.Mockito.doReturn(existing).when(service).getById(existing.getId());
    }

    // ------------------------------------------------------------ 约束②：库位内有物料时禁改属性

    @Test
    void updateAttr_shouldRejectWhenOccupied() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        dto.setMaxWeight(new java.math.BigDecimal("500.000"));
        dto.setStatus(WmsConstants.LOCATION_STATUS_FAULT);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.updateAttr(dto));
        assertEquals("库位内有物料，不允许修改属性", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void updateAttr_shouldAllowWhenIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));
        org.mockito.Mockito.doReturn(true).when(service).updateById(any());

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        dto.setMaxWeight(new java.math.BigDecimal("500.000"));

        service.updateAttr(dto);

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).updateById(captor.capture());
        assertEquals(new java.math.BigDecimal("500.000"), captor.getValue().getMaxWeight());
        assertNotNull(captor.getValue().getUpdateTime(), "审计字段应由 WmsUtils.fillUpdate 填入");
    }

    @Test
    void updateAttr_shouldRejectMissingLocation() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(null).when(service).getById("nope");
        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("nope");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.updateAttr(dto));
        assertEquals("库位不存在", ex.getMessage());
    }

    // ------------------------------------------------------------ 释放（spec §5.3 增补）

    /**
     * 释放的由来：若「占用」既代表有物料、又阻断一切状态修改，被误置为占用的库位就
     * **永远无法人工恢复**，约束②会变成锁死缺陷。故单开一个语义明确的「占用 → 空闲」动作。
     */
    @Test
    void release_shouldTurnOccupiedIntoIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));
        org.mockito.Mockito.doReturn(true).when(service).updateById(any());

        service.release("L1");

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).updateById(captor.capture());
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, captor.getValue().getStatus());
    }

    @Test
    void release_shouldRejectWhenNotOccupied() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.release("L1"));
        assertEquals("仅占用状态的库位可释放", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).updateById(any());
    }

    // ------------------------------------------------------------ 删除守卫

    @Test
    void removeLocation_shouldRejectWhenNotIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeLocation("L1"));
        assertEquals("库位已占用或有故障，不允许删除", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).removeById(any(String.class));
    }

    @Test
    void removeLocation_shouldRejectWhenFault() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_FAULT));
        assertThrows(RuntimeException.class, () -> service.removeLocation("L1"));
    }

    @Test
    void removeLocation_shouldDeleteWhenIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));
        org.mockito.Mockito.doReturn(true).when(service).removeById(any(String.class));
        service.removeLocation("L1");
        verify(service).removeById("L1");
    }

    // ------------------------------------------------------------ 手工补录单个库位

    @Test
    void addLocation_shouldGenerateCodeFromCoordinates() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(0L).when(service).count(any(Wrapper.class));
        org.mockito.Mockito.doReturn(true).when(service).save(any());

        service.addLocation(new WmsLocation().setWarehouseId("W1")
                .setRowNo(1).setColNo(6).setLayerNo(24));

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).save(captor.capture());
        assertEquals("LB-R01-C06-L24", captor.getValue().getLocationCode(), "编码由后端按规则生成，不接受前端传入");
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, captor.getValue().getStatus());
    }

    @Test
    void addLocation_shouldRejectDuplicateCoordinates() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addLocation(
                new WmsLocation().setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(24)));
        assertEquals("该坐标的库位已存在，请重新输入", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void addLocation_shouldRejectBeyondEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addLocation(
                new WmsLocation().setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(30)));
        assertEquals("第 30 层超出库区层数上限 24", ex.getMessage());
    }

    // ------------------------------------------------------------ 编辑：坐标不可改

    @Test
    void editLocation_shouldRejectCoordinateChange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editLocation(
                new WmsLocation().setId("L1").setRowNo(1).setColNo(6).setLayerNo(10)));
        assertEquals("库位坐标不可修改，如需变更请删除后重新生成", ex.getMessage());
    }

    @Test
    void editLocation_shouldApplyAttributeGuard() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editLocation(
                new WmsLocation().setId("L1").setMaxWeight(new java.math.BigDecimal("1.000"))));
        assertEquals("库位内有物料，不允许修改属性", ex.getMessage());
    }

    // ------------------------------------------------------------ 越界计数（Task 7 用）

    @Test
    void countOutOfEnvelope_shouldCountLocationsBeyondDeclaredDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 10));
        org.mockito.Mockito.doReturn(84L).when(service).count(any(Wrapper.class));
        assertEquals(84L, service.countOutOfEnvelope("W1"));
    }

    @Test
    void countOutOfEnvelope_shouldReturnZeroWhenNoDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", null, null, null));
        assertEquals(0L, service.countOutOfEnvelope("W1"));
    }
```

并在文件顶部补 import：

```java
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationServiceImplTest' -DskipTests=false`
Expected: 编译失败 —— `WmsLocationAttrDTO` 不存在、`updateAttr` / `release` / `addLocation` 等方法未定义。

- [ ] **Step 3: 写入参 DTO**

`dto/WmsLocationAttrDTO.java`：

```java
package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * @Description: 库位属性更新入参（承重 / 状态）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "库位属性更新入参")
public class WmsLocationAttrDTO {

    @Schema(description = "库位ID")
    private String id;

    @Schema(description = "承重")
    private BigDecimal maxWeight;

    @Schema(description = "状态：空闲/占用/故障；不传则不改")
    private String status;
}
```

- [ ] **Step 4: 扩接口**

在 `IWmsLocationService` 的 `generate` 之后追加：

```java
    /** 库位编码唯一性校验（蓝图异常①：库位编码重复 → 提示并拒绝保存）。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    /**
     * 手工补录单个库位（补网格空洞）。
     *
     * <p>编码<b>由后端按规则生成</b>，不接受前端传入 —— 否则编码与坐标会脱节，
     * 而整个幂等与唯一性设计都建立在「编码是坐标的函数」之上。
     */
    void addLocation(WmsLocation location);

    /**
     * 编辑库位。
     *
     * <p>坐标不可改（改了编码就与坐标脱节）；可变字段只有承重与状态，
     * 故与 {@link #updateAttr} 共用同一守卫。
     */
    void editLocation(WmsLocation location);

    /** 更新承重 / 状态。库位为「占用」时拒绝（约束②）。 */
    void updateAttr(WmsLocationAttrDTO dto);

    /**
     * 释放库位：占用 → 空闲。
     *
     * <p>存在的理由：若「占用」既表示有物料、又阻断一切状态修改，被误置为占用的库位
     * 将永远无法人工恢复，约束②会变成锁死缺陷。本动作语义明确 —— 人工确认该库位已清空 ——
     * 且不是绕过约束的通用后门（改承重仍被拒）。
     */
    void release(String id);

    /** 逻辑删除；仅「空闲」库位可删。 */
    void removeLocation(String id);

    /** 位于库区当前维度之外的库位数。缩维后不为 0，用于提示而非拦截。 */
    long countOutOfEnvelope(String warehouseId);
```

- [ ] **Step 5: 实现（含把包络校验抽成共用方法）**

在 `WmsLocationServiceImpl` 中：

**(a) 用共用方法替换 `validateRange` 里内联的包络三连判断。** 把 `validateRange` 改成：

```java
    private long validateRange(WmsLocationGenDTO dto, WmsWarehouse warehouse) {
        Integer rowFrom = dto.getRowFrom();
        Integer rowTo = dto.getRowTo();
        Integer colFrom = dto.getColFrom();
        Integer colTo = dto.getColTo();
        Integer layerFrom = dto.getLayerFrom();
        Integer layerTo = dto.getLayerTo();
        if (rowFrom == null || rowTo == null || colFrom == null || colTo == null
                || layerFrom == null || layerTo == null) {
            throw new RuntimeException("库位范围不合法");
        }
        if (rowFrom < 1 || colFrom < 1 || layerFrom < 1
                || rowFrom > rowTo || colFrom > colTo || layerFrom > layerTo) {
            throw new RuntimeException("库位范围不合法");
        }
        // 上限取范围末端：各段长度随数值单调增长，故末端即最坏情形
        requireWithinEnvelope(warehouse, rowTo, colTo, layerTo);
        long total = (long) (rowTo - rowFrom + 1) * (colTo - colFrom + 1) * (layerTo - layerFrom + 1);
        if (total > WmsConstants.LOCATION_GEN_MAX_PER_REQUEST) {
            throw new RuntimeException("单次最多生成 " + WmsConstants.LOCATION_GEN_MAX_PER_REQUEST
                    + " 个库位，本次 " + total + " 个，请按层 / 列分批");
        }
        String longestCode = WmsLocationCodeGenerator.locationCode(
                warehouse.getWarehouseCode(), rowTo, colTo, layerTo);
        if (longestCode.length() > WmsConstants.LOCATION_CODE_MAX_LENGTH) {
            throw new RuntimeException("库区编码过长，生成的库位编码将超过 "
                    + WmsConstants.LOCATION_CODE_MAX_LENGTH + " 字符（示例：" + longestCode
                    + "），请缩短库区编码或缩小范围");
        }
        return total;
    }
```

并把原来的 `validateDimensions` 替换为 `requireWithinEnvelope`（它同时承担「维度未配置」的守卫）：

```java
    /**
     * 维度与包络守卫，批量生成与手工补录共用。
     *
     * <p>库区维度可以自由增减（spec §5.2），但**为空或非正数时不允许落库位** ——
     * 否则会把 NULL 当成上限，算出满屏 {@code null} 段。线边柜这类不适用网格模型的库区
     * 就是靠这条挡住的。
     */
    private void requireWithinEnvelope(WmsWarehouse warehouse, int row, int col, int layer) {
        if (warehouse.getRowCount() == null || warehouse.getRowCount() <= 0
                || warehouse.getColCount() == null || warehouse.getColCount() <= 0
                || warehouse.getLayerCount() == null || warehouse.getLayerCount() <= 0) {
            throw new RuntimeException("库区未配置网格维度，请先在库区上填写排 / 列 / 层上限");
        }
        if (row > warehouse.getRowCount()) {
            throw new RuntimeException("第 " + row + " 排超出库区排数上限 " + warehouse.getRowCount());
        }
        if (col > warehouse.getColCount()) {
            throw new RuntimeException("第 " + col + " 列超出库区列数上限 " + warehouse.getColCount());
        }
        if (layer > warehouse.getLayerCount()) {
            throw new RuntimeException("第 " + layer + " 层超出库区层数上限 " + warehouse.getLayerCount());
        }
    }
```

同时把 `generate` 开头的 `validateDimensions(warehouse);` 删掉（改由 `validateRange` → `requireWithinEnvelope` 承担）。

**(b) 追加新方法：**

```java
    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsLocation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("location_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addLocation(WmsLocation location) {
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(location.getWarehouseId());
        Integer row = location.getRowNo();
        Integer col = location.getColNo();
        Integer layer = location.getLayerNo();
        if (row == null || col == null || layer == null) {
            throw new RuntimeException("排 / 列 / 层不能为空");
        }
        if (row < 1 || col < 1 || layer < 1) {
            throw new RuntimeException("排 / 列 / 层必须为正整数");
        }
        requireWithinEnvelope(warehouse, row, col, layer);
        long exists = this.count(new QueryWrapper<WmsLocation>()
                .eq("warehouse_id", location.getWarehouseId())
                .eq("row_no", row).eq("col_no", col).eq("layer_no", layer));
        if (exists > 0) {
            throw new RuntimeException("该坐标的库位已存在，请重新输入");
        }
        location.setLocationCode(
                WmsLocationCodeGenerator.locationCode(warehouse.getWarehouseCode(), row, col, layer));
        if (oConvertUtils.isEmpty(location.getStatus())) {
            location.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        }
        WmsUtils.fillCreate(location);
        this.save(location);
    }

    @Override
    public void editLocation(WmsLocation location) {
        WmsLocation existing = requireExisting(location.getId());
        if (coordinateChanged(location, existing)) {
            throw new RuntimeException("库位坐标不可修改，如需变更请删除后重新生成");
        }
        applyAttr(location.getId(), location.getMaxWeight(), location.getStatus(), existing);
    }

    @Override
    public void updateAttr(WmsLocationAttrDTO dto) {
        if (dto == null) {
            throw new RuntimeException("库位不存在");
        }
        WmsLocation existing = requireExisting(dto.getId());
        applyAttr(dto.getId(), dto.getMaxWeight(), dto.getStatus(), existing);
    }

    @Override
    public void release(String id) {
        WmsLocation existing = requireExisting(id);
        if (!WmsConstants.LOCATION_STATUS_OCCUPIED.equals(existing.getStatus())) {
            throw new RuntimeException("仅占用状态的库位可释放");
        }
        WmsLocation update = new WmsLocation();
        update.setId(id);
        update.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        WmsUtils.fillUpdate(update);
        this.updateById(update);
    }

    @Override
    public void removeLocation(String id) {
        WmsLocation existing = requireExisting(id);
        if (!WmsConstants.LOCATION_STATUS_IDLE.equals(existing.getStatus())) {
            throw new RuntimeException("库位已占用或有故障，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public long countOutOfEnvelope(String warehouseId) {
        WmsWarehouse warehouse = oConvertUtils.isEmpty(warehouseId) ? null : wmsWarehouseService.getById(warehouseId);
        if (warehouse == null
                || warehouse.getRowCount() == null || warehouse.getColCount() == null
                || warehouse.getLayerCount() == null) {
            return 0L;
        }
        // 维度是「声明」，库位是「已存在的资产」：缩维后两者可能不一致。
        // 这里只统计、不拦截 —— 由人决定是补回维度还是删库位。
        return this.count(new QueryWrapper<WmsLocation>()
                .eq("warehouse_id", warehouseId)
                .and(w -> w.gt("row_no", warehouse.getRowCount())
                        .or().gt("col_no", warehouse.getColCount())
                        .or().gt("layer_no", warehouse.getLayerCount())));
    }

    /** 取库位，不存在直接抛。 */
    private WmsLocation requireExisting(String id) {
        WmsLocation existing = oConvertUtils.isEmpty(id) ? null : this.getById(id);
        if (existing == null) {
            throw new RuntimeException("库位不存在");
        }
        return existing;
    }

    private boolean coordinateChanged(WmsLocation submitted, WmsLocation existing) {
        return (submitted.getRowNo() != null && !submitted.getRowNo().equals(existing.getRowNo()))
                || (submitted.getColNo() != null && !submitted.getColNo().equals(existing.getColNo()))
                || (submitted.getLayerNo() != null && !submitted.getLayerNo().equals(existing.getLayerNo()));
    }

    /**
     * 应用属性变更（承重 / 状态），约束②在此强制。
     *
     * <p>{@code null} 字段不写库（MyBatis-Plus 默认忽略 null），故「只改承重」是安全的。
     */
    private void applyAttr(String id, java.math.BigDecimal maxWeight, String status, WmsLocation existing) {
        if (WmsConstants.LOCATION_STATUS_OCCUPIED.equals(existing.getStatus())) {
            throw new RuntimeException("库位内有物料，不允许修改属性");
        }
        WmsLocation update = new WmsLocation();
        update.setId(id);
        update.setMaxWeight(maxWeight);
        update.setStatus(status);
        WmsUtils.fillUpdate(update);
        this.updateById(update);
    }
```

补 import：`com.baomidou.mybatisplus.core.conditions.query.QueryWrapper`、`org.jeecg.modules.wms.dto.WmsLocationAttrDTO`、`java.math.BigDecimal`。

- [ ] **Step 6: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationServiceImplTest' -DskipTests=false`
Expected: `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`
（**Task 5 的 14 个用例必须仍然全绿** —— 这验证 `requireWithinEnvelope` 的抽取没有改变行为。）

- [ ] **Step 7: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsLocationAttrDTO.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsLocationService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsLocationServiceImplTest.java
git commit -m "feat(wms): BD-003 库位属性/释放/删除守卫与手工补录（约束②）"
```

---

## Task 7: 库位控制器与库区越界计数

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsLocationController.java`
- Modify: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsWarehouseController.java`（`queryById` 回填越界数；新增 `outOfEnvelopeCount` 端点）
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/controller/WmsLocationControllerTest.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/controller/WmsWarehouseControllerTest.java`

**Interfaces:**
- Consumes: Task 4 的 `WmsWarehouseController` / `IWmsWarehouseService.queryIdsByType`；Task 5/6 的 `IWmsLocationService` 全部方法
- Produces: REST 端点（前端 Task 12 依赖）
  `GET /wms/location/{list,queryById,checkCode}`、`POST /wms/location/{add,generate,updateAttr,release}`、
  `PUT|POST /wms/location/edit`、`DELETE /wms/location/{delete,deleteBatch}`；
  以及 `GET /wms/warehouse/outOfEnvelopeCount?warehouseId=`

**两个必须处理的坑**

1. **Review Focus #2 — `warehouseType` 匹配不到库区时**：库区 id 集合为空，
   MyBatis-Plus 会把 `in("warehouse_id", 空集合)` 拼成 `IN ()`，那是 SQL 语法错误（500）。
   **必须在 `in` 之前显式返回空页**，而不是交给框架。
2. **`WmsLocation.warehouseName` 是非持久化字段**：交给 `QueryGenerator` 前**必须先置空**，
   否则会拼出不存在的 `warehouse_name` 列条件（BD-002 已踩过同一个坑）。

- [ ] **Step 1: 写失败测试（库位控制器）**

创建 `src/test/java/org/jeecg/modules/wms/controller/WmsLocationControllerTest.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库位控制器薄层测试（不启 Spring 上下文）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationControllerTest {

    private static WmsLocationController newController(IWmsLocationService locationService,
                                                       IWmsWarehouseService warehouseService) {
        WmsLocationController controller = new WmsLocationController();
        WmsTestSupport.inject(controller, "service", locationService);
        WmsTestSupport.inject(controller, "wmsWarehouseService", warehouseService);
        return controller;
    }

    private static WmsLocation location(String id, String warehouseId) {
        WmsLocation l = new WmsLocation().setLocationCode("LB-R01-C06-L24")
                .setWarehouseId(warehouseId).setRowNo(1).setColNo(6).setLayerNo(24)
                .setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        l.setId(id);
        return l;
    }

    // ------------------------------------------------------------ Review Focus #2：IN () 保护

    /**
     * 传一个不存在的 warehouseType 时，库区 id 集合为空。
     * 若不处理，MyBatis-Plus 会拼出 {@code IN ()} → SQL 语法错误 500，
     * 而使用者期望的只是「无匹配记录」。
     */
    @Test
    void list_shouldReturnEmptyPageWhenWarehouseTypeMatchesNothing() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryIdsByType("不存在的类型")).thenReturn(Collections.emptyList());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), "不存在的类型", 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        assertNotNull(result.getResult());
        assertEquals(0, result.getResult().getRecords().size());
        verify(locationService, never()).page(any(), any());
    }

    @Test
    void list_shouldFilterByWarehouseIdsOfType() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryIdsByType(WmsConstants.WAREHOUSE_TYPE_STEREO))
                .thenReturn(Arrays.asList("W1", "W2"));
        when(locationService.page(any(), any())).thenReturn(new Page<>());
        when(warehouseService.listByIds(any())).thenReturn(Collections.emptyList());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), WmsConstants.WAREHOUSE_TYPE_STEREO, 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        verify(locationService).page(any(), any());
    }

    // ------------------------------------------------------------ 库区名回填（含已删除库区）

    @Test
    void list_shouldFillWarehouseName() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        Page<WmsLocation> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(location("L1", "W1")));
        when(locationService.page(any(), any())).thenReturn(page);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseName("半成品立库");
        warehouse.setId("W1");
        when(warehouseService.listByIds(any())).thenReturn(Arrays.asList(warehouse));
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), null, 1, 10, new MockHttpServletRequest());

        assertEquals("半成品立库", result.getResult().getRecords().get(0).getWarehouseName());
    }

    /** Review Focus #3：库区被逻辑删除后 listByIds 查不到，回填必须留 null 而不是 NPE。 */
    @Test
    void list_shouldTolerateMissingWarehouse() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        Page<WmsLocation> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(location("L1", "W-DELETED")));
        when(locationService.page(any(), any())).thenReturn(page);
        when(warehouseService.listByIds(any())).thenReturn(Collections.emptyList());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), null, 1, 10, new MockHttpServletRequest());

        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    // ------------------------------------------------------------ 生成（验收标准①）

    @Test
    void generate_shouldReturnCounts() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.generate(any())).thenReturn(new WmsLocationGenResultVO(144, 0));
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        WmsLocationGenDTO dto = new WmsLocationGenDTO();
        dto.setWarehouseId("W1");
        Result<WmsLocationGenResultVO> result = controller.generate(dto);

        assertTrue(result.isSuccess());
        assertEquals(144, result.getResult().getGenerated());
        assertEquals(0, result.getResult().getSkipped());
    }

    @Test
    void generate_shouldSurfaceServiceError() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.generate(any()))
                .thenThrow(new RuntimeException("单次最多生成 50000 个库位，本次 604220 个，请按层 / 列分批"));
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<WmsLocationGenResultVO> result = controller.generate(new WmsLocationGenDTO());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("50000"));
    }

    // ------------------------------------------------------------ 约束②：占用时改属性

    @Test
    void updateAttr_shouldSurfaceMaterialGuard() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("库位内有物料，不允许修改属性"))
                .when(locationService).updateAttr(any());
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        Result<?> result = controller.updateAttr(dto);

        assertFalse(result.isSuccess());
        assertEquals("库位内有物料，不允许修改属性", result.getMessage());
    }

    @Test
    void release_shouldSurfaceGuard() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("仅占用状态的库位可释放"))
                .when(locationService).release("L1");
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<?> result = controller.release("L1");

        assertFalse(result.isSuccess());
        assertEquals("仅占用状态的库位可释放", result.getMessage());
    }

    // ------------------------------------------------------------ 编码唯一（蓝图异常①）

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.checkCodeExists("LB-R01-C06-L24", null)).thenReturn(true);
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<Boolean> result = controller.checkCode("LB-R01-C06-L24", null);

        assertFalse(result.isSuccess());
        assertEquals("库位编码已存在，请重新输入", result.getMessage());
    }

    @Test
    void checkCode_shouldPassWhenUnique() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.checkCodeExists(any(), any())).thenReturn(false);
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        assertTrue(controller.checkCode("LB-R01-C06-L25", null).isSuccess());
    }
}
```

- [ ] **Step 2: 写失败测试（库区控制器追加）**

创建 `src/test/java/org/jeecg/modules/wms/controller/WmsWarehouseControllerTest.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * @Description: 库区控制器薄层测试
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsWarehouseControllerTest {

    private static WmsWarehouse warehouse(String id, Integer layers) {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode("LB").setWarehouseName("半成品立库")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO)
                .setRowCount(1).setColCount(6).setLayerCount(layers)
                .setStatus(WmsConstants.STATUS_ENABLE);
        w.setId(id);
        return w;
    }

    private static WmsWarehouseController newController(IWmsWarehouseService warehouseService,
                                                        IWmsLocationService locationService) {
        WmsWarehouseController controller = new WmsWarehouseController();
        WmsTestSupport.inject(controller, "service", warehouseService);
        WmsTestSupport.inject(controller, "wmsLocationService", locationService);
        return controller;
    }

    @Test
    void treeList_shouldDelegate() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryTreeList()).thenReturn(java.util.Collections.emptyList());
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertTrue(controller.treeList().isSuccess());
    }

    /** 询详情时回填越界库位数，用于「有 N 个库位位于当前维度之外」的提示。 */
    @Test
    void queryById_shouldFillOutOfEnvelopeCount() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.getById("W1")).thenReturn(warehouse("W1", 10));
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.countOutOfEnvelope("W1")).thenReturn(84L);
        WmsWarehouseController controller = newController(warehouseService, locationService);

        Result<WmsWarehouse> result = controller.queryById("W1");

        assertTrue(result.isSuccess());
        assertEquals(84L, result.getResult().getOutOfEnvelopeCount());
    }

    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.getById("nope")).thenReturn(null);
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertFalse(controller.queryById("nope").isSuccess());
    }

    @Test
    void delete_shouldSurfaceGuard() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        doThrow(new RuntimeException("该库区下存在库位，不允许删除")).when(warehouseService).removeWarehouse("W1");
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        Result<?> result = controller.delete("W1");

        assertFalse(result.isSuccess());
        assertEquals("该库区下存在库位，不允许删除", result.getMessage());
    }

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.checkCodeExists("LB", null)).thenReturn(true);
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertFalse(controller.checkCode("LB", null).isSuccess());
    }

    /** 维度自由增减：2979 层照样保存成功，控制器不得加任何额外校验。 */
    @Test
    void edit_shouldAllowShrinkingDimensions() {
        IWmsWarehouseService warehouseService = spy(new org.jeecg.modules.wms.service.impl.WmsWarehouseServiceImpl());
        doReturn(0L).when(warehouseService).count(any(Wrapper.class));
        doReturn(true).when(warehouseService).updateById(any());
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertTrue(controller.edit(warehouse("W1", 10)).isSuccess());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationControllerTest+WmsWarehouseControllerTest' -DskipTests=false`
Expected: 编译失败 —— `WmsLocationController` 不存在；`WmsWarehouseController` 无 `wmsLocationService` 字段；`WmsWarehouse` 无 `setOutOfEnvelopeCount`（Task 2 已加，故此条应已满足）。

- [ ] **Step 4: 写库位控制器**

`controller/WmsLocationController.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-库位")
@RestController
@RequestMapping("/wms/location")
@Slf4j
public class WmsLocationController extends JeecgController<WmsLocation, IWmsLocationService> {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    /**
     * 分页列表查询。
     *
     * <p>{@code warehouseType} 支持按库区类型过滤（左侧树点类型节点）。实现方式是
     * 先查该类型下的库区 id 再 {@code in}，**避免写 JOIN**；但**id 集合为空时必须直接返回空页** ——
     * MyBatis-Plus 会把空集合拼成 {@code IN ()}，那是 SQL 语法错误，而使用者期望的
     * 只是「无匹配记录」。
     */
    @Operation(summary = "库位-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsLocation>> queryPageList(WmsLocation wmsLocation,
                                                    @RequestParam(name = "warehouseType", required = false) String warehouseType,
                                                    @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                    HttpServletRequest req) {
        // 非持久化字段必须先置空，否则 QueryGenerator 会拼出不存在的 warehouse_name 列条件
        wmsLocation.setWarehouseName(null);
        QueryWrapper<WmsLocation> queryWrapper = QueryGenerator.initQueryWrapper(wmsLocation, req.getParameterMap());
        if (oConvertUtils.isNotEmpty(warehouseType)) {
            List<String> warehouseIds = wmsWarehouseService.queryIdsByType(warehouseType);
            if (warehouseIds.isEmpty()) {
                return Result.OK(new Page<>(pageNo, pageSize));
            }
            queryWrapper.in("warehouse_id", warehouseIds);
        }
        queryWrapper.orderByAsc("warehouse_id")
                .orderByAsc("layer_no").orderByAsc("row_no").orderByAsc("col_no");
        Page<WmsLocation> page = new Page<>(pageNo, pageSize);
        IPage<WmsLocation> pageList = service.page(page, queryWrapper);
        fillWarehouseNames(pageList.getRecords());
        return Result.OK(pageList);
    }

    @Operation(summary = "库位-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsLocation> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsLocation entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        fillWarehouseNames(java.util.Collections.singletonList(entity));
        return Result.OK(entity);
    }

    @Operation(summary = "库位-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("库位编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("库位编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "库位-批量生成", operateType = 2)
    @Operation(summary = "库位-按范围批量生成")
    @PostMapping(value = "/generate")
    public Result<WmsLocationGenResultVO> generate(@RequestBody WmsLocationGenDTO dto) {
        try {
            return Result.OK(service.generate(dto));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-添加", operateType = 2)
    @Operation(summary = "库位-手工新增单个")
    @PostMapping(value = "/add")
    public Result<WmsLocation> add(@RequestBody WmsLocation wmsLocation) {
        try {
            service.addLocation(wmsLocation);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-编辑", operateType = 3)
    @Operation(summary = "库位-编辑（坐标不可改，仅承重/状态）")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsLocation> edit(@RequestBody WmsLocation wmsLocation) {
        try {
            service.editLocation(wmsLocation);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-属性更新", operateType = 3)
    @Operation(summary = "库位-属性更新（承重/状态；占用时拒绝）")
    @PostMapping(value = "/updateAttr")
    public Result<?> updateAttr(@RequestBody WmsLocationAttrDTO dto) {
        try {
            service.updateAttr(dto);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-释放", operateType = 3)
    @Operation(summary = "库位-释放（占用 → 空闲）")
    @PostMapping(value = "/release")
    public Result<?> release(@RequestParam(name = "id", required = true) String id) {
        try {
            service.release(id);
            return Result.OK("释放成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-删除", operateType = 4)
    @Operation(summary = "库位-删除（仅空闲）")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeLocation(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-批量删除", operateType = 4)
    @Operation(summary = "库位-批量删除（仅空闲）")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        try {
            for (String id : ids.split(",")) {
                service.removeLocation(id);
            }
            return Result.OK("批量删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 回填库区名。
     *
     * <p>库区被逻辑删除时 {@code listByIds} 查不到它，此处留 {@code null} 而不是抛异常 ——
     * 列表要能显示「已删除库区的库位」，否则数据修复时反而看不到问题所在。
     */
    private void fillWarehouseNames(List<WmsLocation> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<String> warehouseIds = records.stream()
                .map(WmsLocation::getWarehouseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (warehouseIds.isEmpty()) {
            return;
        }
        List<WmsWarehouse> warehouses = wmsWarehouseService.listByIds(warehouseIds);
        Map<String, String> nameById = warehouses.stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName, (a, b) -> a));
        for (WmsLocation record : records) {
            record.setWarehouseName(nameById.get(record.getWarehouseId()));
        }
    }
}
```

- [ ] **Step 5: 改库区控制器（回填越界数）**

在 `WmsWarehouseController` 中：

(a) 追加字段与 import：

```java
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.springframework.beans.factory.annotation.Autowired;

    /** 越界库位数要查 wms_location，故由库位服务提供，避免库区服务反向依赖库位实体之外的东西。 */
    @Autowired
    private IWmsLocationService wmsLocationService;
```

(b) 把 `queryById` 改成：

```java
    @Operation(summary = "库区-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsWarehouse> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsWarehouse entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        entity.setOutOfEnvelopeCount(wmsLocationService.countOutOfEnvelope(id));
        return Result.OK(entity);
    }
```

(c) 追加端点：

```java
    /**
     * 位于当前维度之外的库位数。
     *
     * <p>维度是「声明」，库位是「已存在的资产」：缩维后两者可能不一致。
     * 系统**不拦截**这种不一致，只把它显示出来，由人决定是补回维度还是删库位。
     */
    @Operation(summary = "库区-越界库位数")
    @GetMapping(value = "/outOfEnvelopeCount")
    public Result<Long> outOfEnvelopeCount(@RequestParam(name = "warehouseId", required = true) String warehouseId) {
        return Result.OK(wmsLocationService.countOutOfEnvelope(warehouseId));
    }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsLocationControllerTest+WmsWarehouseControllerTest' -DskipTests=false`
Expected: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

- [ ] **Step 7: 全模块回归并提交**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='Wms*Test' -DskipTests=false`
Expected: 既有 4 个功能（物料/分类/供应商/订单）的测试与本次新增**全部通过**，无 Failure。

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/controller/
git commit -m "feat(wms): BD-003 库位控制器与库区越界计数（IN() 保护 + 库区名回填）"
```

---

## Task 8: 设备告警服务（聚合 + 节流）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsDeviceAlertService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsDeviceAlertServiceImpl.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceAlertServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 `WmsDevice` / `WmsDeviceMapper`；Task 1 的 `DEVICE_ALERT_TITLE` / `DEVICE_ALERT_THROTTLE_MINUTES` / `DEVICE_ALERT_TO_USER_KEY` / `ALERT_FROM_USER` / `ALERT_TO_USER`；平台 `ISysBaseAPI.sendSysAnnouncement(MessageDTO)`
- Produces（Task 9 / Task 11 依赖）：
  ```java
  public interface IWmsDeviceAlertService {
      /**
       * 聚合告警：对 devices 中通过节流判定的设备发一条站内消息，并为其打点 last_alert_time。
       * @param respectThrottle true=按 30 分钟窗口节流（上报/扫描路径）；false=不节流（绑定路径，人的即时操作）
       * @return 实际告警的设备编码列表（被节流跳过的不在其中）
       */
      List<String> alertOffline(List<WmsDevice> devices, boolean respectThrottle);

      /** 清空某设备的告警节流（恢复在线时调用），使后续再次离线能立即告警。 */
      void clearThrottle(String deviceId);
  }
  ```

**必须继承的既有教训（BD-002 已踩过）**

1. **`toUser` 不可为 null**：平台 `SysBaseApiImpl` 会对它执行 `split(",")`，null 会抛 NPE，
   而被本类的 try/catch 吞掉 —— 表现为「公告插入成功但收件人零条」，管理员实际**收不到任何告警**。
   故默认取 `WmsConstants.ALERT_TO_USER`（`"admin"`），可用 `wms.device.alert.to-user` 覆盖。
2. **`updateById` 默认忽略 null 字段**，所以「把 `last_alert_time` 置回 NULL」**必须用 `UpdateWrapper`**，
   否则列永远不会被清空，节流窗口会永久卡住。
3. **聚合而非逐条**：一轮扫描可能几十台设备转离线，逐条投递会刷屏。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceAlertServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备告警服务单测（不启 Spring 上下文）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceAlertServiceImplTest {

    private static WmsDevice device(String id, String code, Date lastAlertTime) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE)
                .setLastHeartbeat(new Date())
                .setLastAlertTime(lastAlertTime);
        d.setId(id);
        return d;
    }

    private static WmsDeviceAlertServiceImpl newService(ISysBaseAPI sysBaseAPI, WmsDeviceMapper mapper) {
        WmsDeviceAlertServiceImpl service = new WmsDeviceAlertServiceImpl(sysBaseAPI, mapper);
        WmsTestSupport.inject(service, "alertToUser", WmsConstants.ALERT_TO_USER);
        return service;
    }

    private static Date minutesAgo(int minutes) {
        return new Date(System.currentTimeMillis() - minutes * 60_000L);
    }

    // ------------------------------------------------------------ 绑定路径：不节流，人的即时操作要即时反馈

    @Test
    void alertOffline_shouldSendImmediatelyWhenThrottleNotRespected() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        // 1 分钟前刚告警过，但绑定是人工操作 → 仍应投递
        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(1))), false);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any(MessageDTO.class));
    }

    // ------------------------------------------------------------ 上报/扫描路径：30 分钟节流

    @Test
    void alertOffline_shouldSkipWithinThrottleWindow() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(5))), true);

        assertTrue(alerted.isEmpty());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void alertOffline_shouldSendAfterThrottleWindow() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(40))), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void alertOffline_shouldSendWhenNeverAlertedBefore() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", null)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
    }

    /** 30 分钟整应放行（边界不能写成 {@code >}）。 */
    @Test
    void alertOffline_shouldSendAtExactlyThrottleBoundary() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        Date exactly30 = new Date(System.currentTimeMillis()
                - WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES * 60_000L - 50);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", exactly30)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
    }

    // ------------------------------------------------------------ 聚合：N 台设备 → 1 条消息

    @Test
    void alertOffline_shouldAggregateIntoOneMessage() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(Arrays.asList(
                device("D1", "DDJ01", null),
                device("D2", "SCR01", null),
                device("D3", "SCR02", null)), true);

        assertEquals(3, alerted.size());
        ArgumentCaptor<MessageDTO> captor = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(captor.capture());
        String content = captor.getValue().getContent();
        assertTrue(content.contains("DDJ01"), "内容应含全部设备编码");
        assertTrue(content.contains("SCR01"));
        assertTrue(content.contains("SCR02"));
        assertEquals(WmsConstants.DEVICE_ALERT_TITLE, captor.getValue().getTitle());
        assertEquals(WmsConstants.ALERT_FROM_USER, captor.getValue().getFromUser());
        assertEquals(WmsConstants.ALERT_TO_USER, captor.getValue().getToUser(),
                "toUser 不可为 null：平台会 split(\",\")，null 会让告警静默丢失");
    }

    /** 混合场景：只有过期的那些被投递，且都打上新的告警时间。 */
    @Test
    void alertOffline_shouldOnlyAlertExpiredOnes() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(Arrays.asList(
                device("D1", "FRESH", minutesAgo(5)),
                device("D2", "STALE", minutesAgo(60))), true);

        assertEquals(Collections.singletonList("STALE"), alerted);
        verify(mapper, times(1)).updateById(any());
    }

    // ------------------------------------------------------------ 投递失败不得反噬调用方

    /**
     * 告警设施自己的毛病不能掩盖「设备离线」这件事本身，
     * 更不能把异常抛回给绑定 / 上报 / 扫描的调用方。
     */
    @Test
    void alertOffline_shouldNotThrowWhenDeliveryFails() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        doThrow(new RuntimeException("公告服务不可用")).when(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", null)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted,
                "投递失败仍应返回「判定为需要告警」的清单，并继续打点");
    }

    @Test
    void alertOffline_shouldTolerateEmptyInput() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mock(WmsDeviceMapper.class));

        assertTrue(service.alertOffline(Collections.emptyList(), true).isEmpty());
        assertTrue(service.alertOffline(null, true).isEmpty());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    // ------------------------------------------------------------ 恢复在线：清空节流

    /**
     * {@code updateById} 默认忽略 null 字段，故清空必须走 {@code UpdateWrapper}。
     * 若这里退回 updateById，{@code last_alert_time} 永远清不掉，节流窗口会永久卡住。
     */
    @Test
    void clearThrottle_shouldWriteNullViaUpdateWrapper() {
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);
        WmsDeviceAlertServiceImpl service = newService(mock(ISysBaseAPI.class), mapper);

        service.clearThrottle("D1");

        verify(mapper, times(1)).update(any(), any(Wrapper.class));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void clearThrottle_shouldIgnoreBlankId() {
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(mock(ISysBaseAPI.class), mapper);

        service.clearThrottle(null);
        service.clearThrottle("");

        verify(mapper, never()).update(any(), any(Wrapper.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceAlertServiceImplTest' -DskipTests=false`
Expected: 编译失败 —— `IWmsDeviceAlertService` / `WmsDeviceAlertServiceImpl` 不存在。

- [ ] **Step 3: 写接口与实现**

`service/IWmsDeviceAlertService.java`：

```java
package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsDevice;

import java.util.List;

/**
 * @Description: 设备离线告警投递
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsDeviceAlertService {

    /**
     * 聚合告警：对本批设备中通过节流判定的那些，发一条站内消息，并为其打点 {@code last_alert_time}。
     *
     * @param devices         待告警设备（本批已判定为离线）
     * @param respectThrottle true = 按 {@code DEVICE_ALERT_THROTTLE_MINUTES} 窗口节流；
     *                        false = 不节流。绑定路径传 false —— 那是人的即时操作，
     *                        要的就是即时反馈，不该被上一次的自动告警压掉
     * @return 实际告警的设备编码；被节流跳过的不在其中
     */
    List<String> alertOffline(List<WmsDevice> devices, boolean respectThrottle);

    /** 清空某设备的告警节流（恢复在线时调用），使后续再次离线能立即告警。 */
    void clearThrottle(String deviceId);
}
```

`service/impl/WmsDeviceAlertServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 设备离线告警投递
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsDeviceAlertServiceImpl implements IWmsDeviceAlertService {

    private final ISysBaseAPI sysBaseAPI;

    /** 只为打点 / 清空 {@code last_alert_time}。直接注入 Mapper 而非设备服务，避免与之循环依赖。 */
    private final WmsDeviceMapper wmsDeviceMapper;

    /**
     * 告警收件人（登录账号）。
     *
     * <p><b>不可为 null</b>：平台 {@code SysBaseApiImpl.sendSysAnnouncement} 会对 toUser 执行
     * {@code split(",")}，null 会抛 NPE 并被 {@link #send} 的 try/catch 吞掉，
     * 结果只剩一行「投递失败」日志而管理员什么都收不到。
     */
    @Value("${" + WmsConstants.DEVICE_ALERT_TO_USER_KEY + ":" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsDeviceAlertServiceImpl(ISysBaseAPI sysBaseAPI, WmsDeviceMapper wmsDeviceMapper) {
        this.sysBaseAPI = sysBaseAPI;
        this.wmsDeviceMapper = wmsDeviceMapper;
    }

    @Override
    public List<String> alertOffline(List<WmsDevice> devices, boolean respectThrottle) {
        if (devices == null || devices.isEmpty()) {
            return Collections.emptyList();
        }
        Date now = new Date();
        List<WmsDevice> allowed = new ArrayList<>();
        for (WmsDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (respectThrottle && !throttleExpired(device, now)) {
                log.debug("设备[{}]在告警节流窗口内，跳过投递（上次告警：{}）",
                        device.getDeviceCode(), device.getLastAlertTime());
                continue;
            }
            allowed.add(device);
        }
        if (allowed.isEmpty()) {
            log.info("本批 {} 台离线设备全部处于告警节流窗口内，未投递", devices.size());
            return Collections.emptyList();
        }
        List<String> codes = allowed.stream().map(WmsDevice::getDeviceCode).collect(Collectors.toList());
        send(buildContent(allowed));
        for (WmsDevice device : allowed) {
            stampAlertTime(device.getId(), now);
        }
        log.warn("设备离线告警已投递，共 {} 台：{}", allowed.size(), codes);
        return codes;
    }

    @Override
    public void clearThrottle(String deviceId) {
        if (oConvertUtils.isEmpty(deviceId)) {
            return;
        }
        // 必须用 UpdateWrapper：updateById 默认忽略 null 字段，置 NULL 会被静默跳过，
        // 于是 last_alert_time 永远清不掉，节流窗口永久卡住。
        UpdateWrapper<WmsDevice> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", deviceId)
                .set("last_alert_time", null)
                .set("update_time", new Date())
                .set("update_by", WmsUtils.currentUsername());
        try {
            wmsDeviceMapper.update(null, wrapper);
        } catch (Exception e) {
            log.error("清空设备[{}]告警节流失败：{}", deviceId, e.getMessage(), e);
        }
    }

    /** 节流窗口是否已过。窗口为 {@code DEVICE_ALERT_THROTTLE_MINUTES} 分钟；从未告警过视为已过。 */
    private boolean throttleExpired(WmsDevice device, Date now) {
        if (device.getLastAlertTime() == null) {
            return true;
        }
        long silentMs = now.getTime() - device.getLastAlertTime().getTime();
        return silentMs >= WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES * 60_000L;
    }

    /**
     * 投递站内消息。
     *
     * <p>用 try/catch 包住 —— 告警设施自己出问题，不能掩盖「设备离线」这件事，
     * 更不能把异常抛回给绑定 / 上报 / 扫描的调用方。
     */
    private void send(String content) {
        try {
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.DEVICE_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("设备离线告警投递失败：{}", e.getMessage(), e);
        }
    }

    /** 聚合内容：一轮扫描可能几十台设备转离线，逐条投递会刷屏。 */
    private String buildContent(List<WmsDevice> devices) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder content = new StringBuilder("以下设备已离线，请及时处理：");
        for (WmsDevice device : devices) {
            content.append("\n设备编码：").append(device.getDeviceCode())
                    .append("；名称：").append(device.getDeviceName())
                    .append("；类型：").append(device.getDeviceType())
                    .append("；最近心跳：")
                    .append(device.getLastHeartbeat() == null ? "从未上报" : format.format(device.getLastHeartbeat()));
        }
        return content.toString();
    }

    /** 打点告警时间。失败只记日志 —— 节流打点丢掉不该影响告警本身的判定结果。 */
    private void stampAlertTime(String deviceId, Date now) {
        if (oConvertUtils.isEmpty(deviceId)) {
            return;
        }
        try {
            WmsDevice update = new WmsDevice();
            update.setId(deviceId);
            update.setLastAlertTime(now);
            WmsUtils.fillUpdate(update);
            wmsDeviceMapper.updateById(update);
        } catch (Exception e) {
            log.error("设备[{}]告警时间打点失败：{}", deviceId, e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceAlertServiceImplTest' -DskipTests=false`
Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

> 若 `clearThrottle_shouldWriteNullViaUpdateWrapper` 报 `wanted but not invoked: update(...)`，
> 检查是否误用了 `updateById`。

- [ ] **Step 5: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsDeviceAlertService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsDeviceAlertServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceAlertServiceImplTest.java
git commit -m "feat(wms): BD-003 设备离线告警服务（聚合投递 + 30分钟节流 + 恢复清零）"
```

---

## Task 9: 设备服务（CRUD / 绑定 / 解绑 / 状态上报 / 离线扫描）

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsDeviceBindDTO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsDeviceStatusDTO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/WmsDeviceBindResultVO.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsDeviceService.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsDeviceServiceImpl.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 `WmsDevice` / `WmsDeviceMapper`；Task 4 的 `IWmsWarehouseService.requireEnabledWarehouse`；Task 8 的 `IWmsDeviceAlertService.alertOffline/clearThrottle`；Task 1 的 `DEVICE_STATUS_*` / `DEVICE_OFFLINE_THRESHOLD_SECONDS` / `DEVICE_SCAN_BATCH`
- Produces（Task 10 / Task 11 依赖）：
  ```java
  public interface IWmsDeviceService extends IService<WmsDevice> {
      boolean checkCodeExists(String code, String id);
      void addDevice(WmsDevice device);
      void editDevice(WmsDevice device);
      void removeDevice(String id);
      WmsDeviceBindResultVO bind(WmsDeviceBindDTO dto);
      void unbind(List<String> ids);
      void reportStatus(WmsDeviceStatusDTO dto);
      /** 扫描心跳静默超时的设备并置离线，返回本次发生跃迁的设备（已在库中置为离线）。 */
      List<WmsDevice> scanOfflineDevices();
  }
  ```
  `WmsDeviceBindDTO`：`String warehouseId`、`List<String> deviceIds`
  `WmsDeviceStatusDTO`：`String deviceCode`、`String status`
  `WmsDeviceBindResultVO`：`int bound`、`List<String> movedDevices`、`List<String> offlineDevices`、`String warning`（`@AllArgsConstructor`）

**三个必须处理的坑**

1. **`updateById` 忽略 null 字段 → 解绑写不进 NULL。** 「解绑 = 把 `warehouse_id` 置 NULL」，
   必须走 `UpdateWrapper.set("warehouse_id", null)`，否则解绑接口返回成功但字段纹丝不动。
   （与 Task 8 的 `clearThrottle` 是同一类坑。）
2. **Review Focus #4**：`/reportStatus` 传不存在的设备编码必须返回明确错误，
   而不是后续 `device.getId()` 的 NPE。
3. **Review Focus #3**：设备的 `warehouse_id` 可能指向已逻辑删除的库区 —— 绑定守卫用
   `requireEnabledWarehouse` 会正确拒绝，但**读取路径不得假设库区存在**。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceServiceImplTest {

    private static WmsDevice device(String id, String code, String status, String warehouseId) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(status).setWarehouseId(warehouseId);
        d.setId(id);
        return d;
    }

    private static WmsWarehouse warehouse() {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode("LB").setWarehouseName("半成品立库")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO).setStatus(WmsConstants.STATUS_ENABLE);
        w.setId("W1");
        return w;
    }

    private static WmsDeviceServiceImpl newService(IWmsDeviceAlertService alertService) {
        WmsDeviceServiceImpl service = spy(new WmsDeviceServiceImpl());
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse());
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);
        WmsTestSupport.inject(service, "wmsDeviceAlertService", alertService);
        return service;
    }

    // ------------------------------------------------------------ CRUD 与心跳打点

    @Test
    void addDevice_shouldRejectDuplicateCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addDevice(device(null, "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, null)));
        assertEquals("设备编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    @Test
    void addDevice_shouldDefaultStatusToOffline() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addDevice(device(null, "DDJ01", null, null));

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).save(captor.capture());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getLastHeartbeat(), "离线设备不应有心跳时间");
    }

    /**
     * 显式置在线**必须同时打心跳**：否则扫描任务下一轮会把这个刚建的在线设备判成离线
     * （因为它 last_heartbeat 为 null 时会被跳过，但一旦有别处写入过就会误判）。
     */
    @Test
    void addDevice_shouldStampHeartbeatWhenOnline() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addDevice(device(null, "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, null));

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).save(captor.capture());
        assertNotNull(captor.getValue().getLastHeartbeat());
    }

    // ------------------------------------------------------------ 绑定（蓝图步骤 5 + 异常「设备绑定失败：保存状态并告警」）

    @Test
    void bind_shouldSetWarehouseOnEachDevice() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, null)))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(1, result.getBound());
        assertTrue(result.getMovedDevices().isEmpty());
        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertEquals("W1", captor.getValue().getWarehouseId());
    }

    /**
     * **蓝图的异常分支**：设备绑定失败 → 保存设备绑定状态，给出告警。
     * 语义要点是「绑定本身成功」，告警只是并行的副作用，不得阻断、不得回滚。
     */
    @Test
    void bind_shouldSucceedButWarnWhenDeviceOffline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean()))
                .thenReturn(Collections.singletonList("DDJ01"));
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, null)))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(1, result.getBound(), "绑定本身必须成功");
        assertEquals(Collections.singletonList("DDJ01"), result.getOfflineDevices());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("DDJ01"));
        // 绑定是人的即时操作 → 不节流（否则会被上一次的自动告警压掉）
        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void bind_shouldReportMovedDevices() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenReturn(Collections.emptyList());
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W-OLD")))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(Collections.singletonList("DDJ01"), result.getMovedDevices(), "搬迁必须被显式报出，不能静默");
    }

    @Test
    void bind_shouldRejectEmptyDeviceSelection() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.emptyList());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.bind(dto));
        assertEquals("请至少选择一台设备", ex.getMessage());
    }

    // ------------------------------------------------------------ 解绑：必须能真的写进 NULL

    /**
     * `updateById` 默认忽略 null 字段。若用 updateById 解绑，接口会返回成功而
     * {@code warehouse_id} 纹丝不动 —— 典型的「看起来已解绑」的静默故障。
     */
    @Test
    void unbind_shouldWriteNullThroughUpdateWrapper() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(true).when(service).update(any(), any(Wrapper.class));

        service.unbind(Collections.singletonList("D1"));

        verify(service, times(1)).update(any(), any(Wrapper.class));
        verify(service, never()).updateById(any());
    }

    @Test
    void unbind_shouldRejectEmptySelection() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        assertThrows(RuntimeException.class, () -> service.unbind(Collections.emptyList()));
    }

    // ------------------------------------------------------------ 状态上报

    /** Review Focus #4：设备不存在必须给出明确的业务错误，而不是后续的 NPE。 */
    @Test
    void reportStatus_shouldRejectUnknownDeviceCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(null).when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("NOPE").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.reportStatus(dto));
        assertEquals("设备不存在：NOPE", ex.getMessage());
    }

    @Test
    void reportStatus_shouldRejectBlankCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        assertThrows(RuntimeException.class, () -> service.reportStatus(dto));
    }

    @Test
    void reportStatus_shouldRefreshHeartbeatOnOnline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, "W1"))
                .when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));
        doReturn(true).when(service).updateById(any());

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        service.reportStatus(dto);

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastHeartbeat(), "在线上报必须打心跳");
        verify(alertService).clearThrottle("D1");
        verify(alertService, never()).alertOffline(anyList(), anyBoolean());
    }

    @Test
    void reportStatus_shouldAlertWithThrottleWhenOffline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenReturn(Collections.singletonList("DDJ01"));
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W1"))
                .when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));
        doReturn(true).when(service).updateById(any());

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        service.reportStatus(dto);

        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(true));
        verify(alertService, never()).clearThrottle(any());
    }

    // ------------------------------------------------------------ 离线扫描

    @Test
    void scanOfflineDevices_shouldFlipAndReturnTransitions() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDevice stale = device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        stale.setLastHeartbeat(new Date(System.currentTimeMillis() - 20 * 60_000L));
        doReturn(Collections.singletonList(stale)).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        List<WmsDevice> turned = service.scanOfflineDevices();

        assertEquals(1, turned.size());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, turned.get(0).getStatus());
        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, captor.getValue().getStatus());
    }

    /**
     * **`last_heartbeat IS NULL` 的设备必须被 SQL 排除**。
     *
     * <p>这类设备从未上线过，判离线是误报；更要命的是新建设备会在 10 分钟后集体变离线，
     * 验收演示时会看到一屏假告警，把真实告警淹没。这条 WHERE 条件不是可选项。
     *
     * <p>这里直接断言 SQL 片段 —— 该行为全部由查询条件承担，用 mock 的返回列表是测不出来的。
     */
    @Test
    void scanOfflineDevices_queryMustExcludeNeverOnlineDevices() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        service.scanOfflineDevices();

        ArgumentCaptor<Wrapper<WmsDevice>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        String sql = ((QueryWrapper<WmsDevice>) captor.getValue()).getSqlSegment();
        assertTrue(sql.contains("last_heartbeat IS NOT NULL"),
                "必须排除从未上报心跳的设备，实际 SQL：" + sql);
        assertTrue(sql.contains("last_heartbeat <"), "必须按心跳阈值筛，实际 SQL：" + sql);
        assertTrue(sql.contains("status <>") || sql.contains("status !="),
                "只捕捉跃迁（不含已是离线的），实际 SQL：" + sql);
    }

    @Test
    void scanOfflineDevices_shouldContinueAfterSingleFailure() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDevice a = device("D1", "A", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        a.setLastHeartbeat(new Date());
        WmsDevice b = device("D2", "B", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        b.setLastHeartbeat(new Date());
        WmsDevice c = device("D3", "C", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        c.setLastHeartbeat(new Date());
        doReturn(Arrays.asList(a, b, c)).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).updateById(a);
        doReturn(true).when(service).updateById(c);
        org.mockito.Mockito.doThrow(new RuntimeException("锁等待超时")).when(service).updateById(b);

        List<WmsDevice> turned = service.scanOfflineDevices();

        assertEquals(Arrays.asList("A", "C"),
                Arrays.asList(turned.get(0).getDeviceCode(), turned.get(1).getDeviceCode()),
                "第 2 条失败不得中断整轮");
        assertEquals(2, turned.size());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceServiceImplTest' -DskipTests=false`
Expected: 编译失败 —— `WmsDeviceBindDTO` / `WmsDeviceStatusDTO` / `WmsDeviceBindResultVO` / `IWmsDeviceService` / `WmsDeviceServiceImpl` 不存在。

- [ ] **Step 3: 写入参与出参**

`dto/WmsDeviceBindDTO.java`：

```java
package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * @Description: 设备绑定库区入参
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备绑定库区入参")
public class WmsDeviceBindDTO {

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "设备ID列表")
    private List<String> deviceIds;
}
```

`dto/WmsDeviceStatusDTO.java`：

```java
package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 设备状态上报入参（供 WCS/RMS 回传）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备状态上报入参")
public class WmsDeviceStatusDTO {

    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "状态：在线/离线/故障")
    private String status;
}
```

`vo/WmsDeviceBindResultVO.java`：

```java
package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: 设备绑定结果
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "设备绑定结果")
public class WmsDeviceBindResultVO {

    /** 成功绑定的设备数。**绑定成功与告警是两件事**：离线只产生告警，不影响本字段。 */
    @Schema(description = "成功绑定的设备数")
    private int bound;

    /** 本次从其它库区搬迁过来的设备编码（显式报出，不静默）。 */
    @Schema(description = "本次搬迁的设备编码")
    private List<String> movedDevices;

    /** 本次绑定涉及设备中当前离线的编码。 */
    @Schema(description = "当前离线的设备编码")
    private List<String> offlineDevices;

    /** 前端提示语；无离线设备时为 null。 */
    @Schema(description = "前端提示语")
    private String warning;
}
```

- [ ] **Step 4: 写服务接口与实现**

`service/IWmsDeviceService.java`：

```java
package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;

import java.util.List;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsDeviceService extends IService<WmsDevice> {

    /** 设备编码唯一性校验。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    void addDevice(WmsDevice device);

    void editDevice(WmsDevice device);

    void removeDevice(String id);

    /**
     * 绑定设备到库区。
     *
     * <p><b>绑定本身成功与告警是两件事</b>（蓝图异常分支「设备绑定失败 → 保存绑定状态并告警」）：
     * 离线设备照样绑定成功，只额外产生告警，不回滚、不阻断。
     */
    WmsDeviceBindResultVO bind(WmsDeviceBindDTO dto);

    /** 解绑：把 {@code warehouse_id} 置 NULL。 */
    void unbind(List<String> ids);

    /** WCS/RMS 状态上报。在线会刷新心跳并清空告警节流；离线/故障按节流告警。 */
    void reportStatus(WmsDeviceStatusDTO dto);

    /**
     * 扫描心跳静默超时的设备并置为离线，返回本次发生跃迁的设备（已在库中置为离线）。
     *
     * <p><b>从未上报过心跳（{@code last_heartbeat IS NULL}）的设备不参与</b> ——
     * 它们从未上线，判离线是误报，且会让新建设备集体变离线、淹没真实告警。
     *
     * <p>本方法**只负责状态跃迁与日志**，告警由调用方（定时任务）聚合后投递，
     * 以便两者可分别测试。
     */
    List<WmsDevice> scanOfflineDevices();
}
```

`service/impl/WmsDeviceServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsDeviceServiceImpl extends ServiceImpl<WmsDeviceMapper, WmsDevice>
        implements IWmsDeviceService {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Autowired
    private IWmsDeviceAlertService wmsDeviceAlertService;

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsDevice> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("device_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addDevice(WmsDevice device) {
        if (checkCodeExists(device.getDeviceCode(), null)) {
            throw new RuntimeException("设备编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(device.getStatus())) {
            // 没收到任何心跳前不应被当成在线设备投用
            device.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        }
        stampHeartbeatIfOnline(device);
        WmsUtils.fillCreate(device);
        this.save(device);
    }

    @Override
    public void editDevice(WmsDevice device) {
        if (checkCodeExists(device.getDeviceCode(), device.getId())) {
            throw new RuntimeException("设备编码已存在，请重新输入");
        }
        stampHeartbeatIfOnline(device);
        WmsUtils.fillUpdate(device);
        this.updateById(device);
    }

    @Override
    public void removeDevice(String id) {
        this.removeById(id);
    }

    @Override
    public WmsDeviceBindResultVO bind(WmsDeviceBindDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWarehouseId())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        if (dto.getDeviceIds() == null || dto.getDeviceIds().isEmpty()) {
            throw new RuntimeException("请至少选择一台设备");
        }
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(dto.getWarehouseId());
        List<WmsDevice> devices = this.listByIds(dto.getDeviceIds());
        if (devices.isEmpty()) {
            throw new RuntimeException("所选设备不存在");
        }
        List<String> moved = new ArrayList<>();
        List<WmsDevice> offline = new ArrayList<>();
        for (WmsDevice device : devices) {
            if (oConvertUtils.isNotEmpty(device.getWarehouseId())
                    && !device.getWarehouseId().equals(dto.getWarehouseId())) {
                moved.add(device.getDeviceCode());
                log.warn("设备[{}]由库区[{}]搬迁到库区[{}]",
                        device.getDeviceCode(), device.getWarehouseId(), dto.getWarehouseId());
            }
            WmsDevice update = new WmsDevice();
            update.setId(device.getId());
            update.setWarehouseId(dto.getWarehouseId());
            WmsUtils.fillUpdate(update);
            this.updateById(update);
            if (WmsConstants.DEVICE_STATUS_OFFLINE.equals(device.getStatus())) {
                offline.add(device);
            }
        }
        List<String> offlineCodes = offline.stream()
                .map(WmsDevice::getDeviceCode).collect(Collectors.toList());
        String warning = null;
        if (!offlineCodes.isEmpty()) {
            // 绑定是人的即时操作 → 不节流，要即时反馈
            List<String> alerted = wmsDeviceAlertService.alertOffline(offline, false);
            if (!alerted.isEmpty()) {
                warning = "已绑定到库区[" + warehouse.getWarehouseName() + "]，但以下 "
                        + alerted.size() + " 台设备当前离线，已发出告警：" + String.join("、", alerted);
            }
        }
        log.info("库区[{}]绑定设备 {} 台，其中离线 {} 台，搬迁 {} 台",
                warehouse.getWarehouseCode(), devices.size(), offlineCodes.size(), moved.size());
        return new WmsDeviceBindResultVO(devices.size(), moved, offlineCodes, warning);
    }

    @Override
    public void unbind(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("请至少选择一台设备");
        }
        // 必须用 UpdateWrapper：updateById 默认忽略 null 字段，
        // 用它「解绑」会返回成功而 warehouse_id 纹丝不动 —— 典型的「看起来已解绑」的静默故障。
        UpdateWrapper<WmsDevice> wrapper = new UpdateWrapper<>();
        wrapper.in("id", ids)
                .set("warehouse_id", null)
                .set("update_time", new Date())
                .set("update_by", WmsUtils.currentUsername());
        this.update(null, wrapper);
    }

    @Override
    public void reportStatus(WmsDeviceStatusDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getDeviceCode())) {
            throw new RuntimeException("设备编码不能为空");
        }
        if (oConvertUtils.isEmpty(dto.getStatus())) {
            throw new RuntimeException("设备状态不能为空");
        }
        WmsDevice device = this.getOne(new QueryWrapper<WmsDevice>()
                .eq("device_code", dto.getDeviceCode()).last("limit 1"), false);
        if (device == null) {
            throw new RuntimeException("设备不存在：" + dto.getDeviceCode());
        }
        Date now = new Date();
        WmsDevice update = new WmsDevice();
        update.setId(device.getId());
        update.setStatus(dto.getStatus());
        update.setLastHeartbeat(now);
        WmsUtils.fillUpdate(update);
        this.updateById(update);

        boolean online = WmsConstants.DEVICE_STATUS_ONLINE.equals(dto.getStatus());
        if (online) {
            // 恢复在线 → 清空节流，使后续再次离线能立即告警
            wmsDeviceAlertService.clearThrottle(device.getId());
        } else {
            device.setStatus(dto.getStatus());
            device.setLastHeartbeat(now);
            wmsDeviceAlertService.alertOffline(Collections.singletonList(device), true);
        }
    }

    @Override
    public List<WmsDevice> scanOfflineDevices() {
        Date threshold = new Date(System.currentTimeMillis()
                - WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS * 1000L);
        List<WmsDevice> candidates = this.list(new QueryWrapper<WmsDevice>()
                // 只捕捉跃迁：已经是离线的设备不再重复处理
                .ne("status", WmsConstants.DEVICE_STATUS_OFFLINE)
                // 关键：从未上报过心跳的设备不参与。它们从未上线，判离线是误报，
                // 且会让新建设备集体变离线、淹没真实告警。
                .isNotNull("last_heartbeat")
                .lt("last_heartbeat", threshold)
                .orderByAsc("last_heartbeat")
                .last("limit " + WmsConstants.DEVICE_SCAN_BATCH));
        List<WmsDevice> turned = new ArrayList<>();
        for (WmsDevice device : candidates) {
            try {
                WmsDevice update = new WmsDevice();
                update.setId(device.getId());
                update.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
                WmsUtils.fillUpdate(update);
                this.updateById(update);
                device.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
                log.warn("设备[{}]心跳静默超过 {} 秒，已置为离线（最近心跳：{}）",
                        device.getDeviceCode(), WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS,
                        device.getLastHeartbeat());
                turned.add(device);
            } catch (Exception e) {
                // 单条失败不中断整轮
                log.error("设备[{}]转离线失败：{}", device.getDeviceCode(), e.getMessage(), e);
            }
        }
        return turned;
    }

    /** 显式把状态置为「在线」时必须同时打心跳，否则扫描任务下一轮会把它判成离线。 */
    private void stampHeartbeatIfOnline(WmsDevice device) {
        if (WmsConstants.DEVICE_STATUS_ONLINE.equals(device.getStatus())) {
            device.setLastHeartbeat(new Date());
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceServiceImplTest' -DskipTests=false`
Expected: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

> `scanOfflineDevices_queryMustExcludeNeverOnlineDevices` 若断言失败，打印的 `sql` 会告诉你缺了哪个条件。

- [ ] **Step 6: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsDeviceServiceImplTest.java
git commit -m "feat(wms): BD-003 设备服务（绑定不因告警而失败/解绑写NULL/上报心跳/离线扫描）"
```

---

## Task 10: 设备控制器

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsDeviceController.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/controller/WmsDeviceControllerTest.java`

**Interfaces:**
- Consumes: Task 9 的 `IWmsDeviceService` 全部方法；Task 4 的 `IWmsWarehouseService.listByIds`
- Produces: REST 端点（前端 Task 13 依赖）
  `GET /wms/device/{list,listUnbound,queryById,checkCode}`、`POST /wms/device/{add,bind,unbind,reportStatus}`、
  `PUT|POST /wms/device/edit`、`DELETE /wms/device/{delete,deleteBatch}`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/controller/WmsDeviceControllerTest.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备控制器薄层测试
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceControllerTest {

    private static WmsDevice device(String id, String code, String warehouseId) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setWarehouseId(warehouseId)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        d.setId(id);
        return d;
    }

    private static WmsDeviceController newController(IWmsDeviceService deviceService,
                                                     IWmsWarehouseService warehouseService) {
        WmsDeviceController controller = new WmsDeviceController();
        WmsTestSupport.inject(controller, "service", deviceService);
        WmsTestSupport.inject(controller, "wmsWarehouseService", warehouseService);
        return controller;
    }

    @Test
    void list_shouldFillWarehouseName() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", "W1")));
        when(deviceService.page(any(), any())).thenReturn(page);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseName("半成品立库");
        warehouse.setId("W1");
        when(warehouseService.listByIds(any())).thenReturn(Arrays.asList(warehouse));
        WmsDeviceController controller = newController(deviceService, warehouseService);

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), 1, 10, new MockHttpServletRequest());

        assertEquals("半成品立库", result.getResult().getRecords().get(0).getWarehouseName());
    }

    /** Review Focus #3：设备指向已逻辑删除的库区时，回填留 null，不得 NPE。 */
    @Test
    void list_shouldTolerateDeletedWarehouse() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", "W-DELETED")));
        when(deviceService.page(any(), any())).thenReturn(page);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.listByIds(any())).thenReturn(Collections.emptyList());
        WmsDeviceController controller = newController(deviceService, warehouseService);

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), 1, 10, new MockHttpServletRequest());

        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    @Test
    void list_shouldTolerateUnboundDevices() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", null)));
        when(deviceService.page(any(), any())).thenReturn(page);
        IWmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    // ------------------------------------------------------------ 绑定（验收标准②：绑定成功 + 离线告警）

    @Test
    void bind_shouldReturnWarningPayload() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.bind(any())).thenReturn(new WmsDeviceBindResultVO(
                2, Collections.emptyList(), Collections.singletonList("DDJ01"),
                "已绑定到库区[半成品立库]，但以下 1 台设备当前离线，已发出告警：DDJ01"));
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Arrays.asList("D1", "D2"));
        Result<WmsDeviceBindResultVO> result = controller.bind(dto);

        assertTrue(result.isSuccess(), "绑定本身必须报成功");
        assertEquals(2, result.getResult().getBound());
        assertNotNull(result.getResult().getWarning(), "离线告警走 warning，不走 error");
    }

    @Test
    void bind_shouldSurfaceGuard() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        doThrow(new RuntimeException("库区不存在或已停用")).when(deviceService).bind(any());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertFalse(controller.bind(new WmsDeviceBindDTO()).isSuccess());
    }

    @Test
    void unbind_shouldDelegate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertTrue(controller.unbind(Arrays.asList("D1")).isSuccess());
        verify(deviceService).unbind(Arrays.asList("D1"));
    }

    // ------------------------------------------------------------ 状态上报

    @Test
    void reportStatus_shouldSurfaceUnknownDevice() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        doThrow(new RuntimeException("设备不存在：NOPE")).when(deviceService).reportStatus(any());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("NOPE").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        Result<?> result = controller.reportStatus(dto);

        assertFalse(result.isSuccess());
        assertEquals("设备不存在：NOPE", result.getMessage());
    }

    @Test
    void reportStatus_shouldDelegate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);

        assertTrue(controller.reportStatus(dto).isSuccess());
        verify(deviceService).reportStatus(dto);
    }

    // ------------------------------------------------------------ 未绑定列表（绑定弹窗数据源）

    @Test
    void listUnbound_shouldQueryNullWarehouse() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertTrue(controller.listUnbound().isSuccess());
        verify(deviceService).list(any(Wrapper.class));
    }

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.checkCodeExists("DDJ01", null)).thenReturn(true);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertFalse(controller.checkCode("DDJ01", null).isSuccess());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceControllerTest' -DskipTests=false`
Expected: 编译失败 —— `WmsDeviceController` 不存在。

- [ ] **Step 3: 写控制器**

`controller/WmsDeviceController.java`：

```java
package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-设备")
@RestController
@RequestMapping("/wms/device")
@Slf4j
public class WmsDeviceController extends JeecgController<WmsDevice, IWmsDeviceService> {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Operation(summary = "设备-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsDevice>> queryPageList(WmsDevice wmsDevice,
                                                  @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                  HttpServletRequest req) {
        // 非持久化字段必须先置空，否则 QueryGenerator 会拼出不存在的 warehouse_name 列条件
        wmsDevice.setWarehouseName(null);
        QueryWrapper<WmsDevice> queryWrapper = QueryGenerator.initQueryWrapper(wmsDevice, req.getParameterMap());
        queryWrapper.orderByAsc("device_type").orderByAsc("device_code");
        Page<WmsDevice> page = new Page<>(pageNo, pageSize);
        IPage<WmsDevice> pageList = service.page(page, queryWrapper);
        fillWarehouseNames(pageList.getRecords());
        return Result.OK(pageList);
    }

    @Operation(summary = "设备-未绑定库区的设备列表（绑定弹窗数据源）")
    @GetMapping(value = "/listUnbound")
    public Result<List<WmsDevice>> listUnbound() {
        QueryWrapper<WmsDevice> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNull("warehouse_id").orderByAsc("device_code");
        return Result.OK(service.list(queryWrapper));
    }

    @Operation(summary = "设备-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsDevice> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsDevice entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        fillWarehouseNames(java.util.Collections.singletonList(entity));
        return Result.OK(entity);
    }

    @Operation(summary = "设备-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("设备编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("设备编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "设备-添加", operateType = 2)
    @Operation(summary = "设备-添加")
    @PostMapping(value = "/add")
    public Result<WmsDevice> add(@RequestBody WmsDevice wmsDevice) {
        try {
            service.addDevice(wmsDevice);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-编辑", operateType = 3)
    @Operation(summary = "设备-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsDevice> edit(@RequestBody WmsDevice wmsDevice) {
        try {
            service.editDevice(wmsDevice);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 绑定设备到库区。
     *
     * <p><b>注意返回语义</b>：绑定成功即 {@code Result.OK}，即使有离线设备。
     * 离线告警放在 {@code warning} 字段里，前端用 {@code useMessage().warning} 展示 ——
     * 若走 {@code Result.error}，使用者会误判成「绑定失败」。
     */
    @AutoLog(value = "设备-绑定库区", operateType = 3)
    @Operation(summary = "设备-绑定归属库区")
    @PostMapping(value = "/bind")
    public Result<WmsDeviceBindResultVO> bind(@RequestBody WmsDeviceBindDTO dto) {
        try {
            return Result.OK(service.bind(dto));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-解绑库区", operateType = 3)
    @Operation(summary = "设备-解绑库区")
    @PostMapping(value = "/unbind")
    public Result<?> unbind(@RequestBody List<String> ids) {
        try {
            service.unbind(ids);
            return Result.OK("解绑成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-状态上报", operateType = 3)
    @Operation(summary = "设备-状态上报（WCS/RMS 回传）")
    @PostMapping(value = "/reportStatus")
    public Result<?> reportStatus(@RequestBody WmsDeviceStatusDTO dto) {
        try {
            service.reportStatus(dto);
            return Result.OK("上报成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-删除", operateType = 4)
    @Operation(summary = "设备-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        service.removeDevice(id);
        return Result.OK("删除成功！");
    }

    @AutoLog(value = "设备-批量删除", operateType = 4)
    @Operation(summary = "设备-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        for (String id : ids.split(",")) {
            service.removeDevice(id);
        }
        return Result.OK("批量删除成功！");
    }

    /**
     * 回填库区名。
     *
     * <p>设备可能未绑定（{@code warehouse_id} 为 null），也可能指向已逻辑删除的库区 ——
     * 两种情况下 {@code listByIds} 都查不到，此处留 {@code null} 而不是抛异常。
     */
    private void fillWarehouseNames(List<WmsDevice> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<String> warehouseIds = records.stream()
                .map(WmsDevice::getWarehouseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (warehouseIds.isEmpty()) {
            return;
        }
        Map<String, String> nameById = wmsWarehouseService.listByIds(warehouseIds).stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName, (a, b) -> a));
        for (WmsDevice record : records) {
            record.setWarehouseName(nameById.get(record.getWarehouseId()));
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceControllerTest' -DskipTests=false`
Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsDeviceController.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/controller/WmsDeviceControllerTest.java
git commit -m "feat(wms): BD-003 设备控制器（绑定成功与告警分离，warning 不走 error）"
```

---

## Task 11: 离线扫描定时任务与启动自注册

**Files:**
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/WmsDeviceOfflineScanJob.java`
- Create: `jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/WmsDeviceOfflineScanJobRegistrar.java`
- Test: `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/job/WmsDeviceOfflineScanJobTest.java`

**Interfaces:**
- Consumes: Task 9 的 `IWmsDeviceService.scanOfflineDevices`；Task 8 的 `IWmsDeviceAlertService.alertOffline`；Task 1 的 `DEVICE_SCAN_JOB_*` 常量；Task 1 的 Flyway 种子行（同 id）
- Produces: 可被 Quartz 调度的 `WmsDeviceOfflineScanJob`；启动自注册的 `WmsDeviceOfflineScanJobRegistrar`

**为什么要注册器（继承 BD-002 的发现）**

`sys_quartz_job` 表只是平台的**注册表元数据**，全库**没有任何启动加载器** ——
任务仅经「定时任务管理」UI 的 `saveAndScheduleJob` 写入调度器。
**故仅靠 Flyway 插一行，任务在全新环境不会执行**，离线告警会静默失效。
注册器是照 `WmsOrderSyncJobRegistrar` 的同构实现（**刻意不抽象**：抽象要改动一个已验收的类，
收益仅 30 行，风险大于收益。若出现第三个任务再抽基类）。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/jeecg/modules/wms/job/WmsDeviceOfflineScanJobTest.java`：

```java
package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备离线扫描任务单测
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceOfflineScanJobTest {

    private static WmsDevice device(String code) {
        WmsDevice d = new WmsDevice().setDeviceCode(code)
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        d.setId("D-" + code);
        return d;
    }

    private static WmsDeviceOfflineScanJob newJob(IWmsDeviceService deviceService,
                                                  IWmsDeviceAlertService alertService) {
        WmsDeviceOfflineScanJob job = new WmsDeviceOfflineScanJob();
        WmsTestSupport.inject(job, "wmsDeviceService", deviceService);
        WmsTestSupport.inject(job, "wmsDeviceAlertService", alertService);
        return job;
    }

    @Test
    void execute_shouldAlertAggregatedForTransitions() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices())
                .thenReturn(Arrays.asList(device("DDJ01"), device("SCR01")));
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean()))
                .thenReturn(Arrays.asList("DDJ01", "SCR01"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        job.execute(mock(JobExecutionContext.class));

        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void execute_shouldNotAlertWhenNoTransitions() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenReturn(Collections.emptyList());
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        job.execute(mock(JobExecutionContext.class));

        verify(alertService, never()).alertOffline(anyList(), anyBoolean());
    }

    /**
     * 扫描异常不得抛回调度器 —— 否则 Quartz 会把 job 置为 ERROR 状态，
     * 后续轮次可能不再触发，「离线告警」整条链路静默死掉。
     */
    @Test
    void execute_shouldSwallowScanFailure() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenThrow(new RuntimeException("数据库连接超时"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, mock(IWmsDeviceAlertService.class));

        assertDoesNotThrow(() -> job.execute(mock(JobExecutionContext.class)));
    }

    @Test
    void execute_shouldSwallowAlertFailure() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenReturn(Collections.singletonList(device("DDJ01")));
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenThrow(new RuntimeException("公告服务不可用"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        assertDoesNotThrow(() -> job.execute(mock(JobExecutionContext.class)));
    }

    /** 常量必须与 Flyway 种子行的 id 一致，否则 UI 的「立即执行」控制的是另一个 job。 */
    @Test
    void jobConstants_mustBeConsistent() {
        org.junit.jupiter.api.Assertions.assertEquals(
                "1920000000000000102", WmsConstants.DEVICE_SCAN_JOB_ID);
        org.junit.jupiter.api.Assertions.assertEquals(
                "org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob", WmsConstants.DEVICE_SCAN_JOB_CLASS);
        org.junit.jupiter.api.Assertions.assertTrue(
                WmsConstants.DEVICE_SCAN_JOB_CLASS.startsWith("org.jeecg."),
                "类名须以 org.jeecg. 开头：QuartzJobServiceImpl.getClass() 有包白名单防 RCE");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceOfflineScanJobTest' -DskipTests=false`
Expected: 编译失败 —— `WmsDeviceOfflineScanJob` 不存在。

- [ ] **Step 3: 写任务与注册器**

`job/WmsDeviceOfflineScanJob.java`：

```java
package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 设备离线扫描任务：把心跳静默超时的设备置为离线并聚合告警。
 *
 * <p>无状态、分隔清晰：**跃迁与日志**由 {@link IWmsDeviceService#scanOfflineDevices()} 负责，
 * **告警投递**由本类编排 —— 这样两者可分别测试。
 *
 * <p>{@code @DisallowConcurrentExecution}：单轮处理上限 {@code DEVICE_SCAN_BATCH} 条，
 * 正常情况下远快于 5 分钟周期；加此注解是防极端堆积时自我重叠。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsDeviceOfflineScanJob implements Job {

    @Autowired
    private IWmsDeviceService wmsDeviceService;

    @Autowired
    private IWmsDeviceAlertService wmsDeviceAlertService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("WMS设备离线扫描任务开始");
        try {
            List<WmsDevice> turned = wmsDeviceService.scanOfflineDevices();
            if (turned.isEmpty()) {
                log.info("WMS设备离线扫描任务结束：本轮无设备转为离线");
                return;
            }
            // 扫描只捕捉跃迁（WHERE status != 离线），天然一台设备一轮只告警一次；
            // 仍传 respectThrottle=true，以覆盖「恢复在线后 30 分钟内又失联」这种反复。
            List<String> alerted = wmsDeviceAlertService.alertOffline(turned, true);
            log.info("WMS设备离线扫描任务结束：转离线 {} 台，实际告警 {} 台",
                    turned.size(), alerted.size());
        } catch (Exception e) {
            // 不得抛回调度器：Quartz 会把 job 置为 ERROR，后续轮次可能不再触发，
            // 「离线告警」整条链路会静默死掉。
            log.error("WMS设备离线扫描任务异常：{}", e.getMessage(), e);
        }
    }
}
```

`job/WmsDeviceOfflineScanJobRegistrar.java`：

```java
package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 离线扫描任务的启动自注册器。
 *
 * <p>为什么要它：{@code sys_quartz_job} 表只是平台的注册表元数据，项目里**没有**任何启动加载器，
 * 任务只经「定时任务管理」UI 的 saveAndScheduleJob 写入调度器。若只靠 Flyway 插一行，
 * 全新环境上任务永远不会执行 —— 离线告警会静默失效。故在此幂等自注册。
 *
 * <p>启停以配置 {@code wms.device.job.enabled} 为准（默认 true）。刻意**不读**
 * sys_quartz_job.status，以免 wms 模块反向依赖 system-biz 的 QuartzJob 实体。
 *
 * <p>约定与 {@code QuartzJobServiceImpl.schedulerAdd} 对齐：JobDetail / CronTrigger 均用
 * withIdentity(id) 的**默认组**，这样 UI 的启停按钮控制的是同一个 job。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Component
public class WmsDeviceOfflineScanJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsDeviceOfflineScanJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 仅供单测设置开关。 */
    void setJobEnabledForTest(boolean jobEnabled) {
        this.jobEnabled = jobEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!jobEnabled) {
            unscheduleIfPresent();
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.DEVICE_SCAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                log.info("设备离线扫描任务[{}]已存在于调度器中，跳过注册", WmsConstants.DEVICE_SCAN_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsDeviceOfflineScanJob.class)
                    .withIdentity(WmsConstants.DEVICE_SCAN_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.DEVICE_SCAN_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.DEVICE_SCAN_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("设备离线扫描任务[{}]注册成功，cron={}",
                    WmsConstants.DEVICE_SCAN_JOB_ID, WmsConstants.DEVICE_SCAN_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("设备离线扫描任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 关闭开关时**主动摘除**已注册的任务。
     *
     * <p>本部署的 Quartz 是 JDBC 作业存储（{@code LocalDataSourceJobStore} + {@code isClustered:true}），
     * 任务一旦注册过，其 trigger 就持久化在 QRTZ_* 表中，重启后会被调度器自动加载。
     * 因此仅在关闭时"提前 return"是**停不掉**任务的：运维把配置改成 false 并重启后，
     * 日志会显示"已关闭"，任务却仍每 5 分钟照跑 —— 属"看起来已停"的静默故障。
     */
    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.DEVICE_SCAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("设备离线扫描任务[{}]已按配置 {} 关闭并摘除",
                        WmsConstants.DEVICE_SCAN_JOB_ID, WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY);
            } else {
                log.info("设备离线扫描任务已按配置 {} 关闭，调度器中无残留",
                        WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY);
            }
        } catch (Exception e) {
            log.warn("设备离线扫描任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsDeviceOfflineScanJobTest' -DskipTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`

- [ ] **Step 5: 全模块回归并提交**

Run: `bash /d/tools/m.sh -P dev clean package -pl jeecg-boot-module/jeecg-boot-module-wms -am -DskipTests`
Expected: `BUILD SUCCESS`

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/job/
git commit -m "feat(wms): BD-003 设备离线扫描定时任务与启动自注册器"
```

---

## Task 12: 前端「库区与库位」页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/warehouse/warehouse.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/warehouse.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/location.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/location.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/components/WarehouseTree.vue`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/components/WarehouseModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/components/GenerateModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/components/LocationAttrModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/components/LocationModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/warehouse/index.vue`

**Interfaces:**
- Consumes: Task 7 的 REST 端点（`/wms/warehouse/*`、`/wms/location/*`）
- Produces: 路由组件 `wms/warehouse/index`（Task 1 的菜单 `1910000000000000051` 已指向该 component 路径）

> **前端无自动化测试**：本仓库配置了 Jest 但**未接进 `package.json`**，没有可跑的 `pnpm test`。
> 故此任务的验证是 `pnpm build`（含 `vue-tsc` 类型检查）+ Task 14 的浏览器手工验收。
> 这是诚实的边界，不是省略。

- [ ] **Step 1: 写 API 封装**

`warehouse/warehouse.api.ts`：

```ts
import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/warehouse/list',
  treeList = '/wms/warehouse/treeList',
  add = '/wms/warehouse/add',
  edit = '/wms/warehouse/edit',
  disable = '/wms/warehouse/disable',
  deleteOne = '/wms/warehouse/delete',
  deleteBatch = '/wms/warehouse/deleteBatch',
  checkCode = '/wms/warehouse/checkCode',
  queryById = '/wms/warehouse/queryById',
  outOfEnvelopeCount = '/wms/warehouse/outOfEnvelopeCount',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

export const getTreeList = (params?) => defHttp.get({ url: Api.treeList, params });

export const queryById = (params) => defHttp.get({ url: Api.queryById, params });

/** 库区编码唯一性校验：返回原始报文以便读取 message */
export const checkWarehouseCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateWarehouse = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

export const disableWarehouse = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteWarehouse = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());

export const getOutOfEnvelopeCount = (params) => defHttp.get({ url: Api.outOfEnvelopeCount, params });
```

`warehouse/location.api.ts`：

```ts
import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/location/list',
  add = '/wms/location/add',
  edit = '/wms/location/edit',
  generate = '/wms/location/generate',
  updateAttr = '/wms/location/updateAttr',
  release = '/wms/location/release',
  deleteOne = '/wms/location/delete',
  deleteBatch = '/wms/location/deleteBatch',
  checkCode = '/wms/location/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

export const checkLocationCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

/** 批量生成。返回 { generated, skipped } —— 注意 skipped 是「已存在被跳过」，不是失败 */
export const generateLocations = (params) => defHttp.post({ url: Api.generate, params });

export const updateLocationAttr = (params) => defHttp.post({ url: Api.updateAttr, params });

export const releaseLocation = (params) => defHttp.post({ url: Api.release, params }, { joinParamsToUrl: true });

export const saveOrUpdateLocation = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

export const deleteLocation = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());
```

- [ ] **Step 2: 写列与表单定义**

`warehouse/warehouse.data.ts`：

```ts
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkWarehouseCode } from './warehouse.api';

/** 库区类型（与后端 WmsConstants 的中文字面量一致） */
export const WAREHOUSE_TYPE_OPTIONS = [
  { label: '立库', value: '立库' },
  { label: '敏捷仓', value: '敏捷仓' },
  { label: '线边柜', value: '线边柜' },
];

export const STATUS_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' },
];

export const columns: BasicColumn[] = [
  { title: '库区编码', align: 'center', dataIndex: 'warehouseCode', width: 120 },
  { title: '库区名称', align: 'center', dataIndex: 'warehouseName', width: 160 },
  { title: '库区类型', align: 'center', dataIndex: 'warehouseType', width: 100 },
  { title: '排', align: 'center', dataIndex: 'rowCount', width: 60 },
  { title: '列', align: 'center', dataIndex: 'colCount', width: 80 },
  { title: '层', align: 'center', dataIndex: 'layerCount', width: 60 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '库区编码',
    field: 'warehouseCode',
    component: 'Input',
    required: true,
    dynamicRules: ({ model }) => [
      { required: true, message: '请输入库区编码!' },
      {
        validator: async (_rule: any, value: string) => {
          if (!value) {
            return Promise.resolve();
          }
          const res: any = await checkWarehouseCode({ code: value, id: model.id });
          if (res && res.success === false) {
            return Promise.reject(res.message || '库区编码已存在，请重新输入');
          }
          return Promise.resolve();
        },
      },
    ],
  },
  { label: '库区名称', field: 'warehouseName', component: 'Input', required: true },
  {
    label: '库区类型',
    field: 'warehouseType',
    component: 'Select',
    required: true,
    componentProps: { options: WAREHOUSE_TYPE_OPTIONS, placeholder: '请选择库区类型' },
  },
  { label: '排数上限', field: 'rowCount', component: 'InputNumber', helpMessage: '仅限制批量生成的范围，可随时自由增减' },
  { label: '列数上限', field: 'colCount', component: 'InputNumber', helpMessage: '仅限制批量生成的范围，可随时自由增减' },
  { label: '层数上限', field: 'layerCount', component: 'InputNumber', helpMessage: '仅限制批量生成的范围，可随时自由增减' },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];
```

`warehouse/location.data.ts`：

```ts
import { BasicColumn, FormSchema } from '/@/components/Table';

export const LOCATION_STATUS_OPTIONS = [
  { label: '空闲', value: '空闲' },
  { label: '占用', value: '占用' },
  { label: '故障', value: '故障' },
];

/** 状态色：空闲(绿) / 占用(蓝) / 故障(红) */
export const LOCATION_STATUS_COLOR: Record<string, string> = {
  空闲: 'green',
  占用: 'blue',
  故障: 'red',
};

export const columns: BasicColumn[] = [
  { title: '库位编码', align: 'center', dataIndex: 'locationCode', width: 180 },
  { title: '库区', align: 'center', dataIndex: 'warehouseName', width: 140 },
  { title: '排', align: 'center', dataIndex: 'rowNo', width: 60 },
  { title: '列', align: 'center', dataIndex: 'colNo', width: 60 },
  { title: '层', align: 'center', dataIndex: 'layerNo', width: 60 },
  { title: '承重', align: 'center', dataIndex: 'maxWeight', width: 100 },
  { title: '状态', align: 'center', dataIndex: 'status', width: 90 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '库位编码', field: 'locationCode', component: 'JInput', colProps: { span: 6 } },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: LOCATION_STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '库区', field: 'warehouseId', component: 'Input', show: false },
  { label: '排', field: 'rowNo', component: 'InputNumber', required: true },
  { label: '列', field: 'colNo', component: 'InputNumber', required: true },
  { label: '层', field: 'layerNo', component: 'InputNumber', required: true },
  { label: '承重', field: 'maxWeight', component: 'InputNumber' },
];

export const attrFormSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '库位编码', field: 'locationCode', component: 'Input', dynamicDisabled: true },
  { label: '承重', field: 'maxWeight', component: 'InputNumber' },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    componentProps: { options: LOCATION_STATUS_OPTIONS },
    helpMessage: '库位为「占用」时不允许修改属性',
  },
];
```

- [ ] **Step 3: 写批量生成弹窗（**验收标准①的主界面**）**

`warehouse/components/GenerateModal.vue`：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" :confirmLoading="submitting" width="600px">
    <BasicForm @register="registerForm" />
    <a-alert v-if="overLimit" type="error" show-icon class="mb-2" :message="overLimitMessage" />
    <a-alert v-else-if="total > 0" type="info" show-icon class="mb-2" :message="`本次预计生成 ${total} 个库位`" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-generate" setup>
  import { computed, ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { generateLocations } from '../location.api';

  /** 与后端 WmsConstants.LOCATION_GEN_MAX_PER_REQUEST 一致 */
  const MAX_PER_REQUEST = 50000;

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const submitting = ref(false);
  const title = ref('批量生成库位');

  /** 当前选中库区的容量包络（上限），用于前端即时校验 */
  const envelope = ref<{ rowCount?: number; colCount?: number; layerCount?: number; name?: string }>({});

  const [registerForm, { setFieldsValue, validate, getFieldsValue, resetFields, updateSchema }] = useForm({
    labelWidth: 110,
    schemas: [
      { label: '库区', field: 'warehouseId', component: 'Select', required: true, componentProps: { options: [] } },
      { label: '起始排', field: 'rowFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束排', field: 'rowTo', component: 'InputNumber', required: true },
      { label: '起始列', field: 'colFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束列', field: 'colTo', component: 'InputNumber', required: true },
      { label: '起始层', field: 'layerFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束层', field: 'layerTo', component: 'InputNumber', required: true },
    ],
  });

  const [registerModal, { closeModal, changeLoading }] = useModalInner(async (data) => {
    resetFields();
    envelope.value = {};
    const w = data?.warehouse;
    if (!w) {
      return;
    }
    envelope.value = {
      rowCount: w.rowCount,
      colCount: w.colCount,
      layerCount: w.layerCount,
      name: w.warehouseName,
    };
    // 库区在左侧树里已经选定了，这里只作展示，避免「树选 A、弹窗选 B」的歧义
    updateSchema({
      field: 'warehouseId',
      componentProps: {
        options: [{ label: w.warehouseName, value: w.id }],
        disabled: true,
      },
    });
    await setFieldsValue({
      warehouseId: w.id,
      rowFrom: 1,
      rowTo: w.rowCount ?? undefined,
      colFrom: 1,
      colTo: w.colCount ?? undefined,
      layerFrom: 1,
      layerTo: w.layerCount ?? undefined,
    });
  });

  /**
   * 实时预计生成数 = 各段长度的乘积。
   *
   * 刻意叫「本次预计生成数」而非「仓库总容量」—— 库区维度只是**上限**，
   * 料箱不一定堆满仓库，范围由运维自己选。
   */
  const total = computed(() => {
    const v = getFieldsValue();
    const span = (from?: number, to?: number) => (from >= 1 && to >= from ? to - from + 1 : 0);
    return span(v.rowFrom, v.rowTo) * span(v.colFrom, v.colTo) * span(v.layerFrom, v.layerTo);
  });

  /** 超出声明维度的那一项，返回给用户看的提示（未超出则空） */
  const envelopeError = computed(() => {
    const v = getFieldsValue();
    const check = (label: string, to?: number, cap?: number) =>
      to && cap && to > cap ? `${label} ${to} 超出库区上限 ${cap}` : '';
    return (
      check('结束排', v.rowTo, envelope.value.rowCount) ||
      check('结束列', v.colTo, envelope.value.colCount) ||
      check('结束层', v.layerTo, envelope.value.layerCount)
    );
  });

  const overLimit = computed(() => total.value > MAX_PER_REQUEST || !!envelopeError.value);

  const overLimitMessage = computed(() =>
    envelopeError.value ? envelopeError.value : `单次最多生成 ${MAX_PER_REQUEST} 个库位，本次 ${total.value} 个，请按层 / 列分批`,
  );

  async function handleSubmit() {
    if (overLimit.value) {
      createMessage.error(overLimitMessage.value);
      return;
    }
    submitting.value = true;
    changeLoading(true);
    try {
      const values = await validate();
      const res: any = await generateLocations(values);
      // skipped 是「已存在被跳过」，不是失败 —— 必须显式说出来，
      // 否则重跑后看到「生成了 0 个」会被误读为失败。
      createMessage.success(`生成 ${res.generated} 个，跳过 ${res.skipped} 个`);
      closeModal();
      emit('success', res);
    } catch (err: any) {
      createMessage.error(err?.message || '生成失败');
    } finally {
      submitting.value = false;
      changeLoading(false);
    }
  }

  defineExpose({});
</script>
```

- [ ] **Step 4: 写其余组件**

`warehouse/components/WarehouseTree.vue`：

```vue
<template>
  <div>
    <BasicTree
      title="库区"
      :treeData="treeData"
      :fieldNames="{ title: 'title', key: 'key', children: 'children' }"
      :selectedKeys="selectedKeys"
      defaultExpandAll
      clickRowToExpand
      @select="onSelect"
    >
      <template #headerTitle>
        <span>库区</span>
        <a-button type="link" size="small" v-auth="'wms:warehouse:add'" @click="onCreate">新增库区</a-button>
      </template>
      <template #action="{ record }">
        <!-- 类型节点是合成的分组，不可编辑/删除；只有库区节点才有操作 -->
        <template v-if="!String(record.key).startsWith('type:')">
          <a-button type="link" size="small" v-auth="'wms:warehouse:edit'" @click.stop="onEdit(record)">编辑</a-button>
          <a-popconfirm title="确定删除吗?" @confirm="onDelete(record)">
            <a-button type="link" size="small" danger v-auth="'wms:warehouse:delete'">删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </BasicTree>
    <WarehouseModal @register="registerModal" @success="onCreated" />
  </div>
</template>

<script lang="ts" name="wms-warehouse-tree" setup>
  import { onMounted, ref } from 'vue';
  import { BasicTree } from '/@/components/Tree';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getTreeList, deleteWarehouse } from '../warehouse.api';
  import WarehouseModal from './WarehouseModal.vue';
  import { useModal } from '/@/components/Modal';

  const emit = defineEmits(['select', 'refresh']);
  const { createMessage } = useMessage();
  const treeData = ref<any[]>([]);
  const selectedKeys = ref<string[]>([]);
  const [registerModal, { openModal }] = useModal();

  async function loadTree() {
    treeData.value = (await getTreeList()) || [];
  }

  function onSelect(keys: string[], info: any) {
    const key = keys?.length ? String(keys[0]) : '';
    selectedKeys.value = key ? [key] : [];
    const node = info?.node ?? {};
    if (key.startsWith('type:')) {
      emit('select', { warehouseType: key.substring('type:'.length) });
    } else {
      emit('select', { warehouseId: key, warehouseName: node.title });
    }
  }

  function onCreate() {
    openModal(true, { isUpdate: false });
  }

  function onEdit(record: any) {
    openModal(true, { record, isUpdate: true });
  }

  async function onDelete(record: any) {
    try {
      await deleteWarehouse({ id: record.key }, () => {
        createMessage.success('删除成功');
        if (selectedKeys.value[0] === record.key) {
          selectedKeys.value = [];
          emit('select', {});
        }
        loadTree();
      });
    } catch (e: any) {
      createMessage.error(e?.message || '删除失败');
    }
  }

  /** 库区新增/编辑成功后刷新树，并让右侧列表重新拉取（库区名可能变了） */
  function onCreated() {
    loadTree();
    emit('refresh');
  }

  onMounted(loadTree);
  defineExpose({ loadTree });
</script>
```

`warehouse/components/WarehouseModal.vue`：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit">
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-warehouse-modal" setup>
  import { ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { formSchema } from '../warehouse.data';
  import { saveOrUpdateWarehouse } from '../warehouse.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const isUpdate = ref(false);
  const title = ref('');

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    title.value = isUpdate.value ? '编辑库区' : '新增库区';
    if (isUpdate.value) {
      await setFieldsValue({ ...data.record });
    }
  });

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await saveOrUpdateWarehouse(values, isUpdate.value);
      createMessage.success(isUpdate.value ? '编辑成功' : '新增成功');
      closeModal();
      emit('success');
    } catch (e: any) {
      createMessage.error(e?.message || '保存失败');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

`warehouse/components/LocationAttrModal.vue`：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="500px">
    <a-alert
      v-if="isOccupied"
      type="warning"
      show-icon
      class="mb-3"
      message="该库位已占用（库位内有物料），不允许修改属性。"
      description="若确认该库位已清空，请使用列表中的「释放」操作。"
    />
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-attr-modal" setup>
  import { computed, ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { attrFormSchema } from '../location.data';
  import { updateLocationAttr } from '../location.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const title = ref('库位属性');
  /** 占用时后端会拒绝，这里先禁用提交按钮，避免让用户白填一遍 */
  const isOccupied = ref(false);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: attrFormSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isOccupied.value = data?.record?.status === '占用';
    await setFieldsValue({ ...data?.record });
  });

  async function handleSubmit() {
    if (isOccupied.value) {
      createMessage.error('库位内有物料，不允许修改属性');
      return;
    }
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await updateLocationAttr(values);
      createMessage.success('修改成功');
      closeModal();
      emit('success');
    } catch (e: any) {
      // 后端的约束②报错原样呈现，这是真实原因，不要改写成笼统的「保存失败」
      createMessage.error(e?.message || '保存失败');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

`warehouse/components/LocationModal.vue`（手工补录空洞）：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="500px">
    <a-alert type="info" show-icon class="mb-3" message="库位编码由系统按规则自动生成，无需填写。" />
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-modal" setup>
  import { ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { formSchema } from '../location.data';
  import { saveOrUpdateLocation } from '../location.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const isUpdate = ref(false);
  const title = ref('');

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    title.value = isUpdate.value ? '编辑库位' : '新增库位';
    if (isUpdate.value) {
      await setFieldsValue({ ...data.record });
    } else if (data?.warehouseId) {
      await setFieldsValue({ warehouseId: data.warehouseId });
    }
  });

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await saveOrUpdateLocation(values, isUpdate.value);
      createMessage.success(isUpdate.value ? '编辑成功' : '新增成功');
      closeModal();
      emit('success');
    } catch (e: any) {
      createMessage.error(e?.message || '保存失败');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 5: 写主页面（左树右表）**

`warehouse/index.vue`：

```vue
<template>
  <div :class="`${prefixCls}`">
    <a-row :gutter="10">
      <a-col :xl="6" :lg="24" :md="24" style="margin-bottom: 10px">
        <WarehouseTree @select="onTreeSelect" @refresh="handleSuccess" />
      </a-col>
      <a-col :xl="18" :lg="24" :md="24" style="margin-bottom: 10px">
        <a-alert
          v-if="envelopeWarning"
          type="warning"
          show-icon
          class="mb-2"
          :message="envelopeWarning"
        />
        <BasicTable @register="registerTable" :rowSelection="rowSelection">
          <template #tableTitle>
            <a-button type="primary" preIcon="ant-design:thunderbolt-outlined" v-auth="'wms:location:generate'"
                      :disabled="!currentWarehouseId" @click="handleGenerate">
              批量生成
            </a-button>
            <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:location:add'"
                      :disabled="!currentWarehouseId" @click="handleCreate">
              新增库位
            </a-button>
          </template>
          <template #action="{ record }">
            <TableAction :actions="getTableAction(record)" />
          </template>
        </BasicTable>
      </a-col>
    </a-row>
    <GenerateModal @register="registerGenerateModal" @success="handleSuccess" />
    <LocationAttrModal @register="registerAttrModal" @success="handleSuccess" />
    <LocationModal @register="registerLocationModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-warehouse" setup>
  import { computed, ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import WarehouseTree from './components/WarehouseTree.vue';
  import GenerateModal from './components/GenerateModal.vue';
  import LocationAttrModal from './components/LocationAttrModal.vue';
  import LocationModal from './components/LocationModal.vue';
  import { columns, LOCATION_STATUS_COLOR, searchFormSchema } from './location.data';
  import { list, deleteLocation, releaseLocation } from './location.api';
  import { queryById } from './warehouse.api';

  const { prefixCls } = useDesign('wms-warehouse');
  const { createMessage } = useMessage();

  /** 左侧树筛选条件：按类型过滤 or 按单个库区过滤 */
  const filter = ref<{ warehouseId?: string; warehouseType?: string }>({});
  const currentWarehouseId = computed(() => filter.value.warehouseId ?? '');
  /** 选中库区的详情（含 outOfEnvelopeCount），供生成弹窗带出维度与越界提示 */
  const warehouseDetail = ref<any>(null);
  const envelopeWarning = ref('');

  const [registerGenerateModal, { openModal: openGenerateModal }] = useModal();
  const [registerAttrModal, { openModal: openAttrModal }] = useModal();
  const [registerLocationModal, { openModal: openLocationModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
      beforeFetch(params) {
        // 必须返回合并结果：useListPage 的导出侧没有 `|| params` 兜底，
        // 不返回会导致 Object.assign(undefined, …) 抛 TypeError。
        return Object.assign(params, filter.value);
      },
    },
  });
  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  async function onTreeSelect(node: any) {
    filter.value = node ?? {};
    warehouseDetail.value = null;
    envelopeWarning.value = '';
    if (node?.warehouseId) {
      // 维度可自由增减，缩维后已有库位可能落在新声明之外 —— 系统不拦截，但要让人看见
      const detail: any = await queryById({ id: node.warehouseId });
      warehouseDetail.value = detail;
      if (detail?.outOfEnvelopeCount > 0) {
        envelopeWarning.value = `有 ${detail.outOfEnvelopeCount} 个库位位于当前维度之外（库区声明为 ${detail.rowCount} 排 × ${detail.colCount} 列 × ${detail.layerCount} 层）。系统不做拦截，请确认是否需要调整库区维度或清理库位。`;
      }
    }
    reload();
  }

  function handleGenerate() {
    openGenerateModal(true, { warehouse: warehouseDetail.value });
  }

  function handleCreate() {
    openLocationModal(true, { isUpdate: false, warehouseId: currentWarehouseId.value });
  }

  function handleEdit(record: any) {
    openAttrModal(true, { record });
  }

  async function handleRelease(record: any) {
    try {
      await releaseLocation({ id: record.id });
      createMessage.success('释放成功');
      handleSuccess();
    } catch (e: any) {
      createMessage.error(e?.message || '释放失败');
    }
  }

  async function handleDelete(record: any) {
    await deleteLocation({ id: record.id }, handleSuccess);
  }

  function getTableAction(record: any) {
    const actions: any[] = [
      { label: '属性', auth: 'wms:location:attr', onClick: handleEdit.bind(null, record) },
    ];
    // 释放仅对「占用」可见 —— 它是把占用改回空闲的唯一人工通道（spec §5.3）
    if (record.status === '占用') {
      actions.push({
        label: '释放',
        auth: 'wms:location:release',
        popConfirm: { title: '确认该库位已清空？释放后状态变为「空闲」', confirm: handleRelease.bind(null, record) },
      });
    }
    // 删除仅对「空闲」可见（后端同样强制）
    if (record.status === '空闲') {
      actions.push({
        label: '删除',
        auth: 'wms:location:delete',
        popConfirm: { title: '确定删除吗?', confirm: handleDelete.bind(null, record) },
      });
    }
    return actions;
  }
</script>
```

- [ ] **Step 6: 类型检查与构建**

Run: `cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3 && pnpm build`
Expected: `vue-tsc` 无错误、`vite build` 产出 `dist/`。

> 若报 `Tree` / `BasicTree` 找不到：确认 `src/components/Tree` 存在（Vben 内置）。
> 若报未使用变量，检查 `noUnusedLocals` 相关规则并按提示清理。

- [ ] **Step 7: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecgboot-vue3/src/views/wms/warehouse/
git commit -m "feat(wms): BD-003 前端库区与库位页（左树右表 + 批量生成 + 属性/释放）"
```

---

## Task 13: 前端「设备管理」页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/device/device.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/device/device.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/device/components/DeviceModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/device/components/DeviceBindModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/device/index.vue`

**Interfaces:**
- Consumes: Task 10 的 REST 端点；Task 12 已建立的 `defHttp` 用法范式
- Produces: 路由组件 `wms/device/index`（Task 1 的菜单 `1910000000000000052` 已指向该 component 路径）

- [ ] **Step 1: 写 API 与定义**

`device/device.api.ts`：

```ts
import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/device/list',
  listUnbound = '/wms/device/listUnbound',
  add = '/wms/device/add',
  edit = '/wms/device/edit',
  bind = '/wms/device/bind',
  unbind = '/wms/device/unbind',
  reportStatus = '/wms/device/reportStatus',
  deleteOne = '/wms/device/delete',
  deleteBatch = '/wms/device/deleteBatch',
  checkCode = '/wms/device/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

export const listUnbound = (params?) => defHttp.get({ url: Api.listUnbound, params });

export const checkDeviceCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateDevice = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

/** 绑定。绑定本身成功即成功，离线告警在返回体的 warning 字段里 */
export const bindDevices = (params) => defHttp.post({ url: Api.bind, params });

export const unbindDevices = (params) => defHttp.post({ url: Api.unbind, params });

export const reportDeviceStatus = (params) => defHttp.post({ url: Api.reportStatus, params });

export const deleteDevice = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());
```

`device/device.data.ts`：

```ts
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkDeviceCode } from './device.api';

/** 设备类型（与后端 WmsConstants 的中文字面量一致） */
export const DEVICE_TYPE_OPTIONS = [
  { label: '堆垛机', value: '堆垛机' },
  { label: 'SCR', value: 'SCR' },
  { label: '分拣单元', value: '分拣单元' },
  { label: '充电桩', value: '充电桩' },
];

export const DEVICE_STATUS_OPTIONS = [
  { label: '在线', value: '在线' },
  { label: '离线', value: '离线' },
  { label: '故障', value: '故障' },
];

/** 状态色：在线(绿) / 离线(红) / 故障(橙) */
export const DEVICE_STATUS_COLOR: Record<string, string> = {
  在线: 'green',
  离线: 'red',
  故障: 'orange',
};

export const columns: BasicColumn[] = [
  { title: '设备编码', align: 'center', dataIndex: 'deviceCode', width: 140 },
  { title: '设备名称', align: 'center', dataIndex: 'deviceName', width: 160 },
  { title: '设备类型', align: 'center', dataIndex: 'deviceType', width: 100 },
  { title: '归属库区', align: 'center', dataIndex: 'warehouseName', width: 140 },
  { title: '状态', align: 'center', dataIndex: 'status', width: 90 },
  { title: '最近心跳', align: 'center', dataIndex: 'lastHeartbeat', width: 170 },
  { title: '最近告警', align: 'center', dataIndex: 'lastAlertTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '设备编码', field: 'deviceCode', component: 'JInput', colProps: { span: 6 } },
  { label: '设备名称', field: 'deviceName', component: 'JInput', colProps: { span: 6 } },
  {
    label: '设备类型',
    field: 'deviceType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: DEVICE_TYPE_OPTIONS, allowClear: true, placeholder: '请选择类型' },
  },
  {
    // 「只看离线」就是按状态筛选
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: DEVICE_STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '设备编码',
    field: 'deviceCode',
    component: 'Input',
    required: true,
    dynamicRules: ({ model }) => [
      { required: true, message: '请输入设备编码!' },
      {
        validator: async (_rule: any, value: string) => {
          if (!value) {
            return Promise.resolve();
          }
          const res: any = await checkDeviceCode({ code: value, id: model.id });
          if (res && res.success === false) {
            return Promise.reject(res.message || '设备编码已存在，请重新输入');
          }
          return Promise.resolve();
        },
      },
    ],
  },
  { label: '设备名称', field: 'deviceName', component: 'Input', required: true },
  {
    label: '设备类型',
    field: 'deviceType',
    component: 'Select',
    required: true,
    componentProps: { options: DEVICE_TYPE_OPTIONS, placeholder: '请选择设备类型' },
  },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    defaultValue: '离线',
    componentProps: { options: DEVICE_STATUS_OPTIONS },
    helpMessage: '置为「在线」时会同时打上心跳时间',
  },
];

/** 绑定弹窗的表单：只选库区，设备用表格勾选 */
export const bindFormSchema: FormSchema[] = [
  {
    label: '目标库区',
    field: 'warehouseId',
    component: 'Select',
    required: true,
    componentProps: { options: [], placeholder: '请选择库区' },
  },
];
```

- [ ] **Step 2: 写设备表单弹窗**

`device/components/DeviceModal.vue`：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="600px">
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-device-modal" setup>
  import { ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { formSchema } from '../device.data';
  import { saveOrUpdateDevice } from '../device.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const isUpdate = ref(false);
  const title = ref('');

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    title.value = isUpdate.value ? '编辑设备' : '新增设备';
    if (isUpdate.value) {
      await setFieldsValue({ ...data.record });
    }
  });

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await saveOrUpdateDevice(values, isUpdate.value);
      createMessage.success(isUpdate.value ? '编辑成功' : '新增成功');
      closeModal();
      emit('success');
    } catch (e: any) {
      createMessage.error(e?.message || '保存失败');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 3: 写绑定弹窗**

`device/components/DeviceBindModal.vue`：

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" title="绑定设备到库区" @ok="handleSubmit"
              :confirmLoading="submitting" width="760px">
    <BasicForm @register="registerForm" />
    <div class="mt-3">
      <a-table
        :dataSource="devices"
        :columns="bindColumns"
        :rowSelection="{ selectedRowKeys, onChange: onSelectChange }"
        :pagination="false"
        :rowKey="(r) => r.id"
        size="small"
        :scroll="{ y: 260 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'status'">
            <a-tag :color="DEVICE_STATUS_COLOR[record.status] || 'default'">{{ record.status }}</a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'warehouseName'">
            {{ record.warehouseName || '未绑定' }}
          </template>
        </template>
      </a-table>
    </div>
    <a-alert
      v-if="offlineSelectedCount > 0"
      type="warning"
      show-icon
      class="mt-3"
      :message="`已勾选 ${offlineSelectedCount} 台离线设备，保存后将产生离线告警（绑定本身仍会成功）`"
    />
  </BasicModal>
</template>

<script lang="ts" name="wms-device-bind-modal" setup>
  import { computed, ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { bindFormSchema, DEVICE_STATUS_COLOR } from '../device.data';
  import { bindDevices, list as listDevices } from '../device.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const submitting = ref(false);
  const devices = ref<any[]>([]);
  const selectedRowKeys = ref<string[]>([]);

  /** 设备用表格勾选而不是下拉：要能一眼看到哪些是离线设备 */
  const bindColumns = [
    { title: '设备编码', dataIndex: 'deviceCode', width: 130 },
    { title: '设备名称', dataIndex: 'deviceName', width: 150 },
    { title: '类型', dataIndex: 'deviceType', width: 100 },
    { title: '当前归属', dataIndex: 'warehouseName', width: 130 },
    { title: '状态', dataIndex: 'status', width: 90 },
  ];

  const offlineSelectedCount = computed(
    () => devices.value.filter((d) => selectedRowKeys.value.includes(d.id) && d.status === '离线').length,
  );

  const [registerForm, { resetFields, updateSchema, validate }] = useForm({
    labelWidth: 90,
    schemas: bindFormSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    selectedRowKeys.value = [];
    setModalProps({ confirmLoading: false });
    // 提供全部库区作为目标（可选未绑定设备，也可从别的库区搬迁过来）
    const warehouses = data?.warehouses ?? [];
    updateSchema({
      field: 'warehouseId',
      componentProps: {
        options: warehouses.map((w: any) => ({ label: `${w.warehouseName}（${w.warehouseCode}）`, value: w.id })),
      },
    });
    devices.value = (await listDevices({ pageNo: 1, pageSize: 200 }))?.records ?? [];
  });

  function onSelectChange(keys: string[]) {
    selectedRowKeys.value = keys;
  }

  async function handleSubmit() {
    if (selectedRowKeys.value.length === 0) {
      createMessage.error('请至少选择一台设备');
      return;
    }
    try {
      const values = await validate();
      submitting.value = true;
      setModalProps({ confirmLoading: true });
      const res: any = await bindDevices({ warehouseId: values.warehouseId, deviceIds: selectedRowKeys.value });
      // 绑定成功与离线告警是两件事：告警走 warning，绝不能让使用者误判成「绑定失败」
      if (res?.warning) {
        createMessage.warning(res.warning);
      } else {
        createMessage.success(`绑定成功，共 ${res?.bound ?? 0} 台`);
      }
      closeModal();
      emit('success');
    } catch (e: any) {
      createMessage.error(e?.message || '绑定失败');
    } finally {
      submitting.value = false;
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 4: 写设备主页面**

`device/index.vue`：

```vue
<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:device:add'" @click="handleCreate">
          新增
        </a-button>
        <a-button type="primary" preIcon="ant-design:link-outlined" v-auth="'wms:device:bind'" @click="handleBind">
          绑定库区
        </a-button>
        <a-button v-auth="'wms:device:bind'" :disabled="selectedRowKeys.length === 0" @click="handleUnbind">
          解绑
        </a-button>
        <a-button type="primary" danger v-auth="'wms:device:delete'" :disabled="selectedRowKeys.length === 0"
                  @click="batchHandleDelete">
          批量删除
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <DeviceModal @register="registerModal" @success="handleSuccess" />
    <DeviceBindModal @register="registerBindModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-device" setup>
  import { h, ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { Tag } from 'ant-design-vue';
  import DeviceModal from './components/DeviceModal.vue';
  import DeviceBindModal from './components/DeviceBindModal.vue';
  import { columns, searchFormSchema, DEVICE_STATUS_COLOR } from './device.data';
  import { list, deleteDevice, unbindDevices } from './device.api';
  import { list as listWarehouses } from '../warehouse/warehouse.api';

  const { prefixCls } = useDesign('wms-device');
  const { createMessage } = useMessage();

  const [registerModal, { openModal }] = useModal();
  const [registerBindModal, { openModal: openBindModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 200 },
    },
  });
  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  // 状态用 Tag 渲染（放在 data.ts 的 customRender 也行，这里集中处理以便复用颜色表）
  columns.forEach((col) => {
    if (col.dataIndex === 'status') {
      (col as any).customRender = ({ text }: { text: string }) =>
        h(Tag, { color: DEVICE_STATUS_COLOR[text] || 'default' }, () => text);
    }
  });

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record: any) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleBind() {
    const page: any = await listWarehouses({ pageNo: 1, pageSize: 200 });
    openBindModal(true, { warehouses: page?.records ?? [] });
  }

  async function handleUnbind() {
    try {
      await unbindDevices(selectedRowKeys.value);
      createMessage.success('解绑成功');
      handleSuccess();
    } catch (e: any) {
      createMessage.error(e?.message || '解绑失败');
    }
  }

  async function handleDelete(record: any) {
    await deleteDevice({ id: record.id }, handleSuccess);
  }

  async function batchHandleDelete() {
    for (const id of selectedRowKeys.value) {
      await deleteDevice({ id }, () => undefined);
    }
    createMessage.success('删除成功');
    handleSuccess();
  }

  function getTableAction(record: any) {
    return [
      { label: '编辑', auth: 'wms:device:edit', onClick: handleEdit.bind(null, record) },
      {
        label: '删除',
        auth: 'wms:device:delete',
        popConfirm: { title: '确定删除吗?', confirm: handleDelete.bind(null, record) },
      },
    ];
  }
</script>
```

> 颜色表放在 `device.data.ts` 是为了「状态→颜色」只有一处定义；
> 状态列的 `Tag` 渲染在 `index.vue` 里就地改写列的 `customRender`，
> 因为 `columns` 是模块级共享数组，在这里改会污染其它引用（本项目只此一处引用，可接受）。

- [ ] **Step 5: 类型检查与构建**

Run: `cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3 && pnpm build`
Expected: `vue-tsc` 无错误、`vite build` 成功。

- [ ] **Step 6: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecgboot-vue3/src/views/wms/device/
git commit -m "feat(wms): BD-003 前端设备管理页（离线标红 + 绑定弹窗告警提示）"
```

---

## Task 14: 端到端验收与收尾

**Files:**
- Create: `docs/superpowers/plans/2026-10-15-yx-wms-bd-003-验收报告.md`

**Interfaces:**
- Consumes: Task 1–13 的全部产出
- Produces: 验收报告（含每步的实际输出，成功与失败都留）

- [ ] **Step 1: 后端全量构建与测试**

Run:
```bash
bash /d/tools/m.sh -P dev clean package
bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='Wms*Test' -DskipTests=false
```
Expected: `BUILD SUCCESS`；测试全绿（**既有 4 个功能的测试也必须全绿**）。
把**实际输出原样贴进验收报告**，失败也贴。

- [ ] **Step 2: 前端构建**

Run: `cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3 && pnpm build`
Expected: 成功。输出贴进报告。

- [ ] **Step 3: 重启后端（**必须**）**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev install -DskipTests        # 先 install：新接口不进本地仓库，重启后仍 404
bash /d/tools/m.sh -P dev spring-boot:run
```
> **本环境实测**：改完 wms 模块若不 `install` + **重启后端**，新接口会 404 而系统看起来一切正常。
> 另外后端实际监听 **8081**（`jeecgboot-vue3/.env.development.local` 改过代理）。

- [ ] **Step 4: Flyway 日志取证**

在后端启动日志中检索 `V3.9.5_5` 与异常关键字：

```bash
grep -iE "3\.9\.5\.5|FlywayException|Migration.*failed" <启动日志>
```
Expected: 出现迁移记录，且**没有**任何异常行。
> 本环境实测 **Flyway 会静默吞错**：脚本报错时应用照常启动，只在后续表现为「表不存在」的 500。
> 故必须真的 grep 日志，不能凭「启动成功」下结论。

- [ ] **Step 5: 按规格 §11.3 的 18 步逐条手工验收**

前端起 `pnpm dev`（端口 3100）→ 登录 → 逐条执行并记录结果。**每一条都要写进验收报告：做了什么、看到什么**。

| # | 动作 | 期望 |
| --- | --- | --- |
| 1 | Flyway 日志 | 见上一步 |
| 2 | 菜单 | 「基础管理」下出现「库区与库位」「设备管理」 |
| 3 | 新增库区 `LB`（立库 1×6×24），再提交一次同编码 | 第二次提示「库区编码已存在，请重新输入」 |
| 4 | 选中 `LB` → 批量生成（自动带出 1/1/6/24）→ 提交 | 「生成 144 个，跳过 0 个」；首条 `LB-R01-C01-L01`，末条 `LB-R01-C06-L24` |
| 5 | **原样再提交一次** | 「生成 0 个，跳过 144 个」 |
| 6 | SQL 取证 | `SELECT COUNT(*) … WHERE warehouse_id=<LB>` = 144；`L24` 那条编码为 `LB-R01-C06-L24` |
| 7a | 层范围改 1~30 | 「第 30 层超出库区层数上限 24」 |
| 7b | 新建 `MJ`（敏捷仓 1×60422×10），生成列 1~60422 | 「单次最多生成 50000 个库位，本次 604220 个，请按层 / 列分批」 |
| 8 | 新增设备 `DDJ01`（默认离线）→ 绑定 `LB` | 绑定成功 + 弹**警告**；返回 `bound=1`、`offlineDevices=["DDJ01"]` |
| 9 | SQL + 公告表 | `status=离线`、`last_alert_time` 已写入；`sys_announcement` 有「WMS设备离线告警」 |
| 10 | 30 分钟内重复上报离线 → 再把 `last_alert_time` 置 40 分钟前重发 | 第一次不新增公告；第二次新增 |
| 11 | 上报 `在线` | `status=在线`、`last_heartbeat` 刷新、**`last_alert_time` 被清空** |
| 12 | 重启应用（不手工点击） | 日志出现离线扫描任务注册记录（或「已存在，跳过」） |
| 13 | 把 `DDJ01` 改成 `在线` 且 `last_heartbeat = NOW() - INTERVAL 20 MINUTE` → 定时任务点「立即执行」 | `status` 变 `离线`，日志有转离线记录，新增 1 条聚合公告 |
| 14 | 新建一台 `last_heartbeat IS NULL` 的设备 → 点「立即执行」 | **保持原状态、零告警**（不误报） |
| 15 | 紧接着**再点一次立即执行** | 不再产生第二条公告（已离线的被 WHERE 排除） |
| 16 | 把某库位改为 `占用` → 点「属性」改承重 → 再点「释放」→ 再改承重 | 第一次「库位内有物料，不允许修改属性」；释放后变空闲；此时改承重成功 |
| 17 | 树筛选 | 点类型节点「立库」只出立库库位；点库区节点 `LB` 只出 LB 的 |
| 18a | `LB` 层数 24 → **缩到 10** | **保存成功，无拦截** |
| 18b | SQL 取 `layer_no=24` 的编码 | 仍为 `LB-R01-C06-L24`，**逐字符未变** |
| 18c | 库区页 | 提示「**有 84 个库位位于当前维度之外**」 |
| 18d | 层数 10 → **扩到 9999**，再手工生成第 100 层 | 保存成功；第 100 层编码为 `LB-R01-C01-L100`（段宽自然变宽、不截断） |
| 18e | 清空三个维度 → 点批量生成 | 保存成功；生成被拒：「库区未配置网格维度，请先在库区上填写排 / 列 / 层上限」 |

- [ ] **Step 6: 写验收报告**

创建 `docs/superpowers/plans/2026-10-15-yx-wms-bd-003-验收报告.md`，结构照
`docs/superpowers/plans/2026-10-14-yx-wms-bd-002-验收报告.md`。必须包含：

1. 验收标准对照表（`能按规则批量生成库位` / `设备绑定成功，离线设备有告警` / 三条约束）；
2. 第 5 步 18 条的**实际执行结果**（含命令与原始输出，**失败也照实写**）；
3. **未覆盖项与遗留风险**（照抄规格 §12 的 10 条，并补上执行中发现的新问题）。

- [ ] **Step 7: 提交**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add docs/superpowers/plans/2026-10-15-yx-wms-bd-003-验收报告.md
git commit -m "docs(wms): BD-003 验收报告（18 步端到端取证）"
```

---

## 计划自检记录

### 1. 规格覆盖

| 规格章节 | 落点 |
| --- | --- |
| §4 数据模型（3 表） | Task 1 |
| §5.1 库区编码唯一 | Task 4 |
| §5.2 编码规则 + 维度自由增减 + 越界可见 | Task 3（规则）、Task 4（维度无守卫）、Task 6/7（越界计数） |
| §5.3 属性守卫 + release | Task 6 |
| §5.4 绑定与离线告警 | Task 9、Task 10 |
| §5.5.1 不做整体回滚 | Task 5 |
| §6.1–6.2 分层与 `insertIgnoreBatch` | Task 2、Task 5 |
| §6.3 告警投递与节流 | Task 8 |
| §6.4 三条触发链路 | Task 9（①②）、Task 11（③） |
| §6.5 启动自注册器 | Task 11 |
| §7 接口清单 | Task 4、Task 7、Task 10 |
| §7.4 `QueryGenerator` 置空 | Task 7、Task 10 |
| §8 前端 | Task 12、Task 13 |
| §9 Flyway 与菜单 | Task 1 |
| §10 依赖变更（无） | 无任务 —— 已核实无需改动，故不需要任务 |
| §11.1 单测 | Task 3/4/5/6/7/8/9/10/11 |
| §11.2–11.3 构建与 E2E | Task 14 |
| §12 遗留与风险 | Task 14 第 6 步落进报告 |
| §13 交付物清单 | 文件结构一节 |

### 2. 占位符扫描

已逐条检查：无 `TBD` / `TODO` / 「稍后实现」/「同 Task N」/ 无代码的纯描述步骤。
每个代码步骤都给了可直接粘贴的完整内容。

### 3. 类型一致性

- `IWmsWarehouseService.requireEnabledWarehouse(String)` 在 Task 4 定义，Task 5、Task 9 使用，签名一致。
- `WmsLocationCodeGenerator.locationCode(String, Integer, Integer, Integer)` Task 3 定义，Task 5、Task 6 使用。
- `WmsLocationMapper.insertIgnoreBatch(List<WmsLocation>)` Task 2 定义，Task 5 使用。
- `IWmsDeviceAlertService.alertOffline(List<WmsDevice>, boolean)` Task 8 定义，Task 9、Task 11 使用；
  **Task 6 里 `WmsLocationServiceImpl` 未使用它**（正确 —— 库位与告警无关）。
- `IWmsLocationService` 在 Task 5 建立、Task 6 扩方法，Task 7 的控制器只调用 Task 6 结束时的完整集合。
- 错误文案在多处被断言，均与实现逐字一致：
  `库区未配置网格维度，请先在库区上填写排 / 列 / 层上限`、
  `单次最多生成 50000 个库位，本次 N 个，请按层 / 列分批`、
  `第 N 层超出库区层数上限 M`、`库位内有物料，不允许修改属性`、
  `仅占用状态的库位可释放`、`库位已占用或有故障，不允许删除`、
  `库位坐标不可修改，如需变更请删除后重新生成`、`设备不存在：<code>`。
- **一处刻意的不对称**：库区服务用 `wmsLocationMapper` / `wmsDeviceMapper` 直查（Task 4），
  而库位控制器用 `IWmsLocationService.countOutOfEnvelope`（Task 6）。前者在服务层内避免循环依赖，
  后者在控制器层避免库区服务反向依赖库位实体 —— 两处都是刻意的，不是遗漏。

### 4. Review Focus 归位

| # | 输入/失败模式 | 归属任务的测试 |
| --- | --- | --- |
| 1 | 库区编码超长 → 编码越界 + `INSERT IGNORE` 静默丢行 | Task 5 `generate_shouldRejectWhenCodeWouldExceedColumnWidth` |
| 2 | `warehouseType` 匹配不到 → `IN ()` 语法错误 500 | Task 7 `list_shouldReturnEmptyPageWhenWarehouseTypeMatchesNothing` |
| 3 | 设备/库位指向已逻辑删除的库区 → 回填 NPE | Task 7 `list_shouldTolerateMissingWarehouse`、Task 10 `list_shouldTolerateDeletedWarehouse` |
| 4 | `/reportStatus` 传不存在的 deviceCode → NPE | Task 9 `reportStatus_shouldRejectUnknownDeviceCode` |
| 5 | 并发两次 `/generate` 同范围 → 重复键异常 | Task 5 幂等用例（mapper 返回 0 时 `skipped` 记满）+ **Task 14 第 5 步第 5 条的真库验证**（`INSERT IGNORE` 的真实跳过语义只能由 MySQL 证明，单测覆盖不到，规格 §12-2 已声明） |