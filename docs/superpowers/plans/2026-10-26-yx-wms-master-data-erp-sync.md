# 永鑫WMS 主数据 ERP 同步骨架 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec 落地主数据骨架：Flyway 列与分类种子、仓库同步链路（白名单空转）、分类映射同步链路、工单 order_type 集成、默认关闭的 Job 与手动触发端点、前端口径与运维文档。

**Architecture:** 同步全部走既有 `WmsKingdeeFormClient`（ExecuteBillQuery）+ `LoginByAppSecret` 会话；新服务仿 `WmsKingdeeWorkOrderSyncServiceImpl` 模式（FORM_ID/FIELD_KEYS 常量、filter 拼装、syncLogService 留痕）；配置绑在 `WmsKingdeeProperties` 新增 `masterData` 嵌套；改名/建仓走服务层过删除守卫，不进 SQL 种子。

**Tech Stack:** Spring Boot（jakarta）+ MyBatis-Plus + Flyway + Quartz + JUnit 5 + Mockito；前端 Vue 3 + Vben。

**Spec:** `docs/superpowers/specs/2026-10-26-yx-wms-master-data-erp-sync-design.md`（已批准；任务引用其 §编号）

## Global Constraints

- Maven 一律 `bash /d/tools/m.sh -P dev ...`；裸 `mvn` 会把 SpringCloud 模块拉进 reactor。
- 改 wms 模块后必须：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests` + 重启后端 JVM，否则新端点 404。
- Flyway 静默失败：每次迁移后 grep 启动日志「flyway执行sql脚本失败」必须为 0，且 `flyway_schema_history` 对应行 `success=1`。
- DB 检查：`"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "..."`。
- 反目标：不拉白名单外仓库；不物理删改既有分类/仓库数据；appSecret 不落日志/前端/仓库配置。
- 配置键（spec §7）：`wms.sync.erp.kingdee.master-data.{enabled,org-id,stock-whitelist,stock-code-map,category-map,order-type-field,order-type-value-map}`；`enabled` 默认 `false`、`org-id` 默认 `"101"`、其余默认空。
- 提交：Conventional Commits `wms` scope，从 `D:\yongxin-WMS\JeecgBoot` 仓库根提交，结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## Review Focus

1. **双组织重复行互盖**——BD_STOCK 同码不同组织（100/101），不带组织过滤会静默互盖。→ Task 4 测试：filter 必含 `FUseOrgId.FNumber='101'`。
2. **空白名单误拉全量**（约 190 仓）→ Task 4 测试：`stock-whitelist` 为空时不发查询、返回 0 行。
3. **改名破坏引用**——LB/MJ 只改 `warehouse_name` 不动 `warehouse_code`。→ Task 4 测试：rename 保留 code。
4. **order_type 空配置写脏值**——金蝶字段未就位时不得把「客户订单」写进库。→ Task 6 测试：空配置 → `orderType == null`。
5. **Flyway 静默失败**——迁移坏了系统照常启动。→ Task 1 验证步骤。

---

### Task 1: Flyway 迁移 V3.9.5_24（列/索引/分类种子）

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_24__wms_master_data_erp_sync.sql`

**Interfaces:**
- Produces: `wms_work_order.order_type varchar(20) NULL`；`wms_warehouse.erp_stock_code varchar(50) NULL`、`erp_stock_name varchar(100) NULL`、`UNIQUE KEY uk_wms_warehouse_erp(erp_stock_code, deleted_at)`；`wms_material_category.erp_group_number varchar(100) NULL`、`erp_group_name varchar(200) NULL`；4 条分类种子（id `CATHALF/CATCJ/CATTC/CATCP`）。

- [ ] **Step 1: 写迁移脚本**（裸 ALTER，项目惯例只跑一次；头部注释只写用途）

```sql
-- 内容：主数据 ERP 同步骨架（spec 2026-10-26）：
--   工单类型列 / 仓库 ERP 映射列与唯一索引 / 分类 ERP 分组列 / 4 条分类种子
ALTER TABLE `wms_work_order`
    ADD COLUMN `order_type` varchar(20) NULL COMMENT '工单类型：客户订单/备库订单/委外订单/受托订单（ERP 同步，缺省空）';
ALTER TABLE `wms_warehouse`
    ADD COLUMN `erp_stock_code` varchar(50) NULL COMMENT '金蝶仓库编码（BD_STOCK.FNumber）',
    ADD COLUMN `erp_stock_name` varchar(100) NULL COMMENT '金蝶仓库名称',
    ADD UNIQUE INDEX `uk_wms_warehouse_erp` (`erp_stock_code`, `deleted_at`);
ALTER TABLE `wms_material_category`
    ADD COLUMN `erp_group_number` varchar(100) NULL COMMENT '金蝶物料分组编码（代表来源）',
    ADD COLUMN `erp_group_name` varchar(200) NULL COMMENT '金蝶物料分组名称（代表来源）';
INSERT IGNORE INTO `wms_material_category`
    (`id`,`category_code`,`category_name`,`parent_id`,`category_type`,`status`)
VALUES
    ('CATHALF','HALF','半成品','0','半成品','1'),
    ('CATCJ','CJ','初级产成品','0','初级产成品','1'),
    ('CATTC','TC','涂层产成品','0','涂层产成品','1'),
    ('CATCP','CP','产成品','0','产成品','1');
```

- [ ] **Step 2: 启动后端应用迁移并验证**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev spring-boot:run --server.port=8081   # 后台启动
```
grep 启动日志「flyway执行sql脚本失败」计数必须为 0；然后：
```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e \
"SELECT version,success FROM flyway_schema_history WHERE version='3.9.5.24'; \
 SELECT id,category_code,category_type FROM wms_material_category WHERE id LIKE 'CAT%' AND category_code IN ('HALF','CJ','TC','CP'); \
 SHOW COLUMNS FROM wms_warehouse LIKE 'erp%';"
```
Expected: `success=1`；4 行种子（HALF/CJ/TC/CP）；`erp_stock_code/erp_stock_name` 两列存在。验证完停止后端。

- [ ] **Step 3: Commit**

```bash
cd /d/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_24__wms_master_data_erp_sync.sql
git commit -m "feat(wms): 主数据同步骨架——order_type/仓库ERP列/分类种子 (V3.9.5_24)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: 常量与配置绑定

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/sync/WmsKingdeeProperties.java`
- Test: `src/test/java/org/jeecg/modules/wms/constant/WmsConstantsMasterDataTest.java`、`src/test/java/org/jeecg/modules/wms/sync/WmsKingdeePropertiesMasterDataTest.java`

**Interfaces:**
- Produces（后续任务按这些精确名字使用）:
  - `WmsConstants.CATEGORY_TYPE_PRIMARY = "初级产成品"`、`CATEGORY_TYPE_COATED = "涂层产成品"`、`CATEGORY_TYPE_FINISHED = "产成品"`（既有 `CATEGORY_TYPE_HALF_FINISHED="半成品"` 复用）、`WAREHOUSE_TYPE_NORMAL = "普通仓"`；
  - `WmsConstants.WORK_ORDER_TYPE_CUSTOMER = "客户订单"`、`WORK_ORDER_TYPE_STOCK = "备库订单"`、`WORK_ORDER_TYPE_OUTSOURCE = "委外订单"`、`WORK_ORDER_TYPE_TRUSTED = "受托订单"`（`ORDER_TYPE_OUTSOURCE` 已被 wms_order 域占用，故用 `WORK_ORDER_TYPE_` 前缀）；
  - `WmsKingdeeProperties.MasterData` 内嵌 `@Data static class`，字段与默认值：`boolean enabled = false; String orgId = "101"; List<String> stockWhitelist = new ArrayList<>(); Map<String,String> stockCodeMap = new LinkedHashMap<>(); List<String> categoryMap = new ArrayList<>(); String orderTypeField; Map<String,String> orderTypeValueMap = new LinkedHashMap<>();`，外加便捷方法 `boolean isMasterDataActive()` = `enabled && WmsKingdeeProperties 外层 isConfigured()`（外层已有该方法则直接复用）。
  - 挂载：`WmsKingdeeProperties` 加 `private MasterData masterData = new MasterData();`

- [ ] **Step 1: 写失败测试**
  - `WmsConstantsMasterDataTest`：断言上述 8 个常量值逐一等于 spec §9 的中文值；断言 `WORK_ORDER_TYPE_OUTSOURCE` 与 wms_order 域既有 `ORDER_TYPE_OUTSOURCE` 是两个不同常量（后者值不受影响）。
  - `WmsKingdeePropertiesMasterDataTest`：new 一个 `WmsKingdeeProperties`，断言 `masterData != null`、`orgId="101"`、`enabled=false`、三个集合非 null；set 两个值再断言 getter 生效。
- [ ] **Step 2: 运行确认失败**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsConstantsMasterDataTest
```
Expected: 编译失败「找不到符号」。
- [ ] **Step 3: 实现**（常量 + 嵌套类 + 默认值；集合用 `ArrayList`/`LinkedHashMap` 初始化）
- [ ] **Step 4: 运行通过**

```bash
bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='WmsConstantsMasterDataTest,WmsKingdeePropertiesMasterDataTest'
```
Expected: PASS。
- [ ] **Step 5: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/sync/WmsKingdeeProperties.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/constant/WmsConstantsMasterDataTest.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/sync/WmsKingdeePropertiesMasterDataTest.java
git commit -m "feat(wms): 主数据常量与 master-data 配置绑定

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: FormClient 翻页重载

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/sync/WmsKingdeeFormClient.java`
- Test: `src/test/java/org/jeecg/modules/wms/sync/WmsKingdeeFormClientTest.java`（新建）

**Interfaces:**
- Consumes: 既有 `executeBillQuery(String formId, String fieldKeys, String filter, int top)`（内部 `post(QUERY_PATH, Map.of("data", data), true)`）。
- Produces: `public WmsKingdeeResult executeBillQuery(String formId, String fieldKeys, String filter, int top, int startRow)` —— 5 参重载，data 增 `"StartRow"`；原 4 参方法改为委托 `executeBillQuery(formId, fieldKeys, filter, top, 0)`，外部调用零破坏。

- [ ] **Step 1: 写失败测试**——mock `RestTemplate`（注入方式照 `WmsKingdeeAuthServiceImplTest` 的做法；若 FormClient 构造不可注入 RestTemplate，则以最小侵入补一个包内可见测试构造）。断言：5 参调用时请求体 JSON 含 `"StartRow":500`；4 参调用时含 `"StartRow":0`。
- [ ] **Step 2: 运行确认失败**（同 Task 2 命令，`-Dtest=WmsKingdeeFormClientTest`，Expected: 编译失败或断言失败）
- [ ] **Step 3: 实现**（5 参重载 + 4 参委托；不改 `post` 可见性）
- [ ] **Step 4: 运行通过 + 全量回归**

```bash
bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsKingdeeFormClientTest
```
- [ ] **Step 5: Commit** `feat(wms): FormClient 支持 StartRow 翻页`（+Co-Authored-By）

### Task 4: 仓库同步服务 + 本地档案初始化

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsKingdeeWarehouseSyncService.java`
- Create: `.../service/impl/WmsKingdeeWarehouseSyncServiceImpl.java`
- Create: `.../vo/WmsKingdeeStockSyncResultVO.java`
- Modify: `.../entity/WmsWarehouse.java`（加 `private String erpStockCode;` `private String erpStockName;`）
- Modify: `.../controller/WmsWarehouseController.java`（加两个端点）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsKingdeeWarehouseSyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 `WmsKingdeeProperties`（masterData 配置）、Task 3 5 参查询（本任务用 4 参即可）、既有 `IWmsWarehouseService`、`IWmsSyncLogService`、`WmsKingdeeFormClient`、`WmsConstants.WAREHOUSE_TYPE_NORMAL`。
- Produces:
  - `WmsKingdeeStockSyncResultVO`：`int fetched; int renamed; int created; String message;`（+getter/setter）
  - `WmsKingdeeStockSyncResultVO syncStock()`——白名单空 → 不查询、`fetched=0`、message=「白名单未配置，跳过」；非空 → filter = `FUseOrgId.FNumber='<orgId>' AND FNumber in ('A','B',...)`，FieldKeys `FNumber,FName`，逐行 upsert：`stock-code-map` 命中金蝶编码 → 查既有行（按 `warehouse_code`）**只改 `warehouse_name`**（值为金蝶 FName）+ 盖 `erp_stock_code/erp_stock_name`，`renamed++`；未命中 → 新建 `warehouse_code=FNumber`、`warehouse_name=FName`、`warehouse_type=普通仓`、`created++`。每次写 syncLog。
  - `void initLocalArchives()`——幂等：`LB` 名改「半成品仓」、`MJ` 名改「产成品仓」（只改 name）；不存在才新建 `CJ 初级产成品仓`、`TC 涂层产成品仓`（type=普通仓，`erp_stock_code` 置空）。经 `IWmsWarehouseService` 落库。
  - Controller：`POST /wms/warehouse/sync-kingdee`、`POST /wms/warehouse/init-master-data`，返回 `Result.OK(...)`，仿 `WmsWorkOrderController` `/sync-kingdee` 写法。

- [ ] **Step 1: 写失败测试**（mock formClient/properties/warehouseService/syncLogService）：
  - 白名单空 → `formClient` 零交互、`fetched==0`；
  - filter 字符串含 `FUseOrgId.FNumber='101'` 与 `FNumber in ('X.2.4')`；
  - `stock-code-map` 命中 `02.001→MJ`：捕获 update 的实体，断言 `warehouseName` 变为金蝶 FName、`warehouseCode` 仍为 `MJ`；
  - 未命中 `X.2.4`：捕获 save 实体，断言 type=普通仓、`erpStockCode='X.2.4'`；
  - `initLocalArchives()` 二次调用不重复新建 CJ/TC（mock getByCode 返回已存在行时不再 save）。
- [ ] **Step 2: 运行确认失败**（编译失败）
- [ ] **Step 3: 实现**（upsert 前按 code 查询既有行；syncLog 记录每次同步摘要）
- [ ] **Step 4: 运行通过**（`-Dtest=WmsKingdeeWarehouseSyncServiceImplTest`）
- [ ] **Step 5: Commit** `feat(wms): 仓库同步服务与本地档案初始化（白名单空转）`（+Co-Authored-By）

### Task 5: 分类映射同步服务

**Files:**
- Create: `.../service/IWmsKingdeeCategorySyncService.java`、`.../service/impl/WmsKingdeeCategorySyncServiceImpl.java`
- Modify: `.../entity/WmsMaterialCategory.java`（加 `erpGroupNumber`/`erpGroupName`）
- Modify: `.../controller/WmsMaterialCategoryController.java`（加 `POST /sync-kingdee`）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsKingdeeCategorySyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 配置（`categoryMap` 列表，规则格式 `前缀=分类码`，如 `BC.1012=CP`）、Task 3 5 参查询（BD_MATERIAL 全量翻页拉取：`FNumber,FName,FMaterialGroup.FNumber,FMaterialGroup.FName`，按 500/页循环直至返回行数 < 页大小）、`IWmsMaterialCategoryService`（按 `category_code` 查）、`IWmsSyncLogService`。
- Produces: `WmsKingdeeStockSyncResultVO syncCategory()`（复用 Task 4 的 VO，字段语义：fetched=分组数、renamed=分类更新数、created=0）——`categoryMap` 为空 → 照常查询但零写入（空转，message=「分类映射未配置」）；命中规则的金蝶分组 → 把 `erp_group_number/name` 盖到对应分类行（代表=首个命中分组）；未命中的分组跳过；同步写 syncLog。

- [ ] **Step 1: 写失败测试**：空 categoryMap → 分类服务零调用；一条规则 `WA.2205=HALF` + mock 返回含 `WA.2205.x` 分组的物料行 → HALF 分类行被盖 `erp_group_number='WA.2205'`；无关分组跳过。
- [ ] **Step 2: 确认失败**
- [ ] **Step 3: 实现**（前缀匹配 `group.startsWith(rulePrefix)`；每分类只记首个命中代表）
- [ ] **Step 4: 通过 + Commit** `feat(wms): 物料分组→分类映射同步（E2 未配置时空转）`（+Co-Authored-By）

### Task 6: order_type 集成到工单同步

**Files:**
- Modify: `.../entity/WmsWorkOrder.java`（加 `private String orderType;`）
- Modify: `.../service/impl/WmsKingdeeWorkOrderSyncServiceImpl.java`
- Test: 扩展 `src/test/java/org/jeecg/modules/wms/service/impl/WmsKingdeeWorkOrderSyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 `WORK_ORDER_TYPE_*` 常量与 `orderTypeField/orderTypeValueMap` 配置。
- Produces: FIELD_KEYS 动态追加 `orderTypeField`（配置非空时，追加在末尾并记录下标）；落库规则——配置空 → `orderType=null`；配置非空 → 行值经 `order-type-value-map`（金蝶值→类型）映射后落库，未命中 → `null` + `log.warn`（单次同步过程内按未知值去重，防刷屏）+ syncLog 告警一条。

- [ ] **Step 1: 写失败测试**：空配置 → 捕获 save 的实体 `orderType==null`；配置 `F_UNW_Text_x` + map `{"客户订单":"客户订单"}` 且行值「客户订单」→ `orderType=="客户订单"`；行值「未知值」→ `orderType==null`。
- [ ] **Step 2: 确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 通过（含该测试类既有用例全绿）+ Commit** `feat(wms): 工单同步支持 order_type（E1 就位前 NULL 兜底）`（+Co-Authored-By）

### Task 7: 主数据同步 Job（默认关闭）

**Files:**
- Create: `.../job/WmsMasterDataSyncJob.java`、`.../job/WmsMasterDataSyncJobRegistrar.java`
- Test: `src/test/java/org/jeecg/modules/wms/job/WmsMasterDataSyncJobTest.java`

**Interfaces:**
- Consumes: Task 4 `syncStock()`、Task 5 `syncCategory()`、`WmsKingdeeProperties`（`masterData.enabled` AND 外层 `enabled`/`isConfigured()`）。
- Produces: Job 执行体——开关任一为假 → 直接返回不调用任何同步；开启 → 依次调 `syncStock()` + `syncCategory()`，异常捕获记 log 不抛（Quartz 惯例照 `WmsOrderSyncRetryJob`）。Registrar 仿 `WmsOrderSyncJobRegistrar`：启动时注册 cron `0 0 3 * * ?`（每天 03:00，错峰），注册后立即置为**暂停**状态（默认关闭，D6）。

- [ ] **Step 1: 写失败测试**：开关全默认 → 两个 sync 服务零交互；`masterData.enabled=true` 且外层 enabled → 两服务各调一次；执行体吞异常（mock 抛异常不外抛）。
- [ ] **Step 2: 确认失败 → Step 3 实现 → Step 4 通过**
- [ ] **Step 5: Commit** `feat(wms): 主数据同步 Job（默认暂停，手动触发为主）`（+Co-Authored-By）

### Task 8: 前端口径

**Files:**
- Modify: `jeecgboot-vue3/src/views/wms/warehouse/data.ts`（仓库类型下拉加「普通仓」选项，值=「普通仓」）
- Modify: `jeecgboot-vue3/src/views/wms/workOrder/`（列表加「工单类型」列：渲染 `orderType ?? '客户订单'` 兜底，spec D4）
- Modify: `jeecgboot-vue3/src/views/wms/warehouse/index.vue`（工具栏加「同步金蝶」「初始化档案」按钮，调 Task 4 两个端点；`WmsMaterialCategoryController` 对应页面同理加「同步金蝶」——执行者按页面既有按钮模式落）

- [ ] **Step 1: 改造 + lint**

```bash
cd /d/yongxin-WMS/JeecgBoot/jeecgboot-vue3
npx eslint src/views/wms/warehouse/data.ts src/views/wms/warehouse/index.vue src/views/wms/workOrder/
```
Expected: 0 error。
- [ ] **Step 2: `pnpm dev` 目检**（仓库类型下拉出现「普通仓」；工单类型列显示；按钮能发请求到 8081）
- [ ] **Step 3: Commit** `feat(wms): 前端普通仓类型/工单类型列/主数据同步入口`（+Co-Authored-By）

### Task 9: 运维文档 + 端到端验证

**Files:**
- Create: `JeecgBoot/docs/wms/主数据同步配置与使用.md`

- [ ] **Step 1: 写文档**——配置项表（§7 七个键：含义/默认/启用前提）、启用步骤（E1–E4 各自就位后的动作）、验证方法（含「白名单为空触发同步=0 行」与「临时白名单 X.2.4 → 拉到 1 行」两个脚本化验证）。
- [ ] **Step 2: 端到端验证**——重启后端（已完成 install）；POST `/wms/warehouse/sync-kingdee`（白名单空）→ 返回 fetched=0、DB 无新仓；临时把 `stock-whitelist` 配 `X.2.4` 重启再触发 → `wms_warehouse` 出现一行 `erp_stock_code='X.2.4'`、`erp_stock_name='涂层产成品仓'`、type=普通仓；还原白名单为空。
- [ ] **Step 3: Commit** `docs(wms): 主数据同步配置与使用文档`（+Co-Authored-By）

---

## 执行注意（每个任务通用）

- 改 wms 模块代码的任务（2–7）完成后：`install -DskipTests` + 重启后端，再进入下一任务。
- 全量回归（Task 6/7 之后各跑一次）：`bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms`，Expected: 全绿（CK-004 基线 743 个用例 + 新增）。
