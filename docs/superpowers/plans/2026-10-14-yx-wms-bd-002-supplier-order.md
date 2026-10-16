# YX-WMS-BD-002 供应商与生产订单管理 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在永鑫 WMS 中实现供应商维护、订单创建/审核(含驳回)/下达/查询，以及下达后同步 ERP/MES —— 同步失败自动重试 3 次、告警、落日志，并由定时任务自愈。

**Architecture:** 在既有 `jeecg-boot-module-wms` 内按 `WmsMaterial` 已确立的 entity/service/controller/Flyway/前端 三段式扩展。同步按 SPI 分层解耦：`WmsOrderSyncGateway` 只管「怎么和 ERP/MES 说话」，`IWmsOrderSyncService` 只管「失败怎么重试与告警」。订单同步用「同步中」态 + 条件 `UPDATE` 做乐观认领，互斥「用户下达」与「定时补偿」两个触发源，防止重复投递给 ERP/MES。

**Tech Stack:** JeecgBoot 3.9.5 / Spring Boot 4.1.0 / Java 17 / MyBatis-Plus / Shiro+JWT / Flyway 7.15.0 / Quartz(JDBC 存储 + clustered) / AutoPoi / Knife4j；前端 Vue 3 + Ant Design Vue + `useListPage`/`BasicTable`。

**Spec:** `docs/superpowers/specs/2026-10-14-yx-wms-bd-002-supplier-order-design.md`

## Global Constraints

以下为项目级硬约束，**每个任务都隐含包含本节**。

**工具链（已实测，勿凭经验替换命令）**

- **Maven 不在 PATH**。所有 Maven 命令必须写成 `bash /d/tools/m.sh <args>`（该脚本设好 `JAVA_HOME` 后转调 `D:\tools\apache-maven-3.9.9\bin\mvn.cmd`）。裸跑 `mvn` 会 `command not found`。
- 后端模块目录：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`。
- 后端编译：`bash /d/tools/m.sh -P dev clean package`（**必须带 `-P dev`**：根 pom 同时把 `dev` 与 `SpringCloud` 设为 `activeByDefault`，不带会拉入 cloud 模块并留下未解析的 `@profile.name@`）。
- 单模块编译：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am compile`
- 单模块测试：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='Wms*Test' -DfailIfNoTests=false`
  （**测试默认被跳过**，必须显式 `-DskipTests=false`；根 pom 已以 `<scope>test</scope>` 声明 `spring-boot-starter-test`，全部模块自动继承，**无需改 pom**。）
- 前端：`cd jeecgboot-vue3 && pnpm build`（会跑 `vue-tsc`，类型检查即门槛）。node v24.21.0 / pnpm 12.10.1 已就位。

**Java / 框架约定**

- Java 17，`jakarta.*` 命名空间（不是 `javax`）。
- 包根 `org.jeecg.modules.wms`；业务表统一 `wms_` 前缀。
- 实体：`extends JeecgEntity`（已含 `id/createBy/createTime/updateBy/updateTime`，**不要再声明这五个字段**），`@TableName("wms_xxx")`，`@Data @EqualsAndHashCode(callSuper=false) @Accessors(chain=true)`，逻辑删除字段 `@TableLogic private Integer delFlag;`。
- 服务：接口 `extends JeecgService<X>`；实现 `extends ServiceImpl<XMapper, X> implements I<X>Service`。
- 控制器：`extends JeecgController<X, I<X>Service>`，一律返回 `Result<T>`（`Result.OK(...)` / `Result.error(...)`），用 `@Tag`/`@Operation`/`@Schema`。
- Mapper：`extends BaseMapper<X>`，**无需 `@Mapper` 注解**；XML 放 `src/main/java/org/jeecg/modules/wms/mapper/xml/`（`mapper-locations: classpath*:org/jeecg/**/xml/*Mapper.xml` 已在全部 profile 配好，放对位置即加载）。
- 审计字段：用 `org.jeecg.modules.wms.util.WmsUtils` 的 `fillCreate(entity)` / `fillUpdate(entity)` / `currentUsername()`（本项目**未启用** MyBatis-Plus 自动填充，必须显式写）。
- 禁止魔法值：订单类型 / 订单状态 / 同步状态 / 重试参数 / 任务参数一律取自 `WmsConstants`。
- 日志用 SLF4J 占位符 `log.info("...{}", arg)`，禁止字符串拼接。
- 中文注释与中文 `@Schema` 描述；类头保留 `@Description/@Author: 永鑫WMS/@Date` 风格。

**业务取值（中文字面量，与 `wms_material_category.category_type` 既有做法一致）**

- 订单类型：`公司订单` / `采购订单` / `委外订单`
- 订单状态：`待审核` / `已审核` / `已下达` / `已驳回`
- 同步状态：`待同步` / `同步中` / `同步成功` / `同步失败`
- 重试：`SYNC_MAX_RETRY = 3` → **连同首次共最多 4 次调用**；退避 `1000 / 2000 / 4000` 毫秒
- `last_sync_error` 落库上限 500 字符（与列宽一致，超长必须截断）
- 「同步中」僵死回收阈值 300 秒
- 补偿任务：`sys_quartz_job` 主键 `1920000000000000101`，cron `0 3/5 * * * ? *`，开关 `wms.sync.job.enabled`（默认 `true`）

**前端约定**

- 路径别名 `/@/` → `src/`（带前导斜杠是本项目惯例）。
- Prettier：150 列宽、单引号、trailing comma (es5)、2 空格、`vueIndentScriptAndStyle: true`。
- 页面结构照 `src/views/wms/material/index.vue`：`useListPage` + `BasicTable` + `useModal` + `TableAction` + `useDesign`。
- 权限码用 `v-auth` 与 `TableAction` 的 `auth` 字段。

**验证纪律**

- **Flyway 的坑（必须记住）**：`FlywayConfig.migrate()` 是 `@PostConstruct`，dev 下 `spring.flyway.enabled=true`，所以**启动即自动迁移**；但它把 `flyway.migrate()` 包在 `catch (FlywayException e) { log.error("【数据库升级】flyway执行sql脚本失败", e); }` 里——**SQL 出错时应用照常启动，只留一行 ERROR**。因此 SQL 任务的验证**必须 grep 启动日志**，不能只看「应用起来了」。
- 启动命令：`cd jeecg-module-system/jeecg-system-start && bash /d/tools/m.sh -P dev spring-boot:run`（需 MySQL `127.0.0.1:3306/yongxin_wms` root/root 与 Redis `127.0.0.1:6379`，**已实测在线**）。
- 无 `mysql` CLI。行级取证走 HTTP（`/wms/order/list` 响应含 `syncStatus`/`retryCount`/`lastSyncError`）与启动日志；`curl` 可用。
- 任何一步失败，**原样贴出输出**再继续，不得只报告结论。

## Review Focus

以下是 spec 隐含、但**没有任务测试直接覆盖**、最容易在用户手里咬人的五类输入/失败模式。每条都已在「归属任务」里补了测试，实现时不要删。

1. **未配置 `wms.sync.erp.url` 时**（全新部署的默认状态）：必须优雅地返回「失败」并进入重试与告警链路，**不得抛 NPE、不得让下达接口 500、不得阻断应用启动**。→ 归属 Task 4。
2. **编码/订单号含首尾空格**（如 `" SUP001 "`）：唯一性判定若不做 trim，会允许 `SUP001` 与 ` SUP001 ` 并存，唯一索引与业务查重不一致。→ 归属 Task 2、Task 3。
3. **并发双击「下达」**或「下达」与「定时补偿」撞车：必须只有一次真正投递给 ERP/MES。→ 归属 Task 6、Task 8。
4. **`last_sync_error` 超长**（ERP 返回一整页 HTML 错误）：不截断会因列宽 500 直接写库报错，反而丢掉失败痕迹。→ 归属 Task 5。
5. **供应商已被停用/删除后**，历史订单列表仍须能渲染：`supplierName` 回填必须对「查不到的 supplierId」容错，**不得抛异常、不得让整个列表 500**。→ 归属 Task 3。

---

## 文件结构

**后端新增（`jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/`）**

| 文件 | 职责 |
| --- | --- |
| `constant/WmsConstants.java`（**改**） | 追加订单类型/状态/同步/重试/任务常量 |
| `entity/WmsSupplier.java` | 供应商表映射 |
| `entity/WmsOrder.java` | 订单表映射（含非持久化 `supplierName`） |
| `dto/WmsOrderAuditDTO.java` | 审核入参（id / pass / auditRemark） |
| `vo/WmsOrderSyncOutcomeVO.java` | 下达与重同步的响应体（synced / syncError / retryCount） |
| `mapper/WmsSupplierMapper.java` | 供应商数据访问 |
| `mapper/WmsOrderMapper.java` | 订单数据访问 + 认领/扫描自定义语句声明 |
| `mapper/xml/WmsOrderMapper.xml` | 认领 `UPDATE` 与扫描候选 `SELECT` |
| `service/IWmsSupplierService.java` + `impl/WmsSupplierServiceImpl.java` | 供应商业务（唯一性、trim、listAll） |
| `service/IWmsOrderService.java` + `impl/WmsOrderServiceImpl.java` | 订单业务（状态机守卫、审核、下达、重同步、三段式落库） |
| `service/IWmsOrderSyncService.java` + `impl/WmsOrderSyncServiceImpl.java` | 同步重试编排 + 告警（**不写库**） |
| `sync/WmsOrderSyncGateway.java` | SPI：一次同步尝试 |
| `sync/WmsOrderSyncResult.java` | 单次尝试结果（success / errorMessage） |
| `sync/WmsOrderSyncAttempt.java` | 一次同步流程的结果（success / errorMessage / retryCount）—— 顶层类，供调用方依赖接口即可使用 |
| `sync/HttpWmsOrderSyncGateway.java` | 可配置 HTTP 实现 |
| `job/WmsOrderSyncRetryJob.java` | 定时补偿：扫描并续跑 |
| `job/WmsOrderSyncJobRegistrar.java` | 启动时幂等自注册调度 |
| `controller/WmsSupplierController.java` | 供应商接口 |
| `controller/WmsOrderController.java` | 订单接口 |

**后端测试新增（`jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/`，目录当前不存在）**

| 文件 | 职责 |
| --- | --- |
| `service/impl/WmsSupplierServiceImplTest.java` | 唯一性 + trim |
| `service/impl/WmsOrderServiceImplTest.java` | 状态机守卫全覆盖 + 认领失败跳过 + 供应商名回填容错 |
| `service/impl/WmsOrderSyncServiceImplTest.java` | 重试 4 场景 + announce 开关 + 错误截断 |
| `sync/HttpWmsOrderSyncGatewayTest.java` | 空 URL / 非 2xx / 异常 |
| `job/WmsOrderSyncRetryJobTest.java` | 单条失败不中断整轮 |

**Flyway 新增（`jeecg-system-start/src/main/resources/flyway/sql/mysql/`）**

| 文件 | 内容 |
| --- | --- |
| `V3.9.5_3__wms_supplier_order.sql` | 两张表 DDL + 索引（Task 1） |
| `V3.9.5_4__wms_supplier_order_menu_job.sql` | 目录改名 + 新菜单 + 按钮权限 + admin 授权 + `sys_quartz_job` 种子（Task 9） |

> **对 spec 的一处实现细化（比 spec 更细，非偏离）**：spec §8 写「一个脚本 `V3.9.5_3` 装全部」。本计划拆成 `V3.9.5_3`（建表）与 `V3.9.5_4`（菜单+任务种子）两个脚本。理由：建表在 Task 1 就要验证，而菜单若提前落地，其 `component` 指向的前端页要到 Task 11 才存在，会在中途留下「点菜单 404」的状态；拆开后每个脚本都随它支撑的代码一起落地、各自可验证。内容与命名规则完全不变。

**前端新增（`jeecgboot-vue3/src/views/wms/`）**

| 文件 | 职责 |
| --- | --- |
| `supplier/index.vue` `supplier.api.ts` `supplier.data.ts` `components/SupplierModal.vue` | 供应商管理页 |
| `order/index.vue` `order.api.ts` `order.data.ts` `components/OrderModal.vue` | 订单管理页 |

**关于 TDD 的诚实说明**：Task 1、7、9、10、11 的产物是**声明式**的（SQL DDL、DTO/VO、控制器薄封装、Vue 页面），不存在有意义的「先写失败测试」循环——为它们编造断言 getter 的测试只会是噪音。这些任务的验证手段是**编译通过 + 启动日志 grep + 构建通过**，并在 Task 12 统一做端到端取证。真正值得 TDD 的是 Task 2–6、8 的业务逻辑，它们**逐步给出真实测试代码**。

---

### Task 1: 数据层声明（常量 + 实体 + DTO/VO + 建表 DDL）

**Files:**
- Modify: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java`
- Create: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsSupplier.java`
- Create: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsOrder.java`
- Create: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsOrderAuditDTO.java`
- Create: `jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/WmsOrderSyncOutcomeVO.java`
- Create: `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_3__wms_supplier_order.sql`

**Interfaces:**
- Consumes: 无（本任务是地基）
- Produces: 常量名 `ORDER_TYPE_COMPANY/PURCHASE/OUTSOURCE`、`ORDER_STATUS_PENDING_AUDIT/AUDITED/DISPATCHED/REJECTED`、`SYNC_STATUS_PENDING/RUNNING/SUCCESS/FAILED`、`SYNC_MAX_RETRY=3`、`SYNC_RETRY_BACKOFF_MS`、`SYNC_ERROR_MAX_LENGTH=500`、`SYNC_ALERT_TITLE`、`ALERT_FROM_USER`、`SYNC_JOB_ID/SYNC_JOB_CLASS/SYNC_JOB_CRON/SYNC_SCAN_BATCH/SYNC_RECLAIM_TIMEOUT_SECONDS/SYNC_JOB_ENABLED_KEY`；类 `WmsSupplier`、`WmsOrder`（字段 `orderNo/orderType/supplierId/supplierName/orderStatus/auditBy/auditTime/auditRemark/dispatchBy/dispatchTime/syncStatus/retryCount/lastSyncError/lastSyncTime/remark/delFlag`）、`WmsOrderAuditDTO(id,pass,auditRemark)`、`WmsOrderSyncOutcomeVO(synced,syncError,retryCount)`

- [ ] **Step 1: 追加常量到 `WmsConstants`**

打开 `constant/WmsConstants.java`，在既有 `IMPORT_ROW_OFFSET` 常量**之后**、类结束大括号**之前**插入：

```java
    // ---------------------------------------------------------------- 订单类型

    /** 订单类型-公司订单。 */
    public static final String ORDER_TYPE_COMPANY = "公司订单";

    /** 订单类型-采购订单。 */
    public static final String ORDER_TYPE_PURCHASE = "采购订单";

    /** 订单类型-委外订单。 */
    public static final String ORDER_TYPE_OUTSOURCE = "委外订单";

    // ---------------------------------------------------------------- 订单状态

    /** 订单状态-待审核。 */
    public static final String ORDER_STATUS_PENDING_AUDIT = "待审核";

    /** 订单状态-已审核。 */
    public static final String ORDER_STATUS_AUDITED = "已审核";

    /** 订单状态-已下达。 */
    public static final String ORDER_STATUS_DISPATCHED = "已下达";

    /** 订单状态-已驳回。 */
    public static final String ORDER_STATUS_REJECTED = "已驳回";

    // ---------------------------------------------------------------- 同步状态

    /** 同步状态-待同步。 */
    public static final String SYNC_STATUS_PENDING = "待同步";

    /** 同步状态-同步中（认领标记，用于互斥并发同步）。 */
    public static final String SYNC_STATUS_RUNNING = "同步中";

    /** 同步状态-同步成功。 */
    public static final String SYNC_STATUS_SUCCESS = "同步成功";

    /** 同步状态-同步失败。 */
    public static final String SYNC_STATUS_FAILED = "同步失败";

    // ---------------------------------------------------------------- 同步重试

    /** 最大重试次数；连同首次调用，最多尝试 {@code SYNC_MAX_RETRY + 1} 次。 */
    public static final int SYNC_MAX_RETRY = 3;

    /** 每次重试前的退避毫秒数；下标即第几次重试（下标 0 → 第 1 次重试之前）。 */
    public static final long[] SYNC_RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

    /** 同步失败原因落库长度上限，与 {@code wms_order.last_sync_error} 列宽一致。 */
    public static final int SYNC_ERROR_MAX_LENGTH = 500;

    /** 同步失败告警的消息标题。 */
    public static final String SYNC_ALERT_TITLE = "WMS订单同步失败告警";

    /** 告警消息的发送人标识。 */
    public static final String ALERT_FROM_USER = "system";

    // ---------------------------------------------------------------- 定时补偿任务

    /** 补偿任务在 {@code sys_quartz_job} 中的主键；必须与 Flyway 种子行、注册器三处一致。 */
    public static final String SYNC_JOB_ID = "1920000000000000101";

    /** 补偿任务类名；必须与 Flyway 种子行的 {@code job_class_name} 一致。 */
    public static final String SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsOrderSyncRetryJob";

    /** 补偿任务 cron：每 5 分钟，落在 :03/:08/…，错开整点避免与平台其它任务堆叠。 */
    public static final String SYNC_JOB_CRON = "0 3/5 * * * ? *";

    /** 补偿任务每轮最多处理的订单数，限制单轮耗时上限。 */
    public static final int SYNC_SCAN_BATCH = 50;

    /** 「同步中」僵死回收阈值（秒），须远大于单次同步最坏耗时（约 7 秒）。 */
    public static final int SYNC_RECLAIM_TIMEOUT_SECONDS = 300;

    /** 补偿任务启停开关的配置键；默认 true。 */
    public static final String SYNC_JOB_ENABLED_KEY = "wms.sync.job.enabled";
```

- [ ] **Step 2: 创建 `entity/WmsSupplier.java`**

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "供应商")
@TableName("wms_supplier")
public class WmsSupplier extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 供应商编码 */
    @Excel(name = "供应商编码", width = 20)
    @Schema(description = "供应商编码")
    private String supplierCode;

    /** 供应商名称 */
    @Excel(name = "供应商名称", width = 20)
    @Schema(description = "供应商名称")
    private String supplierName;

    /** 联系人 */
    @Excel(name = "联系人", width = 15)
    @Schema(description = "联系人")
    private String contact;

    /** 联系电话 */
    @Excel(name = "联系电话", width = 15)
    @Schema(description = "联系电话")
    private String phone;

    /** 供货范围 */
    @Excel(name = "供货范围", width = 20)
    @Schema(description = "供货范围")
    private String supplyScope;

    /** 状态：1启用 0停用 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /** 删除状态：0正常 1已删除 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
```

- [ ] **Step 3: 创建 `entity/WmsOrder.java`**

```java
package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "订单")
@TableName("wms_order")
public class WmsOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    @Excel(name = "订单号", width = 25)
    @Schema(description = "订单号")
    private String orderNo;

    /** 订单类型：公司订单/采购订单/委外订单 */
    @Excel(name = "订单类型", width = 15)
    @Schema(description = "订单类型：公司订单/采购订单/委外订单")
    private String orderType;

    /** 供应商ID（wms_supplier.id） */
    @Schema(description = "供应商ID")
    private String supplierId;

    /**
     * 供应商名称（非持久化）：列表展示用，查询后回填。
     */
    @TableField(exist = false)
    @Schema(description = "供应商名称")
    private String supplierName;

    /** 订单状态：待审核/已审核/已下达/已驳回 */
    @Excel(name = "订单状态", width = 12)
    @Schema(description = "订单状态：待审核/已审核/已下达/已驳回")
    private String orderStatus;

    /** 审核人 */
    @Schema(description = "审核人")
    private String auditBy;

    /** 审核时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "审核时间")
    private Date auditTime;

    /** 审核意见（驳回时必填） */
    @Excel(name = "审核意见", width = 20)
    @Schema(description = "审核意见（驳回时必填）")
    private String auditRemark;

    /** 下达人 */
    @Schema(description = "下达人")
    private String dispatchBy;

    /** 下达时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "下达时间")
    private Date dispatchTime;

    /** 同步状态：待同步/同步中/同步成功/同步失败 */
    @Excel(name = "同步状态", width = 12)
    @Schema(description = "同步状态：待同步/同步中/同步成功/同步失败")
    private String syncStatus;

    /** 最近一次同步尝试中实际发生的重试次数 */
    @Excel(name = "重试次数", width = 10)
    @Schema(description = "最近一次同步尝试中实际发生的重试次数")
    private Integer retryCount;

    /** 最后一次同步失败原因 */
    @Schema(description = "最后一次同步失败原因")
    private String lastSyncError;

    /** 最后一次同步尝试时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后一次同步尝试时间")
    private Date lastSyncTime;

    /** 备注 */
    @Excel(name = "备注", width = 20)
    @Schema(description = "备注")
    private String remark;

    /** 删除状态：0正常 1已删除 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
```

- [ ] **Step 4: 创建 `dto/WmsOrderAuditDTO.java`**

```java
package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 订单审核入参
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Schema(description = "订单审核入参")
public class WmsOrderAuditDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    @Schema(description = "订单ID")
    private String id;

    /** 是否通过：true 审核通过 false 驳回 */
    @Schema(description = "是否通过：true 审核通过 false 驳回")
    private Boolean pass;

    /** 审核意见（驳回时必填） */
    @Schema(description = "审核意见（驳回时必填）")
    private String auditRemark;
}
```

- [ ] **Step 5: 创建 `vo/WmsOrderSyncOutcomeVO.java`**

```java
package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * @Description: 订单同步结果（下达 / 重新同步的响应体）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单同步结果")
public class WmsOrderSyncOutcomeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否同步成功 */
    @Schema(description = "是否同步成功")
    private Boolean synced;

    /** 同步失败原因；成功时为空 */
    @Schema(description = "同步失败原因；成功时为空")
    private String syncError;

    /** 本次同步实际重试次数 */
    @Schema(description = "本次同步实际重试次数")
    private Integer retryCount;
}
```

- [ ] **Step 6: 创建建表脚本 `V3.9.5_3__wms_supplier_order.sql`**

在 `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/` 下新建。脚本**可重复执行**（沿用既有 `CREATE TABLE IF NOT EXISTS` 幂等风格，对齐 `V3.9.5_2__wms_material.sql`）：

```sql
-- ============================================================================
-- 永鑫WMS · 供应商与生产订单管理（蓝图流程 YX-WMS-BD-002）
-- 内容：wms_supplier / wms_order 建表
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS）
-- 注意：菜单与定时任务种子在 V3.9.5_4 中
-- ============================================================================

-- ---------------------------------------------------------------- 供应商
CREATE TABLE IF NOT EXISTS `wms_supplier` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `supplier_code` varchar(64)  NOT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) NOT NULL COMMENT '供应商名称',
  `contact`       varchar(50)  DEFAULT NULL COMMENT '联系人',
  `phone`         varchar(30)  DEFAULT NULL COMMENT '联系电话',
  `supply_scope`  varchar(200) DEFAULT NULL COMMENT '供货范围',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_supplier_code` (`supplier_code`) USING BTREE,
  INDEX `idx_wms_supplier_name` (`supplier_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='供应商';

-- ---------------------------------------------------------------- 订单
CREATE TABLE IF NOT EXISTS `wms_order` (
  `id`              varchar(32)  NOT NULL COMMENT '主键',
  `order_no`        varchar(64)  NOT NULL COMMENT '订单号',
  `order_type`      varchar(20)  NOT NULL COMMENT '订单类型：公司订单/采购订单/委外订单',
  `supplier_id`     varchar(32)  DEFAULT NULL COMMENT '供应商ID（wms_supplier.id）',
  `order_status`    varchar(20)  NOT NULL DEFAULT '待审核' COMMENT '订单状态：待审核/已审核/已下达/已驳回',
  `audit_by`        varchar(50)  DEFAULT NULL COMMENT '审核人',
  `audit_time`      datetime     DEFAULT NULL COMMENT '审核时间',
  `audit_remark`    varchar(500) DEFAULT NULL COMMENT '审核意见（驳回时必填）',
  `dispatch_by`     varchar(50)  DEFAULT NULL COMMENT '下达人',
  `dispatch_time`   datetime     DEFAULT NULL COMMENT '下达时间',
  `sync_status`     varchar(20)  NOT NULL DEFAULT '待同步' COMMENT '同步状态：待同步/同步中/同步成功/同步失败',
  `retry_count`     int(11)      NOT NULL DEFAULT 0 COMMENT '最近一次同步尝试中的重试次数',
  `last_sync_error` varchar(500) DEFAULT NULL COMMENT '最后一次同步失败原因',
  `last_sync_time`  datetime     DEFAULT NULL COMMENT '最后一次同步尝试时间',
  `remark`          varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag`        tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`       varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`     datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`     datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_order_no` (`order_no`) USING BTREE,
  INDEX `idx_wms_order_supplier` (`supplier_id`) USING BTREE,
  INDEX `idx_wms_order_type_status` (`order_type`, `order_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='订单';
```

- [ ] **Step 7: 编译验证**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am compile
```

Expected: `BUILD SUCCESS`。若报错，逐条修到通过再往下。

- [ ] **Step 8: 启动应用验证 Flyway 真的把表建出来了**

启动（保持前台，看到日志后 Ctrl+C 或另开终端）：

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev spring-boot:run > /tmp/boot1.log 2>&1
```

另开终端 grep 两件事：

```bash
grep -c "flyway执行sql脚本失败" /tmp/boot1.log   # 期望：0
grep -c "数据库版本自动升级" /tmp/boot1.log        # 期望：>=1
```

- 若第一条 > 0：**立即停手**，把日志里 `flyway执行sql脚本失败` 后面的异常栈原样贴出来。SQL 任务**必须**绿灯，不修不前进（这正是 Global Constraints 里说的静默吞错）。
- 第二条 >= 1 表示迁移执行过。

> 说明：`flyway_schema_history` 已在既有库中，`V3.9.5_3` 会作为下一个版本被应用。表建好后 Task 2 起的实体才有真实表可对。

- [ ] **Step 9: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsSupplier.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/entity/WmsOrder.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/dto/WmsOrderAuditDTO.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/vo/WmsOrderSyncOutcomeVO.java \
        jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_3__wms_supplier_order.sql
git commit -m "feat(wms): BD-002 数据层声明与建表脚本"
```

---

### Task 2: 供应商后端（唯一性 + trim + listAll）

**Files:**
- Create: `.../wms/mapper/WmsSupplierMapper.java`
- Create: `.../wms/service/IWmsSupplierService.java`
- Create: `.../wms/service/impl/WmsSupplierServiceImpl.java`
- Create: `.../wms/controller/WmsSupplierController.java`
- Test: `.../src/test/java/org/jeecg/modules/wms/service/impl/WmsSupplierServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WmsSupplier`、`WmsConstants.STATUS_ENABLE`
- Produces:
  - `IWmsSupplierService extends JeecgService<WmsSupplier>`，方法：`void addSupplier(WmsSupplier)`、`void editSupplier(WmsSupplier)`、`void disableSupplier(String id)`、`void removeSupplier(String id)`、`void removeBatch(List<String> ids)`、`boolean checkCodeExists(String code, String id)`、`List<WmsSupplier> listAllEnabled()`
  - HTTP：`GET /wms/supplier/list|queryById|checkCode|listAll`、`POST /wms/supplier/add|disable`、`PUT|POST /wms/supplier/edit`、`DELETE /wms/supplier/delete|deleteBatch`

- [ ] **Step 1: 写失败的测试（调 `trimCode` 与唯一性）**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsSupplierServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * @Description: 供应商业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsSupplierServiceImplTest {

    /** 编码首尾空格必须被去掉，否则 `SUP001` 与 ` SUP001 ` 会并存，与唯一索引语义不一致。 */
    @Test
    void addSupplier_shouldTrimCodeAndName() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("  SUP001  ")
                .setSupplierName("  永鑫五金  ");

        service.addSupplier(supplier);

        assertEquals("SUP001", supplier.getSupplierCode());
        assertEquals("永鑫五金", supplier.getSupplierName());
    }

    /** 编码为空白必须直接拒绝，不能落一条空编码。 */
    @Test
    void addSupplier_shouldRejectBlankCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("   ")
                .setSupplierName("永鑫五金");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addSupplier(supplier));
        assertEquals("供应商编码不能为空", ex.getMessage());
    }

    /** 编码重复必须拒绝。 */
    @Test
    void addSupplier_shouldRejectDuplicatedCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("SUP001")
                .setSupplierName("永鑫五金");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addSupplier(supplier));
        assertEquals("编码已存在，请重新输入", ex.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsSupplierServiceImplTest' -DfailIfNoTests=false
```

Expected: **编译失败** —— `WmsSupplierServiceImpl` 尚不存在。这是预期的红。

- [ ] **Step 3: 创建 Mapper**

`.../wms/mapper/WmsSupplierMapper.java`：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsSupplier;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsSupplierMapper extends BaseMapper<WmsSupplier> {
}
```

- [ ] **Step 4: 创建服务接口**

`.../wms/service/IWmsSupplierService.java`：

```java
package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsSupplier;

import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsSupplierService extends JeecgService<WmsSupplier> {

    /** 新增供应商（编码 trim + 查重）。 */
    void addSupplier(WmsSupplier supplier);

    /** 编辑供应商（编码 trim + 查重）。 */
    void editSupplier(WmsSupplier supplier);

    /** 停用供应商。 */
    void disableSupplier(String id);

    /** 删除供应商（逻辑删除）。 */
    void removeSupplier(String id);

    /** 批量删除供应商（逻辑删除）。 */
    void removeBatch(List<String> ids);

    /** 判断供应商编码是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkCodeExists(String code, String id);

    /** 取全部启用中的供应商，供订单页下拉选择。 */
    List<WmsSupplier> listAllEnabled();
}
```

- [ ] **Step 5: 创建服务实现**

`.../wms/service/impl/WmsSupplierServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsSupplierServiceImpl extends ServiceImpl<WmsSupplierMapper, WmsSupplier> implements IWmsSupplierService {

    @Override
    public void addSupplier(WmsSupplier supplier) {
        trimFields(supplier);
        if (oConvertUtils.isEmpty(supplier.getSupplierCode())) {
            throw new RuntimeException("供应商编码不能为空");
        }
        if (oConvertUtils.isEmpty(supplier.getSupplierName())) {
            throw new RuntimeException("供应商名称不能为空");
        }
        if (checkCodeExists(supplier.getSupplierCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(supplier.getStatus())) {
            supplier.setStatus(WmsConstants.STATUS_ENABLE);
        }
        supplier.setId(null);
        supplier.setDelFlag(0);
        WmsUtils.fillCreate(supplier);
        this.save(supplier);
    }

    @Override
    public void editSupplier(WmsSupplier supplier) {
        trimFields(supplier);
        if (oConvertUtils.isEmpty(supplier.getSupplierCode())) {
            throw new RuntimeException("供应商编码不能为空");
        }
        if (oConvertUtils.isEmpty(supplier.getSupplierName())) {
            throw new RuntimeException("供应商名称不能为空");
        }
        if (checkCodeExists(supplier.getSupplierCode(), supplier.getId())) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(supplier);
        this.updateById(supplier);
    }

    @Override
    public void disableSupplier(String id) {
        WmsSupplier supplier = new WmsSupplier();
        supplier.setId(id);
        supplier.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(supplier);
        this.updateById(supplier);
    }

    @Override
    public void removeSupplier(String id) {
        this.removeById(id);
    }

    @Override
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            this.removeByIds(ids);
        }
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsSupplier> queryWrapper = new QueryWrapper<>();
        // 代码逻辑说明: 按 trim 后的编码比对，与 addSupplier/editSupplier 的入库值保持一致，
        // 否则 " SUP001" 会绕过 "SUP001" 的查重，最终撞上唯一索引报 500
        queryWrapper.eq("supplier_code", code.trim());
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public List<WmsSupplier> listAllEnabled() {
        QueryWrapper<WmsSupplier> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", WmsConstants.STATUS_ENABLE);
        queryWrapper.orderByAsc("supplier_code");
        return this.list(queryWrapper);
    }

    /**
     * 去掉编码/名称/联系人等文本字段的首尾空格，避免「看起来一样、实际不同」的脏数据。
     */
    private void trimFields(WmsSupplier supplier) {
        supplier.setSupplierCode(trim(supplier.getSupplierCode()));
        supplier.setSupplierName(trim(supplier.getSupplierName()));
        supplier.setContact(trim(supplier.getContact()));
        supplier.setPhone(trim(supplier.getPhone()));
        supplier.setSupplyScope(trim(supplier.getSupplyScope()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsSupplierServiceImplTest' -DfailIfNoTests=false
```

Expected: `Tests run: 3, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

> 若 `spy(new WmsSupplierServiceImpl())` 因缺少 mockito-agent 报错（Java 17 + 较新 Mockito 需要 `-javaagent`），在 `jeecg-boot-module-wms/pom.xml` 加 `mockito-inline` 测试依赖，或改用 `Mockito.mock(WmsSupplierServiceImpl.class, CALLS_REAL_METHODS)`。**先按原样试，报错再改，并如实记录改了什么。**

- [ ] **Step 7: 创建控制器**

`.../wms/controller/WmsSupplierController.java`：

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
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Tag(name = "WMS-供应商")
@RestController
@RequestMapping("/wms/supplier")
@Slf4j
public class WmsSupplierController extends JeecgController<WmsSupplier, IWmsSupplierService> {

    /**
     * 分页列表查询（支持 supplierCode / supplierName / status）
     */
    @Operation(summary = "供应商-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsSupplier>> queryPageList(WmsSupplier wmsSupplier,
                                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                   HttpServletRequest req) {
        QueryWrapper<WmsSupplier> queryWrapper = QueryGenerator.initQueryWrapper(wmsSupplier, req.getParameterMap());
        queryWrapper.orderByDesc("create_time");
        Page<WmsSupplier> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    /**
     * 取全部启用中的供应商（订单页下拉用）。只返回 id/code/name 三列，减小报文。
     */
    @Operation(summary = "供应商-启用列表（下拉用）")
    @GetMapping(value = "/listAll")
    public Result<List<WmsSupplier>> listAll() {
        List<WmsSupplier> list = service.listAllEnabled();
        list.forEach(item -> {
            WmsSupplier slim = new WmsSupplier();
            item.setContact(null);
            item.setPhone(null);
            item.setSupplyScope(null);
        });
        return Result.OK(list);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "供应商-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsSupplier> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsSupplier entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.OK(entity);
    }

    /**
     * 供应商编码唯一性校验
     */
    @Operation(summary = "供应商-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("供应商编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "供应商-添加", operateType = 2)
    @Operation(summary = "供应商-添加")
    @PostMapping(value = "/add")
    public Result<WmsSupplier> add(@RequestBody WmsSupplier wmsSupplier) {
        try {
            service.addSupplier(wmsSupplier);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "供应商-编辑", operateType = 3)
    @Operation(summary = "供应商-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsSupplier> edit(@RequestBody WmsSupplier wmsSupplier) {
        try {
            service.editSupplier(wmsSupplier);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 停用
     */
    @AutoLog(value = "供应商-停用", operateType = 3)
    @Operation(summary = "供应商-停用")
    @PostMapping(value = "/disable")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableSupplier(id);
        return Result.OK("停用成功！");
    }

    /**
     * 通过id删除
     */
    @AutoLog(value = "供应商-删除", operateType = 4)
    @Operation(summary = "供应商-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        service.removeSupplier(id);
        return Result.OK("删除成功！");
    }

    /**
     * 批量删除
     */
    @AutoLog(value = "供应商-批量删除", operateType = 4)
    @Operation(summary = "供应商-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        service.removeBatch(Arrays.asList(ids.split(",")));
        return Result.OK("删除成功！");
    }
}
```

> **上一版此处有个 bug，已修正**：`listAll` 里原本写成给 `item` 赋 null 却构造了未使用的 `slim`，等于什么都没做。修正为直接对返回项清空非必要字段。

- [ ] **Step 8: 编译并跑全部 WMS 测试**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='Wms*Test' -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`，`WmsSupplierServiceImplTest` 3 个用例全绿。

- [ ] **Step 9: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsSupplierMapper.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsSupplierService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsSupplierServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsSupplierController.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsSupplierServiceImplTest.java
git commit -m "feat(wms): BD-002 供应商后端（唯一性校验 + trim + 下拉列表）"
```

---

### Task 3: 订单服务 — 状态机守卫 + 供应商名回填

**Files:**
- Create: `.../wms/mapper/WmsOrderMapper.java`
- Create: `.../wms/service/IWmsOrderService.java`
- Create: `.../wms/service/impl/WmsOrderServiceImpl.java`
- Test: `.../src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WmsOrder`/`WmsOrderAuditDTO`/`WmsConstants`；Task 2 的 `WmsSupplierMapper`
- Produces: `IWmsOrderService extends JeecgService<WmsOrder>`，方法：
  - `void addOrder(WmsOrder order)`
  - `void editOrder(WmsOrder order)`
  - `void auditOrder(WmsOrderAuditDTO dto)`
  - `void removeOrder(String id)` / `void removeBatch(List<String> ids)`
  - `boolean checkOrderNoExists(String orderNo, String id)`
  - `void fillSupplierName(List<WmsOrder> list)`
  - `static final String MSG_NOT_PENDING_AUDIT = "仅待审核状态的订单可审核"` 等常量文案（见下）
  - **本任务只做状态流转，不下达、不同步** —— 下达与同步在 Task 5/Task 6 接入。

- [ ] **Step 1: 写失败的测试（状态机守卫全覆盖 + 回填容错）**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * @Description: 订单状态机守卫单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderServiceImplTest {

    // ------------------------------------------------------------ 创建

    /** 新建订单状态必须落到「待审核」，且订单号首尾空格被去掉。 */
    @Test
    void addOrder_shouldDefaultToPendingAuditAndTrim() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("  PO001  ")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        service.addOrder(order);

        assertEquals("PO001", order.getOrderNo());
        assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, order.getOrderStatus());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertEquals(0, order.getRetryCount());
    }

    /** 订单号重复必须拒绝。 */
    @Test
    void addOrder_shouldRejectDuplicatedOrderNo() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(true).when(service).checkOrderNoExists(any(), any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addOrder(order));
        assertEquals("订单号已存在，请重新输入", ex.getMessage());
    }

    /** 订单类型必须在允许集合内。 */
    @Test
    void addOrder_shouldRejectUnknownOrderType() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(false).when(service).checkOrderNoExists(any(), any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType("不存在的类型");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addOrder(order));
        assertEquals("订单类型不合法", ex.getMessage());
    }

    // ------------------------------------------------------------ 审核

    /** 待审核 → 审核通过 → 已审核，并写入审核人与时间。 */
    @Test
    void auditOrder_pass_shouldMoveToAudited() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        doReturn(order).when(service).getById("1");
        doReturn(true).when(service).updateById(any());

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(true);
        service.auditOrder(dto);

        assertEquals(WmsConstants.ORDER_STATUS_AUDITED, order.getOrderStatus());
        assertEquals("1", order.getId());
    }

    /** 待审核 → 驳回 → 已驳回，且驳回原因必填。 */
    @Test
    void auditOrder_reject_shouldMoveToRejected() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        doReturn(order).when(service).getById("1");
        doReturn(true).when(service).updateById(any());

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(false);
        dto.setAuditRemark("价格不符合预算");
        service.auditOrder(dto);

        assertEquals(WmsConstants.ORDER_STATUS_REJECTED, order.getOrderStatus());
        assertEquals("价格不符合预算", order.getAuditRemark());
    }

    /** 驳回但不填原因 → 必须拒绝。 */
    @Test
    void auditOrder_rejectWithoutRemark_shouldFail() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        doReturn(order).when(service).getById("1");

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(false);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto));
        assertEquals("驳回时必须填写驳回原因", ex.getMessage());
    }

    // ------------------------------------------------------------ 守卫：非法前置状态

    /** 只有「待审核」可审核；已审核/已下达/已驳回都必须被拒。 */
    @Test
    void auditOrder_shouldRejectNonPendingStates() {
        for (String status : new String[]{
                WmsConstants.ORDER_STATUS_AUDITED,
                WmsConstants.ORDER_STATUS_DISPATCHED,
                WmsConstants.ORDER_STATUS_REJECTED}) {
            WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
            WmsOrder order = new WmsOrder().setId("1").setOrderStatus(status);
            doReturn(order).when(service).getById("1");

            WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
            dto.setId("1");
            dto.setPass(true);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto),
                    "状态 " + status + " 不应允许审核");
            assertEquals("仅待审核状态的订单可审核", ex.getMessage());
        }
    }

    /** 只有「待审核/已驳回」可编辑；已审核/已下达必须被拒。 */
    @Test
    void editOrder_shouldRejectNonEditableStates() {
        for (String status : new String[]{
                WmsConstants.ORDER_STATUS_AUDITED,
                WmsConstants.ORDER_STATUS_DISPATCHED}) {
            WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
            WmsOrder existing = new WmsOrder().setId("1").setOrderStatus(status);
            doReturn(existing).when(service).getById("1");

            WmsOrder input = new WmsOrder()
                    .setId("1")
                    .setOrderNo("PO001")
                    .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input),
                    "状态 " + status + " 不应允许编辑");
            assertEquals("仅待审核或已驳回状态的订单可编辑", ex.getMessage());
        }
    }

    /** 编辑「已驳回」订单后，状态回到「待审核」且清空上一轮审核痕迹。 */
    @Test
    void editOrder_rejectedShouldReturnToPendingAudit() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder()
                .setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_REJECTED);
        doReturn(existing).when(service).getById("1");
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsOrder input = new WmsOrder()
                .setId("1")
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        service.editOrder(input);

        assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, input.getOrderStatus());
        assertEquals(null, input.getAuditRemark());
    }

    /** 只有「待审核/已驳回」可删除；已下达必须被拒。 */
    @Test
    void removeOrder_shouldRejectDispatched() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeOrder("1"));
        assertEquals("仅待审核或已驳回状态的订单可删除", ex.getMessage());
    }

    // ------------------------------------------------------------ Review Focus #5：供应商回填容错

    /** 构造被测对象并注入 mock 的供应商 Mapper——回填查询走它，裸 new 会因字段为空 NPE。 */
    private WmsOrderServiceImpl serviceWithSupplierMapper(List<WmsSupplier> suppliers) {
        WmsOrderServiceImpl service = new WmsOrderServiceImpl();
        WmsSupplierMapper mapper = mock(WmsSupplierMapper.class);
        when(mapper.selectBatchIds(any())).thenReturn(suppliers);
        service.setSupplierMapperForTest(mapper);
        return service;
    }

    /** 供应商查不到（已删除/已停用）时，回填必须静默跳过，不能抛异常把整个列表打挂。 */
    @Test
    void fillSupplierName_shouldTolerateMissingSupplier() {
        // selectBatchIds 自带 del_flag=0 过滤，已删除的供应商查不到 → 返回空集合
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());
        WmsOrder order = new WmsOrder().setId("1").setSupplierId("不存在的供应商ID");

        assertDoesNotThrow(() -> service.fillSupplierName(Collections.singletonList(order)));
        assertEquals(null, order.getSupplierName());
    }

    /** 空列表与全空 supplierId 都必须安全返回（此时根本不该查库）。 */
    @Test
    void fillSupplierName_shouldHandleEmptyInput() {
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());

        assertDoesNotThrow(() -> service.fillSupplierName(null));
        assertDoesNotThrow(() -> service.fillSupplierName(Collections.emptyList()));
        assertDoesNotThrow(() -> service.fillSupplierName(
                Collections.singletonList(new WmsOrder().setId("1"))));
    }

    /** 供应商存在时必须正确回填名称（防止"只测了容错、没测正常路径"）。 */
    @Test
    void fillSupplierName_shouldFillExistingSupplier() {
        WmsSupplier supplier = new WmsSupplier().setId("S1").setSupplierName("永鑫五金");
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.singletonList(supplier));
        WmsOrder order = new WmsOrder().setId("1").setSupplierId("S1");

        service.fillSupplierName(Collections.singletonList(order));

        assertEquals("永鑫五金", order.getSupplierName());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderServiceImplTest' -DfailIfNoTests=false
```

Expected: 编译失败（`WmsOrderServiceImpl` 不存在）。

- [ ] **Step 3: 创建 Mapper（本任务只声明继承，认领 SQL 在 Task 6 加）**

`.../wms/mapper/WmsOrderMapper.java`：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsOrder;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsOrderMapper extends BaseMapper<WmsOrder> {
}
```

- [ ] **Step 4: 创建服务接口**

`.../wms/service/IWmsOrderService.java`：

```java
package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;

import java.util.List;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsOrderService extends JeecgService<WmsOrder> {

    /** 新增订单（订单号 trim + 查重，状态置为「待审核」）。 */
    void addOrder(WmsOrder order);

    /** 编辑订单（仅「待审核」或「已驳回」可编辑）。 */
    void editOrder(WmsOrder order);

    /** 审核订单：通过 → 已审核；驳回 → 已驳回（原因必填）。 */
    void auditOrder(WmsOrderAuditDTO dto);

    /** 删除订单（逻辑删除，仅「待审核」或「已驳回」可删）。 */
    void removeOrder(String id);

    /** 批量删除订单（逻辑删除）。 */
    void removeBatch(List<String> ids);

    /** 判断订单号是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkOrderNoExists(String orderNo, String id);

    /** 回填供应商名称（非持久化字段，供列表使用）。 */
    void fillSupplierName(List<WmsOrder> list);
}
```

- [ ] **Step 5: 创建服务实现（状态机守卫版）**

`.../wms/service/impl/WmsOrderServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsOrderServiceImpl extends ServiceImpl<WmsOrderMapper, WmsOrder> implements IWmsOrderService {

    /** 允许的订单类型。 */
    private static final Set<String> ALLOWED_ORDER_TYPES = new HashSet<>(Arrays.asList(
            WmsConstants.ORDER_TYPE_COMPANY,
            WmsConstants.ORDER_TYPE_PURCHASE,
            WmsConstants.ORDER_TYPE_OUTSOURCE));

    @Autowired
    private WmsSupplierMapper wmsSupplierMapper;

    /** 仅供单测注入供应商 Mapper（裸 new 时该字段为空，回填会 NPE）。 */
    void setSupplierMapperForTest(WmsSupplierMapper wmsSupplierMapper) {
        this.wmsSupplierMapper = wmsSupplierMapper;
    }

    // ------------------------------------------------------------ 创建

    @Override
    public void addOrder(WmsOrder order) {
        order.setOrderNo(trim(order.getOrderNo()));
        if (oConvertUtils.isEmpty(order.getOrderNo())) {
            throw new RuntimeException("订单号不能为空");
        }
        if (!ALLOWED_ORDER_TYPES.contains(order.getOrderType())) {
            throw new RuntimeException("订单类型不合法");
        }
        if (checkOrderNoExists(order.getOrderNo(), null)) {
            throw new RuntimeException("订单号已存在，请重新输入");
        }
        order.setId(null);
        order.setDelFlag(0);
        order.setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setSyncStatus(WmsConstants.SYNC_STATUS_PENDING);
        order.setRetryCount(0);
        order.setAuditBy(null);
        order.setAuditTime(null);
        order.setAuditRemark(null);
        order.setDispatchBy(null);
        order.setDispatchTime(null);
        order.setLastSyncError(null);
        order.setLastSyncTime(null);
        WmsUtils.fillCreate(order);
        this.save(order);
    }

    // ------------------------------------------------------------ 编辑

    @Override
    public void editOrder(WmsOrder order) {
        WmsOrder existing = this.getById(order.getId());
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())
                && !WmsConstants.ORDER_STATUS_REJECTED.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核或已驳回状态的订单可编辑");
        }
        order.setOrderNo(trim(order.getOrderNo()));
        if (oConvertUtils.isEmpty(order.getOrderNo())) {
            throw new RuntimeException("订单号不能为空");
        }
        if (!ALLOWED_ORDER_TYPES.contains(order.getOrderType())) {
            throw new RuntimeException("订单类型不合法");
        }
        if (checkOrderNoExists(order.getOrderNo(), order.getId())) {
            throw new RuntimeException("订单号已存在，请重新输入");
        }
        // 代码逻辑说明: 已驳回的订单被重新编辑即视为重新提交，回到「待审核」，
        // 并清空上一轮审核痕迹；旧意见的历史由操作日志（@AutoLog）承担
        order.setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setAuditBy(null);
        order.setAuditTime(null);
        order.setAuditRemark(null);
        WmsUtils.fillUpdate(order);
        this.updateById(order);
    }

    // ------------------------------------------------------------ 审核

    @Override
    public void auditOrder(WmsOrderAuditDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getId())) {
            throw new RuntimeException("订单ID不能为空");
        }
        if (dto.getPass() == null) {
            throw new RuntimeException("请选择审核结果");
        }
        WmsOrder existing = this.getById(dto.getId());
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核状态的订单可审核");
        }
        if (!dto.getPass() && oConvertUtils.isEmpty(dto.getAuditRemark())) {
            throw new RuntimeException("驳回时必须填写驳回原因");
        }
        WmsOrder update = new WmsOrder();
        update.setId(dto.getId());
        update.setOrderStatus(Boolean.TRUE.equals(dto.getPass())
                ? WmsConstants.ORDER_STATUS_AUDITED
                : WmsConstants.ORDER_STATUS_REJECTED);
        update.setAuditBy(WmsUtils.currentUsername());
        update.setAuditTime(new Date());
        update.setAuditRemark(trim(dto.getAuditRemark()));
        WmsUtils.fillUpdate(update);
        this.updateById(update);
        // 让调用方持有的实体也反映最终状态（单测据此断言）
        existing.setOrderStatus(update.getOrderStatus());
        existing.setAuditRemark(update.getAuditRemark());
    }

    // ------------------------------------------------------------ 删除

    @Override
    public void removeOrder(String id) {
        WmsOrder existing = this.getById(id);
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())
                && !WmsConstants.ORDER_STATUS_REJECTED.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核或已驳回状态的订单可删除");
        }
        this.removeById(id);
    }

    @Override
    public void removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            removeOrder(id);
        }
    }

    // ------------------------------------------------------------ 查重与回填

    @Override
    public boolean checkOrderNoExists(String orderNo, String id) {
        if (oConvertUtils.isEmpty(orderNo)) {
            return false;
        }
        QueryWrapper<WmsOrder> queryWrapper = new QueryWrapper<>();
        // 代码逻辑说明: 与入库值一样按 trim 后比对，防止 " PO001" 绕过 "PO001" 的查重
        queryWrapper.eq("order_no", orderNo.trim());
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void fillSupplierName(List<WmsOrder> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> supplierIds = new HashSet<>();
        for (WmsOrder order : list) {
            if (oConvertUtils.isNotEmpty(order.getSupplierId())) {
                supplierIds.add(order.getSupplierId());
            }
        }
        if (supplierIds.isEmpty()) {
            return;
        }
        // 代码逻辑说明: selectBatchIds 会自动带上 del_flag=0，已删除的供应商查不到；
        // 此处刻意不报错，回填不到就留空，避免历史订单列表整体 500
        List<WmsSupplier> suppliers = wmsSupplierMapper.selectBatchIds(supplierIds);
        Map<String, WmsSupplier> supplierMap = new HashMap<>(suppliers.size());
        for (WmsSupplier supplier : suppliers) {
            supplierMap.put(supplier.getId(), supplier);
        }
        for (WmsOrder order : list) {
            WmsSupplier supplier = order.getSupplierId() == null ? null : supplierMap.get(order.getSupplierId());
            if (supplier != null) {
                order.setSupplierName(supplier.getSupplierName());
            }
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderServiceImplTest' -DfailIfNoTests=false
```

Expected: 全部用例通过。**特别确认** `fillSupplierName_shouldTolerateMissingSupplier` 与 `addOrder_shouldTrimOrderNo` 两条是绿的——它们对应 Review Focus #5 与 #2。

- [ ] **Step 7: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/WmsOrderMapper.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsOrderService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImplTest.java
git commit -m "feat(wms): BD-002 订单状态机守卫与供应商名回填"
```

---

### Task 4: 同步 SPI — Gateway 与 HTTP 实现

**Files:**
- Create: `.../wms/sync/WmsOrderSyncResult.java`
- Create: `.../wms/sync/WmsOrderSyncGateway.java`
- Create: `.../wms/sync/HttpWmsOrderSyncGateway.java`
- Test: `.../src/test/java/org/jeecg/modules/wms/sync/HttpWmsOrderSyncGatewayTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WmsOrder`
- Produces:
  - `WmsOrderSyncResult`：`static WmsOrderSyncResult ok()`、`static WmsOrderSyncResult fail(String errorMessage)`、`boolean isSuccess()`、`String getErrorMessage()`
  - `WmsOrderSyncGateway`：`WmsOrderSyncResult sync(WmsOrder order)`
  - `HttpWmsOrderSyncGateway implements WmsOrderSyncGateway`，构造器 `HttpWmsOrderSyncGateway(String syncUrl)`（便于单测注入空/假 URL）

- [ ] **Step 1: 写失败的测试（覆盖 Review Focus #1）**

创建 `src/test/java/org/jeecg/modules/wms/sync/HttpWmsOrderSyncGatewayTest.java`：

```java
package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: HTTP 同步网关单测
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class HttpWmsOrderSyncGatewayTest {

    /**
     * Review Focus #1：全新部署默认没配 wms.sync.erp.url。
     * 此时必须"优雅地报告失败"——不能抛异常、不能 NPE。
     */
    @Test
    void sync_shouldFailGracefullyWhenUrlNotConfigured() {
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway("");

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置"),
                "失败原因应说明未配置，实际为：" + result.getErrorMessage());
    }

    /** URL 为 null 同样要优雅失败。 */
    @Test
    void sync_shouldFailGracefullyWhenUrlIsNull() {
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway(null);

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
    }

    /** 地址不可达时必须捕获异常并转成失败结果，而不是把异常抛给调用方。 */
    @Test
    void sync_shouldConvertConnectionFailureIntoResult() {
        // 保留端口 1 上不会有服务监听，连接必然失败
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway("http://127.0.0.1:1/erp/order");

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    /** 结果对象的成功/失败工厂语义。 */
    @Test
    void resultFactories_shouldBehave() {
        assertTrue(WmsOrderSyncResult.ok().isSuccess());
        assertFalse(WmsOrderSyncResult.fail("boom").isSuccess());
        assertTrue(WmsOrderSyncResult.fail("boom").getErrorMessage().contains("boom"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='HttpWmsOrderSyncGatewayTest' -DfailIfNoTests=false
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 创建 `sync/WmsOrderSyncResult.java`**

```java
package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 一次同步尝试的结果（SPI 的返回类型）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Schema(description = "一次同步尝试的结果")
public class WmsOrderSyncResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    @Schema(description = "是否成功")
    private boolean success;

    /** 失败原因；成功时为 null */
    @Schema(description = "失败原因；成功时为 null")
    private String errorMessage;

    public static WmsOrderSyncResult ok() {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(true);
        return result;
    }

    public static WmsOrderSyncResult fail(String errorMessage) {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
```

- [ ] **Step 4: 创建 `sync/WmsOrderSyncGateway.java`**

```java
package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOrder;

/**
 * @Description: 订单同步至 ERP/MES 的网关（SPI）。
 *
 * <p>本接口只负责「怎么和 ERP/MES 说一次话」，<b>不关心</b>重试、退避、告警与落库；
 * 那些由 {@code IWmsOrderSyncService} 编排。接入真实 ERP/MES 时只需替换本接口的实现类。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsOrderSyncGateway {

    /**
     * 向 ERP/MES 提交一次订单同步。
     *
     * <p><b>约定：本方法不得抛异常</b>。任何网络/协议/业务失败都必须转成
     * {@link WmsOrderSyncResult#fail(String)} 返回，让上层的重试编排统一处理。
     *
     * @param order 待同步订单
     * @return 本次尝试的结果
     */
    WmsOrderSyncResult sync(WmsOrder order);
}
```

- [ ] **Step 5: 创建 `sync/HttpWmsOrderSyncGateway.java`**

```java
package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Description: 基于可配置 HTTP 地址的订单同步实现。
 *
 * <p>读取配置 {@code wms.sync.erp.url}；<b>未配置时直接返回失败</b>——于是「重试 + 告警」
 * 这条链路在未接入真实 ERP/MES 时也能开箱跑通、可用于验收。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
public class HttpWmsOrderSyncGateway implements WmsOrderSyncGateway {

    /** 连接与读取超时（毫秒）；必须小于单次同步的预期耗时，避免拖垮下达接口。 */
    private static final int TIMEOUT_MS = 3000;

    private final String syncUrl;
    private final RestTemplate restTemplate;

    public HttpWmsOrderSyncGateway(@Value("${wms.sync.erp.url:}") String syncUrl) {
        this.syncUrl = syncUrl == null ? "" : syncUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsOrder order) {
        if (syncUrl.isEmpty()) {
            return WmsOrderSyncResult.fail("未配置 ERP/MES 同步地址（wms.sync.erp.url）");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(buildPayload(order), headers);

            ResponseEntity<String> response = restTemplate.postForEntity(syncUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return WmsOrderSyncResult.ok();
            }
            return WmsOrderSyncResult.fail("ERP/MES 返回非成功状态：" + response.getStatusCode()
                    + "，响应：" + abbreviate(response.getBody()));
        } catch (Exception e) {
            // 约定：不抛异常，全部转成失败结果交给重试编排
            log.warn("订单[{}]同步 ERP/MES 调用异常：{}", order.getOrderNo(), e.getMessage());
            return WmsOrderSyncResult.fail("调用 ERP/MES 异常：" + e.getMessage());
        }
    }

    /**
     * 组装提交报文。字段与蓝图 BD-002 的同步要素对齐。
     */
    private Map<String, Object> buildPayload(WmsOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType());
        payload.put("supplierId", order.getSupplierId());
        payload.put("supplierName", order.getSupplierName());
        payload.put("orderStatus", order.getOrderStatus());
        payload.put("auditTime", order.getAuditTime());
        payload.put("dispatchTime", order.getDispatchTime());
        return payload;
    }

    /**
     * 截断过长的响应体，避免把一整页 HTML 灌进日志与数据库字段。
     */
    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截断)";
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='HttpWmsOrderSyncGatewayTest' -DfailIfNoTests=false
```

Expected: 4 个用例全绿。`sync_shouldFailGracefullyWhenUrlNotConfigured` 绿 = Review Focus #1 已被钉住。

- [ ] **Step 7: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/sync/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/sync/
git commit -m "feat(wms): BD-002 订单同步 SPI 与可配置 HTTP 实现"
```

---

### Task 5: 重试编排与告警（不写库）

**Files:**
- Create: `.../wms/sync/WmsOrderSyncAttempt.java`
- Create: `.../wms/service/IWmsOrderSyncService.java`
- Create: `.../wms/service/impl/WmsOrderSyncServiceImpl.java`
- Test: `.../src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderSyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 常量、Task 4 的 `WmsOrderSyncGateway`/`WmsOrderSyncResult`
- Produces:
  - `WmsOrderSyncAttempt`（**顶层类**，非嵌套）：`isSuccess()` / `getErrorMessage()` / `getRetryCount()`，全参构造器 `(boolean success, String errorMessage, int retryCount)`
  - `IWmsOrderSyncService`：`WmsOrderSyncAttempt syncOrder(WmsOrder order, boolean announce)`

> **设计修正（实现时请照此，勿照搬 spec 的简化描述）**：spec §6.2 只说「syncOrder 返回失败结果」。但调用方需要知道**实际重试了几次**才能正确写 `retry_count`。故引入结果载体 `WmsOrderSyncAttempt`。
>
> 它被刻意做成 `sync/` 下的**顶层类**而不是实现类的嵌套类：否则 Task 6（订单服务）与 Task 8（定时任务）都得依赖具体实现类 `WmsOrderSyncServiceImpl` 才能引用这个类型，破坏「依赖接口」的边界。`WmsOrderSyncResult` 保持「单次尝试」的语义不变。

- [ ] **Step 1: 写失败的测试（覆盖 Review Focus #4 + announce 开关）**

创建 `src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderSyncServiceImplTest.java`：

```java
package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 同步重试编排单测（退避时间被置零以免拖慢测试）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderSyncServiceImplTest {

    private WmsOrder order() {
        return new WmsOrder().setId("1").setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
    }

    /** 构造被测对象，并把退避时间全部置零。 */
    private WmsOrderSyncServiceImpl service(WmsOrderSyncGateway gateway, ISysBaseAPI sysBaseAPI) {
        WmsOrderSyncServiceImpl service = new WmsOrderSyncServiceImpl(gateway, sysBaseAPI);
        service.setBackoffMsForTest(new long[]{0L, 0L, 0L});
        return service;
    }

    /** ① 首次即成功：retryCount=0，只调 1 次。 */
    @Test
    void syncOrder_firstAttemptSucceeds() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.ok());
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertTrue(attempt.isSuccess());
        assertEquals(0, attempt.getRetryCount());
        verify(gateway, times(1)).sync(any());
    }

    /** ② 第 2 次才成功：retryCount=1，共调 2 次。 */
    @Test
    void syncOrder_secondAttemptSucceeds() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any()))
                .thenReturn(WmsOrderSyncResult.fail("第一次失败"))
                .thenReturn(WmsOrderSyncResult.ok());
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertTrue(attempt.isSuccess());
        assertEquals(1, attempt.getRetryCount());
        verify(gateway, times(2)).sync(any());
    }

    /** ③ 4 次全失败：retryCount=3，gateway 恰被调 4 次，errorMessage 为最后一次的原因。 */
    @Test
    void syncOrder_allAttemptsFail() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any()))
                .thenReturn(WmsOrderSyncResult.fail("第1次"))
                .thenReturn(WmsOrderSyncResult.fail("第2次"))
                .thenReturn(WmsOrderSyncResult.fail("第3次"))
                .thenReturn(WmsOrderSyncResult.fail("第4次"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertFalse(attempt.isSuccess());
        assertEquals(WmsConstants.SYNC_MAX_RETRY, attempt.getRetryCount());
        assertEquals("第4次", attempt.getErrorMessage());
        verify(gateway, times(WmsConstants.SYNC_MAX_RETRY + 1)).sync(any());
    }

    /** ④ announce=true 时失败必须发站内消息。 */
    @Test
    void syncOrder_shouldAnnounceWhenEnabled() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        service(gateway, sysBaseAPI).syncOrder(order(), true);

        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any());
    }

    /** ⑤ announce=false（定时补偿路径）时不得发消息，防止每 5 分钟轰炸一次。 */
    @Test
    void syncOrder_shouldNotAnnounceWhenDisabled() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        service(gateway, sysBaseAPI).syncOrder(order(), false);

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }

    /**
     * Review Focus #4：ERP 返回一整页 HTML 时，错误原因必须被截断到列宽 500 以内，
     * 否则写库会因超长报错，反而丢掉失败痕迹。
     */
    @Test
    void syncOrder_shouldTruncateOverlongErrorMessage() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append("x");
        }
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail(huge.toString()));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), false);

        assertTrue(attempt.getErrorMessage().length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH,
                "错误原因长度 " + attempt.getErrorMessage().length() + " 超出了列宽上限");
    }

    /** 告警本身抛异常时，不能反过来把同步流程带崩。 */
    @Test
    void syncOrder_shouldSurviveAnnounceFailure() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        org.mockito.Mockito.doThrow(new RuntimeException("MQ 挂了"))
                .when(sysBaseAPI).sendSysAnnouncement(any());

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertFalse(attempt.isSuccess());
        assertEquals("挂了", attempt.getErrorMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderSyncServiceImplTest' -DfailIfNoTests=false
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 创建结果载体与服务接口**

先建顶层结果载体 `.../wms/sync/WmsOrderSyncAttempt.java`：

```java
package org.jeecg.modules.wms.sync;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 一次同步流程的结果（含实际重试次数）。
 *
 * <p>刻意放在 sync 包下作为顶层类，而不是实现类的嵌套类——否则调用方（订单服务、定时任务）
 * 必须依赖具体实现类才能引用本类型，破坏「依赖接口」的边界。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@AllArgsConstructor
public class WmsOrderSyncAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否最终成功 */
    private final boolean success;

    /** 失败原因；成功时为 null */
    private final String errorMessage;

    /** 本次流程实际重试次数（0 表示首次即成功） */
    private final int retryCount;
}
```

再建服务接口 `.../wms/service/IWmsOrderSyncService.java`：

```java
package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

/**
 * @Description: 订单同步重试编排。
 *
 * <p>职责边界：<b>只管</b>重试次数、退避、告警与日志；<b>不管</b> HTTP 细节（交给
 * {@code WmsOrderSyncGateway}），<b>也不写库</b>（交给调用方，保证「谁拥有数据谁写」）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsOrderSyncService {

    /**
     * 同步订单至 ERP/MES，失败自动重试至多 {@code WmsConstants.SYNC_MAX_RETRY} 次。
     *
     * @param order    待同步订单
     * @param announce 失败时是否投递站内消息告警。用户触发路径传 true；
     *                 定时补偿路径传 false（防每轮轰炸）
     * @return 最后一次尝试的结果与本次实际重试次数
     */
    WmsOrderSyncAttempt syncOrder(WmsOrder order, boolean announce);
}
```

- [ ] **Step 4: 创建服务实现**

`.../wms/service/impl/WmsOrderSyncServiceImpl.java`：

```java
package org.jeecg.modules.wms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.stereotype.Service;

/**
 * @Description: 订单同步重试编排。不写库。
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsOrderSyncServiceImpl implements IWmsOrderSyncService {

    private final WmsOrderSyncGateway gateway;
    private final ISysBaseAPI sysBaseAPI;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    public WmsOrderSyncServiceImpl(WmsOrderSyncGateway gateway, ISysBaseAPI sysBaseAPI) {
        this.gateway = gateway;
        this.sysBaseAPI = sysBaseAPI;
    }

    /** 仅供单测把退避时间置零，避免测试白等 7 秒。 */
    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsOrderSyncAttempt syncOrder(WmsOrder order, boolean announce) {
        WmsOrderSyncResult last = null;
        int attemptNo = 0;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            attemptNo = i;
            last = gateway.sync(order);
            if (last.isSuccess()) {
                log.info("订单[{}]同步 ERP/MES 成功，重试次数={}", order.getOrderNo(), i);
                return new WmsOrderSyncAttempt(true, null, i);
            }
            if (i < WmsConstants.SYNC_MAX_RETRY) {
                sleepBeforeRetry(i, order);
            }
        }
        String error = truncate(last == null ? "未知原因" : last.getErrorMessage());
        log.error("订单[{}]同步 ERP/MES 失败，已重试{}次，订单类型={}，供应商ID={}，失败原因：{}",
                order.getOrderNo(), WmsConstants.SYNC_MAX_RETRY, order.getOrderType(),
                order.getSupplierId(), error);
        if (announce) {
            alert(order, error);
        }
        return new WmsOrderSyncAttempt(false, error, attemptNo);
    }

    /**
     * 退避等待。下标 i 表示「第 i 次重试之前」，故取 backoffMs[i]。
     */
    private void sleepBeforeRetry(int i, WmsOrder order) {
        long wait = i < backoffMs.length ? backoffMs[i] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("订单[{}]同步退避等待被中断", order.getOrderNo());
        }
    }

    /**
     * 站内消息告警。用 try/catch 包住——告警设施自己出问题，不能掩盖同步失败这件事。
     */
    private void alert(WmsOrder order, String error) {
        try {
            String content = String.format("订单号：%s；订单类型：%s；重试次数：%d；失败原因：%s",
                    order.getOrderNo(), order.getOrderType(), WmsConstants.SYNC_MAX_RETRY, error);
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, null,
                    WmsConstants.SYNC_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("订单[{}]同步失败告警投递失败：{}", order.getOrderNo(), e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text
                : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }
}
```

> 本类**不注入任何 Mapper、不写库**——落库由调用方（订单服务、定时任务）负责，保证「谁拥有数据谁写」。其中 `ISysBaseAPI` 由 `jeecg-system-local-api` 提供（Task 8 Step 1 会补该依赖）。
>
> `setBackoffMsForTest` 是包级方法，仅供单测把退避时间置零，避免测试白等 7 秒。

- [ ] **Step 5: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderSyncServiceImplTest' -DfailIfNoTests=false
```

Expected: 7 个用例全绿。其中：
- ③ 绿 = 验收标准「同步失败有明确记录」的逻辑核心
- ⑤ 绿 = 防轰炸开关生效
- `syncOrder_shouldTruncateOverlongErrorMessage` 绿 = Review Focus #4 已钉住

- [ ] **Step 6: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/sync/WmsOrderSyncAttempt.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsOrderSyncService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsOrderSyncServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderSyncServiceImplTest.java
git commit -m "feat(wms): BD-002 同步重试编排与失败告警"
```

---

### Task 6: 认领抢占 + 三段式下达/重同步（接入 Task 5）

**Files:**
- Modify: `.../wms/mapper/WmsOrderMapper.java`
- Create: `.../wms/mapper/xml/WmsOrderMapper.xml`
- Modify: `.../wms/service/IWmsOrderService.java`
- Modify: `.../wms/service/impl/WmsOrderServiceImpl.java`
- Modify: `.../src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImplTest.java`

**Interfaces:**
- Consumes: Task 5 的 `IWmsOrderSyncService.syncOrder(order, announce)` → `WmsOrderSyncAttempt`；Task 1 常量
- Produces:
  - `WmsOrderMapper`：`int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore)`、`List<WmsOrder> selectSyncCandidates(@Param("staleBefore") Date staleBefore, @Param("limit") int limit)`
  - `IWmsOrderService` 追加：`WmsOrderSyncOutcomeVO dispatchOrder(String id)`、`WmsOrderSyncOutcomeVO resyncOrder(String id)`、`int claimForSync(String id, Date staleBefore)`、`List<WmsOrder> selectSyncCandidates(Date staleBefore, int limit)`、`void persistSyncResult(String id, WmsOrderSyncAttempt attempt)`、`Date syncStaleBefore()`
  - `WmsOrderSyncOutcomeVO` 的 `synced/syncError/retryCount` 被控制器与前端消费
  - **认领与落库经服务暴露给 Task 8**：定时任务只依赖 `IWmsOrderService` / `IWmsOrderSyncService` 两个接口，不碰 Mapper，落库逻辑仅此一处

- [ ] **Step 1: 写失败的测试（覆盖 Review Focus #3）**

在 `WmsOrderServiceImplTest` 末尾追加：

```java
    // ------------------------------------------------------------ Review Focus #3：并发互斥

    /** 认领失败（别人已持有）时必须跳过，绝不能调用 gateway 重复投递 ERP/MES。 */
    @Test
    void dispatchOrder_whenClaimFails_shouldSkipSync() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setId("1").setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED);
        doReturn(order).when(service).getById("1");
        // dispatchOrder 会先写下达状态，必须打桩 updateById，否则会走到真实的 ServiceImpl（baseMapper 为空）NPE
        doReturn(true).when(service).updateById(any());
        doReturn(0).when(service).claimForSync(any(), any());

        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        service.setSyncServiceForTest(syncService);

        WmsOrderSyncOutcomeVO outcome = service.dispatchOrder("1");

        // 没抢到锁 → 既不调同步，也不谎报成功
        verify(syncService, never()).syncOrder(any(), anyBoolean());
        assertEquals(null, outcome.getSynced());
    }

    /** 下达成功且认领成功 → 必须调用同步一次，并把结果映射进 VO。 */
    @Test
    void dispatchOrder_whenClaimSucceeds_shouldSyncOnce() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setId("1").setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED);
        doReturn(order).when(service).getById("1");
        doReturn(1).when(service).claimForSync(any(), any());
        doReturn(true).when(service).updateById(any());

        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(syncService.syncOrder(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(false, "ERP 未配置", 3));
        service.setSyncServiceForTest(syncService);

        WmsOrderSyncOutcomeVO outcome = service.dispatchOrder("1");

        verify(syncService, times(1)).syncOrder(any(), eq(true));
        assertEquals(false, outcome.getSynced());
        assertEquals("ERP 未配置", outcome.getSyncError());
        assertEquals(3, outcome.getRetryCount());
    }

    /** 只有「已审核」可下达。 */
    @Test
    void dispatchOrder_shouldRejectNonAudited() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.dispatchOrder("1"));
        assertEquals("仅已审核状态的订单可下达", ex.getMessage());
    }

    /** 只有「已下达 且 同步失败/待同步」可重新同步。 */
    @Test
    void resyncOrder_shouldRejectNonFailedStates() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setId("1")
                .setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED)
                .setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS);
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resyncOrder("1"));
        assertEquals("仅同步失败或待同步的订单可重新同步", ex.getMessage());
    }
```

并在 import 区补齐：

```java
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderServiceImplTest' -DfailIfNoTests=false
```

Expected: 编译失败（`dispatchOrder` / `claimForSync` / `setSyncServiceForTest` 不存在）。

- [ ] **Step 3: Mapper 追加两条自定义语句声明**

把 `.../wms/mapper/WmsOrderMapper.java` 改为：

```java
package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsOrder;

import java.util.Date;
import java.util.List;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsOrderMapper extends BaseMapper<WmsOrder> {

    /**
     * 乐观认领：把可同步的订单置为「同步中」，用于互斥「用户下达」与「定时补偿」两个触发源，
     * 防止同一张订单被重复投递给 ERP/MES。
     *
     * @param id          订单ID
     * @param staleBefore 「同步中」的僵死阈值；last_sync_time 早于此值才允许被回收
     * @return 受影响行数；1 表示认领成功，0 表示已被他人持有或状态不允许
     */
    int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore);

    /**
     * 取待补偿的订单：已下达且同步失败 / 待同步 / 「同步中」但已僵死。
     *
     * @param staleBefore 「同步中」的僵死阈值
     * @param limit       每轮上限
     */
    List<WmsOrder> selectSyncCandidates(@Param("staleBefore") Date staleBefore, @Param("limit") int limit);
}
```

- [ ] **Step 4: 创建 `mapper/xml/WmsOrderMapper.xml`**

路径必须是 `src/main/java/org/jeecg/modules/wms/mapper/xml/WmsOrderMapper.xml`（**不是** `src/main/resources`），`mapper-locations` 才会匹配到。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.jeecg.modules.wms.mapper.WmsOrderMapper">

    <!--
      乐观认领。三条允许来源：
        1) 待同步            —— 下达后尚未开始
        2) 同步失败          —— 需要重新投递
        3) 同步中 且已僵死    —— 上次进程崩溃留下的残局（超过阈值才回收）
      返回受影响行数，调用方据此判断是否抢到，0 即跳过。
    -->
    <update id="claimForSync">
        UPDATE wms_order
           SET sync_status    = '同步中',
               last_sync_time = NOW()
         WHERE id = #{id}
           AND order_status = '已下达'
           AND del_flag = 0
           AND (
                 sync_status IN ('待同步', '同步失败')
                 OR (sync_status = '同步中' AND (last_sync_time IS NULL OR last_sync_time &lt; #{staleBefore}))
               )
    </update>

    <!-- 取待补偿订单：每轮限量，按最后尝试时间升序（最久没动的优先）。 -->
    <select id="selectSyncCandidates" resultType="org.jeecg.modules.wms.entity.WmsOrder">
        SELECT *
          FROM wms_order
         WHERE del_flag = 0
           AND order_status = '已下达'
           AND (
                 sync_status IN ('待同步', '同步失败')
                 OR (sync_status = '同步中' AND (last_sync_time IS NULL OR last_sync_time &lt; #{staleBefore}))
               )
         ORDER BY last_sync_time IS NULL DESC, last_sync_time ASC
         LIMIT #{limit}
    </select>

</mapper>
```

- [ ] **Step 5: 服务接口追加下达/重同步/认领/补偿方法**

先在 `IWmsOrderService` 的 import 区补三个 import（`WmsOrderSyncAttempt` 到 Task 5 才存在，故此处才可引用）：

```java
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;

import java.util.Date;
```

再在接口中追加以下方法：

```java
    /**
     * 下达订单：状态置「已下达」→ 认领 → 同步 ERP/MES。
     *
     * @return 同步结果；{@code synced=null} 表示未抢到认领、本次未执行同步
     */
    WmsOrderSyncOutcomeVO dispatchOrder(String id);

    /**
     * 重新同步（仅「已下达 且 同步失败/待同步」）。
     */
    WmsOrderSyncOutcomeVO resyncOrder(String id);

    /**
     * 乐观认领：把可同步的订单置为「同步中」。定时补偿任务经此入口，避免直接操作 Mapper。
     *
     * @return 受影响行数；1 表示认领成功，0 表示已被他人持有
     */
    int claimForSync(String id, Date staleBefore);

    /**
     * 取待补偿订单（定时任务用），避免定时任务直接操作 Mapper。
     */
    List<WmsOrder> selectSyncCandidates(Date staleBefore, int limit);

    /**
     * 写回同步结果。集中在一处，避免订单服务与定时任务各写一遍。
     */
    void persistSyncResult(String id, WmsOrderSyncAttempt attempt);

    /**
     * 「同步中」僵死阈值，供定时任务复用同一口径。
     */
    Date syncStaleBefore();
```

- [ ] **Step 6: 服务实现接入认领与三段式**

在 `WmsOrderServiceImpl` 中：

**(a) 追加字段与依赖注入 + 测试注入点**（放在 `wmsSupplierMapper` 之后）：

```java
    @Autowired
    private IWmsOrderSyncService orderSyncService;

    /** 仅供单测替换同步服务。 */
    void setSyncServiceForTest(IWmsOrderSyncService orderSyncService) {
        this.orderSyncService = orderSyncService;
    }
```

**(b) 追加 `dispatchOrder` / `resyncOrder` / `claimAndSync` / `persistSyncResult` / `syncStaleBefore` / `claimForSync` / `selectSyncCandidates` 七个成员**（放在 `fillSupplierName` 之后）：

```java
    // ------------------------------------------------------------ 下达与重同步

    @Override
    public WmsOrderSyncOutcomeVO dispatchOrder(String id) {
        WmsOrder existing = this.getById(id);
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_AUDITED.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅已审核状态的订单可下达");
        }
        // 【事务内】先落下达状态，再抢占认领；两件事都不能拖进长事务
        WmsOrder update = new WmsOrder();
        update.setId(id);
        update.setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        update.setDispatchBy(WmsUtils.currentUsername());
        update.setDispatchTime(new Date());
        WmsUtils.fillUpdate(update);
        this.updateById(update);
        return claimAndSync(id, existing);
    }

    @Override
    public WmsOrderSyncOutcomeVO resyncOrder(String id) {
        WmsOrder existing = this.getById(id);
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_DISPATCHED.equals(existing.getOrderStatus())
                || (!WmsConstants.SYNC_STATUS_FAILED.equals(existing.getSyncStatus())
                    && !WmsConstants.SYNC_STATUS_PENDING.equals(existing.getSyncStatus()))) {
            throw new RuntimeException("仅同步失败或待同步的订单可重新同步");
        }
        return claimAndSync(id, existing);
    }

    /**
     * 【事务外】认领 → 同步（最坏约 7 秒，不持事务）→ 写回结果。
     *
     * <p>认领失败说明别的触发源正在处理这张单，直接跳过——绝不重复投递给 ERP/MES。
     */
    private WmsOrderSyncOutcomeVO claimAndSync(String id, WmsOrder snapshot) {
        int claimed = claimForSync(id, syncStaleBefore());
        if (claimed == 0) {
            log.info("订单[{}]未抢到同步认领（可能已被其它触发源处理），本次跳过", snapshot.getOrderNo());
            return new WmsOrderSyncOutcomeVO().setSynced(null);
        }
        snapshot.setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        snapshot.setSyncStatus(WmsConstants.SYNC_STATUS_RUNNING);
        WmsOrderSyncAttempt attempt = orderSyncService.syncOrder(snapshot, true);
        persistSyncResult(id, attempt);
        return new WmsOrderSyncOutcomeVO()
                .setSynced(attempt.isSuccess())
                .setSyncError(attempt.getErrorMessage())
                .setRetryCount(attempt.getRetryCount());
    }

    /**
     * 写回同步结果。单独一个短更新，避免与「同步中」的认领同处一个长事务。
     *
     * <p>公开给定时补偿任务复用，保证「同步结果怎么落库」只有这一处实现。
     */
    @Override
    public void persistSyncResult(String id, WmsOrderSyncAttempt attempt) {
        WmsOrder result = new WmsOrder();
        result.setId(id);
        result.setSyncStatus(attempt.isSuccess()
                ? WmsConstants.SYNC_STATUS_SUCCESS
                : WmsConstants.SYNC_STATUS_FAILED);
        result.setRetryCount(attempt.getRetryCount());
        result.setLastSyncError(attempt.isSuccess() ? null : attempt.getErrorMessage());
        result.setLastSyncTime(new Date());
        WmsUtils.fillUpdate(result);
        this.updateById(result);
    }

    /** 「同步中」僵死阈值；定时任务复用同一口径，避免两处阈值漂移。 */
    @Override
    public Date syncStaleBefore() {
        return new Date(System.currentTimeMillis()
                - WmsConstants.SYNC_RECLAIM_TIMEOUT_SECONDS * 1000L);
    }

    @Override
    public int claimForSync(String id, Date staleBefore) {
        return baseMapper.claimForSync(id, staleBefore);
    }

    @Override
    public List<WmsOrder> selectSyncCandidates(Date staleBefore, int limit) {
        return baseMapper.selectSyncCandidates(staleBefore, limit);
    }
```

**(c) 补 import**（`WmsOrderServiceImpl` 顶部）：

```java
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
```

> **注意**：`claimForSync` 在实现类里是 `public`（`baseMapper.claimForSync` 的薄封装），单测用 `spy` + `doReturn(0)` 打桩它——不要把它写成 `private`，否则单测无法打桩。

- [ ] **Step 7: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderServiceImplTest' -DfailIfNoTests=false
```

Expected: 全绿。`dispatchOrder_whenClaimFails_shouldSkipSync` 绿 = Review Focus #3 已被钉住。
`resyncOrder_shouldRejectNonFailedStates` 里报错文案必须是「仅同步失败或待同步的订单可重新同步」。

- [ ] **Step 8: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/mapper/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/IWmsOrderService.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/service/impl/WmsOrderServiceImplTest.java
git commit -m "feat(wms): BD-002 下单认领抢占与三段式同步落库"
```

---

### Task 7: 订单控制器

**Files:**
- Create: `.../wms/controller/WmsOrderController.java`

**Interfaces:**
- Consumes: Task 3/6 的 `IWmsOrderService`、Task 1 的 `WmsOrderAuditDTO`/`WmsOrderSyncOutcomeVO`
- Produces: 全部 `/wms/order/*` 接口（供 Task 11 前端与 Task 12 E2E 使用）

- [ ] **Step 1: 创建控制器**

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
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Tag(name = "WMS-订单")
@RestController
@RequestMapping("/wms/order")
@Slf4j
public class WmsOrderController extends JeecgController<WmsOrder, IWmsOrderService> {

    /**
     * 分页列表查询（支持 orderNo / orderType / orderStatus / syncStatus / supplierId / 创建时间区间）
     */
    @Operation(summary = "订单-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsOrder>> queryPageList(WmsOrder wmsOrder,
                                                @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                HttpServletRequest req) {
        // 代码逻辑说明: supplierName 是非持久化字段（表里无对应列），必须先置空，
        // 否则 QueryGenerator 会按它拼出 supplier_name 条件导致 SQL 报错
        wmsOrder.setSupplierName(null);
        QueryWrapper<WmsOrder> queryWrapper = QueryGenerator.initQueryWrapper(wmsOrder, req.getParameterMap());
        queryWrapper.orderByDesc("create_time");
        Page<WmsOrder> page = new Page<>(pageNo, pageSize);
        IPage<WmsOrder> pageList = service.page(page, queryWrapper);
        service.fillSupplierName(pageList.getRecords());
        return Result.OK(pageList);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "订单-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsOrder> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsOrder entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        service.fillSupplierName(java.util.Collections.singletonList(entity));
        return Result.OK(entity);
    }

    /**
     * 订单号唯一性校验
     */
    @Operation(summary = "订单-订单号唯一性校验")
    @GetMapping(value = "/checkOrderNo")
    public Result<Boolean> checkOrderNo(@RequestParam(name = "code") String code,
                                        @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("订单号不能为空");
        }
        if (service.checkOrderNoExists(code, id)) {
            return Result.error("订单号已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "订单-添加", operateType = 2)
    @Operation(summary = "订单-添加")
    @PostMapping(value = "/add")
    public Result<WmsOrder> add(@RequestBody WmsOrder wmsOrder) {
        try {
            service.addOrder(wmsOrder);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "订单-编辑", operateType = 3)
    @Operation(summary = "订单-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsOrder> edit(@RequestBody WmsOrder wmsOrder) {
        try {
            service.editOrder(wmsOrder);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 审核（通过 / 驳回）
     */
    @AutoLog(value = "订单-审核", operateType = 3)
    @Operation(summary = "订单-审核")
    @PostMapping(value = "/audit")
    public Result<?> audit(@RequestBody WmsOrderAuditDTO dto) {
        try {
            service.auditOrder(dto);
            return Result.OK(Boolean.TRUE.equals(dto.getPass()) ? "审核通过！" : "已驳回！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 下达（内部触发同步 ERP/MES）
     */
    @AutoLog(value = "订单-下达", operateType = 3)
    @Operation(summary = "订单-下达")
    @PostMapping(value = "/dispatch")
    public Result<WmsOrderSyncOutcomeVO> dispatch(@RequestParam(name = "id", required = true) String id) {
        try {
            return Result.OK("下达成功！", service.dispatchOrder(id));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 重新同步（仅同步失败/待同步的已下达订单）
     */
    @AutoLog(value = "订单-重新同步", operateType = 3)
    @Operation(summary = "订单-重新同步")
    @PostMapping(value = "/resync")
    public Result<WmsOrderSyncOutcomeVO> resync(@RequestParam(name = "id", required = true) String id) {
        try {
            return Result.OK("已重新同步！", service.resyncOrder(id));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 通过id删除
     */
    @AutoLog(value = "订单-删除", operateType = 4)
    @Operation(summary = "订单-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeOrder(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除
     */
    @AutoLog(value = "订单-批量删除", operateType = 4)
    @Operation(summary = "订单-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        try {
            service.removeBatch(Arrays.asList(ids.split(",")));
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/controller/WmsOrderController.java
git commit -m "feat(wms): BD-002 订单控制器（增删改查/审核/下达/重同步）"
```

---

### Task 8: 定时补偿任务与启动自注册器

**Files:**
- Create: `.../wms/job/WmsOrderSyncRetryJob.java`
- Create: `.../wms/job/WmsOrderSyncJobRegistrar.java`
- Test: `.../src/test/java/org/jeecg/modules/wms/job/WmsOrderSyncRetryJobTest.java`

**Interfaces:**
- Consumes: Task 6 的 `IWmsOrderService.selectSyncCandidates(Date, int)` / `claimForSync(String, Date)` / `persistSyncResult(String, WmsOrderSyncAttempt)` / `syncStaleBefore()`；Task 5 的 `IWmsOrderSyncService.syncOrder(WmsOrder, boolean)` 与 `WmsOrderSyncAttempt`；Task 1 常量
- Produces: `WmsOrderSyncRetryJob implements org.quartz.Job`（依赖两个**接口**，不碰 Mapper）；`WmsOrderSyncJobRegistrar`（`@Component`，`ApplicationReadyEvent`）
- **必需的依赖变更**：`jeecg-boot-module-wms/pom.xml` 追加 `jeecg-system-local-api`（见 Step 1）

- [ ] **Step 1: pom 追加依赖（站内告警需要 `ISysBaseAPI`）**

修改 `jeecg-boot-module/jeecg-boot-module-wms/pom.xml` 的 `<dependencies>`：

```xml
    <dependencies>
        <dependency>
            <groupId>org.jeecgframework.boot3</groupId>
            <artifactId>jeecg-boot-base-core</artifactId>
        </dependency>
        <!-- 站内消息告警需要 ISysBaseAPI；单体用 local-api，微服务换成 cloud-api（写法与 airag 模块一致） -->
        <dependency>
            <groupId>org.jeecgframework.boot3</groupId>
            <artifactId>jeecg-system-local-api</artifactId>
        </dependency>
        <!--
        <dependency>
            <groupId>org.jeecgframework.boot3</groupId>
            <artifactId>jeecg-system-cloud-api</artifactId>
        </dependency>
        -->
    </dependencies>
```

> Quartz **不需要**加依赖：`spring-boot-starter-quartz` 已声明在 `jeecg-boot-base-core/pom.xml`，本模块传递获得 `org.quartz.*`。

- [ ] **Step 2: 写失败的测试（单条失败不中断整轮）**

创建 `src/test/java/org/jeecg/modules/wms/job/WmsOrderSyncRetryJobTest.java`：

```java
package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 补偿任务单测：单条失败不得中断整轮
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderSyncRetryJobTest {

    /**
     * 3 条候选中第 2 条抛异常 → 第 1、3 条仍必须被处理，且第 2 条不被重复处理。
     */
    @Test
    void scanAndResync_shouldNotAbortRoundOnSingleFailure() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);

        WmsOrder first = new WmsOrder().setId("1").setOrderNo("PO001");
        WmsOrder bad = new WmsOrder().setId("2").setOrderNo("PO002");
        WmsOrder third = new WmsOrder().setId("3").setOrderNo("PO003");
        when(orderService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(first, bad, third));
        when(orderService.claimForSync(any(), any())).thenReturn(1);

        when(syncService.syncOrder(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0))
                .thenThrow(new RuntimeException("模拟第 2 条炸了"))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        WmsOrderSyncRetryJob job = new WmsOrderSyncRetryJob(orderService, syncService);
        job.scanAndResyncForTest();

        // 三条都尝试过：第 2 条虽然炸了，第 3 条仍被处理
        verify(syncService, times(3)).syncOrder(any(), anyBoolean());
    }

    /** 认领失败的候选必须跳过，不调用同步。 */
    @Test
    void scanAndResync_shouldSkipWhenClaimFails() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(orderService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Collections.singletonList(new WmsOrder().setId("1").setOrderNo("PO001")));
        when(orderService.claimForSync(any(), any())).thenReturn(0);

        new WmsOrderSyncRetryJob(orderService, syncService).scanAndResyncForTest();

        verify(syncService, never()).syncOrder(any(), anyBoolean());
    }

    /** 没有候选时必须安静返回，不报错。 */
    @Test
    void scanAndResync_shouldHandleEmptyCandidates() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(orderService.selectSyncCandidates(any(Date.class), anyInt())).thenReturn(Collections.emptyList());

        new WmsOrderSyncRetryJob(orderService, syncService).scanAndResyncForTest();

        verify(syncService, never()).syncOrder(any(), anyBoolean());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderSyncRetryJobTest' -DfailIfNoTests=false
```

Expected: 编译失败（`WmsOrderSyncRetryJob` 不存在）。

- [ ] **Step 4: 创建补偿任务**

`.../wms/job/WmsOrderSyncRetryJob.java`：

```java
package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Description: WMS 订单同步补偿任务。
 *
 * <p>扫描「已下达」且同步失败 / 待同步 / 同步中已僵死的订单，自动续跑同步，实现自愈。
 * 加 {@link DisallowConcurrentExecution} 防止单轮耗时超过周期时自我重叠。
 *
 * <p>依赖的都是**接口**（{@link IWmsOrderService} / {@link IWmsOrderSyncService}），
 * 不直接操作 Mapper——候选查询、认领、结果落库都经由订单服务，保持「谁拥有数据谁写」，
 * 也避免落库逻辑在订单服务与定时任务两处各写一遍。
 *
 * <p>注意：本任务**不代用户下达**——「已审核未下达」的订单不会被自动下达，下达是人的决策。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsOrderSyncRetryJob implements Job {

    private final IWmsOrderService orderService;
    private final IWmsOrderSyncService orderSyncService;

    @Autowired
    public WmsOrderSyncRetryJob(IWmsOrderService orderService, IWmsOrderSyncService orderSyncService) {
        this.orderService = orderService;
        this.orderSyncService = orderSyncService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanAndResyncForTest();
    }

    /**
     * 一轮扫描与续跑。可见性放宽到包级，便于单测直接驱动。
     */
    void scanAndResyncForTest() {
        // 阈值口径由订单服务统一提供，避免两处设定漂移
        Date staleBefore = orderService.syncStaleBefore();
        List<WmsOrder> candidates;
        try {
            candidates = orderService.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("补偿任务取候选订单失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("补偿任务本轮无待处理订单");
            return;
        }
        int claimed = 0;
        int success = 0;
        int failed = 0;
        for (WmsOrder order : candidates) {
            try {
                int affected = orderService.claimForSync(order.getId(), staleBefore);
                if (affected == 0) {
                    // 已被用户下达或上一轮处理中，跳过即可，不是错误
                    continue;
                }
                claimed++;
                // announce=false：定时任务每 5 分钟跑一次，发站内消息会轰炸用户
                WmsOrderSyncAttempt attempt = orderSyncService.syncOrder(order, false);
                orderService.persistSyncResult(order.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // 单条失败不得中断整轮
                log.error("补偿任务处理订单[{}]异常：{}", order.getOrderNo(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("补偿任务本轮结束：候选={}，认领成功={}，同步成功={}，同步失败={}",
                candidates.size(), claimed, success, failed);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='WmsOrderSyncRetryJobTest' -DfailIfNoTests=false
```

Expected: 3 个用例全绿（`syncOrder_shouldNotAbortRound...` 验证了 Review Focus #3 的批次侧）。

- [ ] **Step 6: 创建启动自注册器**

这一步是**堵住最关键的一个坑**：`sys_quartz_job` 只是注册表元数据，全库没有启动加载器，光靠 Flyway 插行任务不会跑。

`.../wms/job/WmsOrderSyncJobRegistrar.java`：

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
 * @Description: 补偿任务的启动自注册器。
 *
 * <p>为什么要它：`sys_quartz_job` 表只是平台的注册表元数据，项目里**没有**任何启动加载器，
 * 任务只经「定时任务管理」UI 的 saveAndScheduleJob 写入调度器。若只靠 Flyway 插一行，
 * 全新环境上任务永远不会执行——自愈会静默失效。故在此幂等自注册。
 *
 * <p>启停以配置 {@code wms.sync.job.enabled} 为准（默认 true）。刻意**不读**
 * sys_quartz_job.status，以免 wms 模块反向依赖 system-biz 的 QuartzJob 实体。
 *
 * <p>约定与 {@code QuartzJobServiceImpl.schedulerAdd} 对齐：JobDetail/CronTrigger 均用
 * withIdentity(id) 的**默认组**，这样「定时任务管理」UI 的启停按钮控制的是同一个 job。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
public class WmsOrderSyncJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.SYNC_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsOrderSyncJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!jobEnabled) {
            log.info("补偿任务已按配置 {} 关闭，跳过注册", WmsConstants.SYNC_JOB_ENABLED_KEY);
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                log.info("补偿任务[{}]已存在于调度器中，跳过注册", WmsConstants.SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsOrderSyncRetryJob.class)
                    .withIdentity(WmsConstants.SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("补偿任务[{}]注册成功，cron={}", WmsConstants.SYNC_JOB_ID, WmsConstants.SYNC_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("补偿任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 7: 编译并跑全部 WMS 测试**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dtest='Wms*Test' -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`，全部测试类绿灯。**如实记录每个测试类的 `Tests run` 数字。**

- [ ] **Step 8: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/pom.xml \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/job/ \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/job/
git commit -m "feat(wms): BD-002 同步补偿定时任务与启动自注册器"
```

---

### Task 9: 菜单、权限与定时任务种子（Flyway V3.9.5_4）

**Files:**
- Create: `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_4__wms_supplier_order_menu_job.sql`

**Interfaces:**
- Consumes: Task 8 的 `WmsConstants.SYNC_JOB_ID`/`SYNC_JOB_CLASS`/`SYNC_JOB_CRON`（**必须同值**）
- Produces: 菜单 `/wms/supplier` 与 `/wms/order`、按钮权限码、`sys_quartz_job` 种子行

- [ ] **Step 1: 创建脚本**

```sql
-- ============================================================================
-- 永鑫WMS · 供应商与生产订单管理（蓝图流程 YX-WMS-BD-002）—— 菜单与任务种子
-- 内容：既有 /wms 目录改名为「基础管理」+ 两个新菜单 + 按钮权限 + admin 授权
--       + 补偿定时任务的 sys_quartz_job 种子行
-- 说明：本脚本可重复执行（INSERT IGNORE / UPDATE 带条件）
-- ============================================================================

-- ------------------------------------------------ 既有目录改名：物料管理 → 基础管理
-- 带 name='物料管理' 条件，重复执行不会覆盖后续的人工改名
UPDATE `sys_permission` SET `name` = '基础管理'
 WHERE `id` = '1910000000000000001' AND `name` = '物料管理';

-- ------------------------------------------------ 菜单（menu_type=1）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000021','1910000000000000001','供应商管理','/wms/supplier','wms/supplier/index',1,'','',1,NULL,'0',2.00,0,'ant-design:shop-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000022','1910000000000000001','订单管理','/wms/order','wms/order/index',1,'','',1,NULL,'0',3.00,0,'ant-design:profile-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000031','1910000000000000021','供应商-新增',NULL,NULL,0,NULL,'',2,'wms:supplier:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000032','1910000000000000021','供应商-编辑',NULL,NULL,0,NULL,'',2,'wms:supplier:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000033','1910000000000000021','供应商-删除',NULL,NULL,0,NULL,'',2,'wms:supplier:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000041','1910000000000000022','订单-新增',NULL,NULL,0,NULL,'',2,'wms:order:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000042','1910000000000000022','订单-编辑',NULL,NULL,0,NULL,'',2,'wms:order:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000043','1910000000000000022','订单-删除',NULL,NULL,0,NULL,'',2,'wms:order:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000044','1910000000000000022','订单-审核',NULL,NULL,0,NULL,'',2,'wms:order:audit','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000045','1910000000000000022','订单-下达',NULL,NULL,0,NULL,'',2,'wms:order:dispatch','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000046','1910000000000000022','订单-重新同步',NULL,NULL,0,NULL,'',2,'wms:order:resync','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ admin 角色授权
-- 注意：既有 admin 角色 id 取自 V3.9.5_2__wms_material.sql
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000021','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000021',NULL,NOW(),'127.0.0.1'),
('1920000000000000022','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000022',NULL,NOW(),'127.0.0.1'),
('1920000000000000031','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000031',NULL,NOW(),'127.0.0.1'),
('1920000000000000032','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000032',NULL,NOW(),'127.0.0.1'),
('1920000000000000033','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000033',NULL,NOW(),'127.0.0.1'),
('1920000000000000041','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000041',NULL,NOW(),'127.0.0.1'),
('1920000000000000042','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000042',NULL,NOW(),'127.0.0.1'),
('1920000000000000043','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000043',NULL,NOW(),'127.0.0.1'),
('1920000000000000044','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000044',NULL,NOW(),'127.0.0.1'),
('1920000000000000045','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000045',NULL,NOW(),'127.0.0.1'),
('1920000000000000046','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000046',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 补偿定时任务种子行
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」（适合验收演示）。
-- 实际调度由 WmsOrderSyncJobRegistrar 负责——本行不会被平台自动加载。
-- id 必须与 WmsConstants.SYNC_JOB_ID 同值；job_class_name 必须与 SYNC_JOB_CLASS 同值。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000101','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsOrderSyncRetryJob','0 3/5 * * * ? *',NULL,'WMS订单同步失败补偿任务',0);
```

- [ ] **Step 2: 提交（SQL 的生效验证放在 Task 12 的启动步骤）**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_4__wms_supplier_order_menu_job.sql
git commit -m "feat(wms): BD-002 菜单权限与补偿任务种子脚本"
```

---

### Task 10: 供应商前端页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/supplier/supplier.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/supplier/supplier.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/supplier/components/SupplierModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/supplier/index.vue`

**Interfaces:**
- Consumes: Task 2 的 `/wms/supplier/*` 接口
- Produces: 路由组件 `wms/supplier/index`（Task 9 的菜单 `component` 指向它）

- [ ] **Step 1: 创建 `supplier.api.ts`**

```ts
import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/supplier/list',
  listAll = '/wms/supplier/listAll',
  add = '/wms/supplier/add',
  edit = '/wms/supplier/edit',
  disable = '/wms/supplier/disable',
  deleteOne = '/wms/supplier/delete',
  deleteBatch = '/wms/supplier/deleteBatch',
  checkCode = '/wms/supplier/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 启用中的供应商（订单页下拉用） */
export const listAllEnabled = () => defHttp.get({ url: Api.listAll });

/** 供应商编码唯一性校验：返回原始报文以便读取 message */
export const checkSupplierCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateSupplier = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const disableSupplier = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteSupplier = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteSupplier = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
```

- [ ] **Step 2: 创建 `supplier.data.ts`**

```ts
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkSupplierCode } from './supplier.api';

/** 状态选项 */
export const STATUS_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' },
];

export const columns: BasicColumn[] = [
  { title: '供应商编码', align: 'center', dataIndex: 'supplierCode', width: 140 },
  { title: '供应商名称', align: 'center', dataIndex: 'supplierName', width: 180 },
  { title: '联系人', align: 'center', dataIndex: 'contact', width: 100 },
  { title: '联系电话', align: 'center', dataIndex: 'phone', width: 130 },
  { title: '供货范围', align: 'center', dataIndex: 'supplyScope' },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

export const searchFormSchema: FormSchema[] = [
  { label: '供应商编码', field: 'supplierCode', component: 'JInput', colProps: { span: 6 } },
  { label: '供应商名称', field: 'supplierName', component: 'JInput', colProps: { span: 6 } },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '供应商编码',
    field: 'supplierCode',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入供应商编码!' },
        {
          // 唯一性：请求返回的第二参数用于排除自身
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkSupplierCode({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '编码已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  { label: '供应商名称', field: 'supplierName', component: 'Input', required: true },
  { label: '联系人', field: 'contact', component: 'Input' },
  { label: '联系电话', field: 'phone', component: 'Input' },
  { label: '供货范围', field: 'supplyScope', component: 'InputTextArea' },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];
```

- [ ] **Step 3: 创建 `components/SupplierModal.vue`**

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="600px">
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" setup>
  import { ref, computed, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form/index';
  import { formSchema } from '../supplier.data';
  import { saveOrUpdateSupplier } from '../supplier.api';

  const emit = defineEmits(['success', 'register']);
  const isUpdate = ref(true);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate)) {
      setFieldsValue({ ...data.record });
    }
  });

  const title = computed(() => (unref(isUpdate) ? '编辑供应商' : '新增供应商'));

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await saveOrUpdateSupplier(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 4: 创建 `index.vue`**

```vue
<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:supplier:add'" @click="handleCreate">
          新增
        </a-button>
        <a-dropdown v-if="selectedRowKeys.length > 0">
          <template #overlay>
            <a-menu>
              <a-menu-item key="1" @click="batchHandleDelete">
                <Icon icon="ant-design:delete-outlined" />
                批量删除
              </a-menu-item>
            </a-menu>
          </template>
          <a-button v-auth="'wms:supplier:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <SupplierModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-supplier" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import SupplierModal from './components/SupplierModal.vue';
  import { columns, searchFormSchema } from './supplier.data';
  import { list, deleteSupplier, batchDeleteSupplier, disableSupplier } from './supplier.api';

  const { prefixCls } = useDesign('wms-supplier');

  const [registerModal, { openModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
    },
  });

  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleDelete(record) {
    await deleteSupplier({ id: record.id }, handleSuccess);
  }

  async function batchHandleDelete() {
    const ids = selectedRowKeys.value.filter((key) => !String(key).includes('loadChild'));
    if (ids.length === 0) {
      return;
    }
    await batchDeleteSupplier({ ids: ids.join(',') }, handleSuccess);
  }

  async function handleDisable(record) {
    await disableSupplier({ id: record.id });
    reload();
  }

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function getTableAction(record) {
    return [
      {
        label: '编辑',
        auth: 'wms:supplier:edit',
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '停用',
        auth: 'wms:supplier:edit',
        ifShow: record.status === '1',
        popConfirm: {
          title: '确定停用吗?',
          confirm: handleDisable.bind(null, record),
        },
      },
      {
        label: '删除',
        auth: 'wms:supplier:delete',
        popConfirm: {
          title: '确定删除吗?',
          confirm: handleDelete.bind(null, record),
        },
      },
    ];
  }
</script>
```

- [ ] **Step 5: 提交（类型检查统一在 Task 12 跑 `pnpm build`）**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecgboot-vue3/src/views/wms/supplier/
git commit -m "feat(wms): BD-002 供应商管理前端页"
```

---

### Task 11: 订单前端页

**Files:**
- Create: `jeecgboot-vue3/src/views/wms/order/order.api.ts`
- Create: `jeecgboot-vue3/src/views/wms/order/order.data.ts`
- Create: `jeecgboot-vue3/src/views/wms/order/components/OrderModal.vue`
- Create: `jeecgboot-vue3/src/views/wms/order/index.vue`

**Interfaces:**
- Consumes: Task 7 的 `/wms/order/*`、Task 10 的 `listAllEnabled`（供应商下拉）
- Produces: 路由组件 `wms/order/index`（Task 9 的菜单 `component` 指向它）

- [ ] **Step 1: 创建 `order.api.ts`**

```ts
import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/order/list',
  add = '/wms/order/add',
  edit = '/wms/order/edit',
  audit = '/wms/order/audit',
  dispatch = '/wms/order/dispatch',
  resync = '/wms/order/resync',
  deleteOne = '/wms/order/delete',
  deleteBatch = '/wms/order/deleteBatch',
  checkOrderNo = '/wms/order/checkOrderNo',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 订单号唯一性校验：返回原始报文以便读取 message */
export const checkOrderNo = (params) => defHttp.get({ url: Api.checkOrderNo, params }, { isTransformResponse: false });

export const saveOrUpdateOrder = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const auditOrder = (params) => defHttp.post({ url: Api.audit, params });

/** 下达（内部会触发同步，返回 { synced, syncError, retryCount }） */
export const dispatchOrder = (params) => defHttp.post({ url: Api.dispatch, params }, { joinParamsToUrl: true });

/** 手动重新同步 */
export const resyncOrder = (params) => defHttp.post({ url: Api.resync, params }, { joinParamsToUrl: true });

export const deleteOrder = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteOrder = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
```

- [ ] **Step 2: 创建 `order.data.ts`**

```ts
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkOrderNo } from './order.api';

/** 订单类型选项（与蓝图：公司订单/采购订单/委外订单 一致） */
export const ORDER_TYPE_OPTIONS = [
  { label: '公司订单', value: '公司订单' },
  { label: '采购订单', value: '采购订单' },
  { label: '委外订单', value: '委外订单' },
];

/** 订单状态选项 */
export const ORDER_STATUS_OPTIONS = [
  { label: '待审核', value: '待审核' },
  { label: '已审核', value: '已审核' },
  { label: '已下达', value: '已下达' },
  { label: '已驳回', value: '已驳回' },
];

/** 同步状态选项 */
export const SYNC_STATUS_OPTIONS = [
  { label: '待同步', value: '待同步' },
  { label: '同步中', value: '同步中' },
  { label: '同步成功', value: '同步成功' },
  { label: '同步失败', value: '同步失败' },
];

export const columns: BasicColumn[] = [
  { title: '订单号', align: 'center', dataIndex: 'orderNo', width: 180 },
  { title: '订单类型', align: 'center', dataIndex: 'orderType', width: 110 },
  { title: '供应商', align: 'center', dataIndex: 'supplierName', width: 160 },
  {
    title: '订单状态',
    align: 'center',
    dataIndex: 'orderStatus',
    width: 100,
    customRender: ({ text }) => {
      const colorMap = { 待审核: 'default', 已审核: 'blue', 已下达: 'green', 已驳回: 'red' } as Record<string, string>;
      return h(Tag, { color: colorMap[text] || 'default' }, () => text);
    },
  },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 110,
    customRender: ({ text, record }) => {
      const colorMap = { 待同步: 'default', 同步中: 'processing', 同步成功: 'green', 同步失败: 'red' } as Record<string, string>;
      const tag = h(Tag, { color: colorMap[text] || 'default' }, () => text);
      // 同步失败时悬浮展示失败原因，让人一眼看到为什么
      if (text === '同步失败' && record.lastSyncError) {
        return h(Tooltip, { title: record.lastSyncError }, () => tag);
      }
      return tag;
    },
  },
  { title: '重试次数', align: 'center', dataIndex: 'retryCount', width: 90 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 160 },
  { title: '下达时间', align: 'center', dataIndex: 'dispatchTime', width: 160 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '订单号', field: 'orderNo', component: 'JInput', colProps: { span: 6 } },
  {
    label: '订单类型',
    field: 'orderType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: ORDER_TYPE_OPTIONS, allowClear: true, placeholder: '请选择订单类型' },
  },
  {
    label: '订单状态',
    field: 'orderStatus',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: ORDER_STATUS_OPTIONS, allowClear: true, placeholder: '请选择订单状态' },
  },
  {
    label: '同步状态',
    field: 'syncStatus',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: SYNC_STATUS_OPTIONS, allowClear: true, placeholder: '请选择同步状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '订单号',
    field: 'orderNo',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入订单号!' },
        {
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkOrderNo({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '订单号已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  {
    label: '订单类型',
    field: 'orderType',
    component: 'Select',
    required: true,
    componentProps: { options: ORDER_TYPE_OPTIONS, placeholder: '请选择订单类型' },
  },
  {
    label: '供应商',
    field: 'supplierId',
    component: 'ApiSelect',
    componentProps: {
      api: listAllEnabled,
      labelField: 'supplierName',
      valueField: 'id',
      placeholder: '请选择供应商',
      showSearch: true,
      optionFilterProp: 'label',
    },
  },
  { label: '备注', field: 'remark', component: 'InputTextArea' },
];

/** 审核弹窗表单（通过 / 驳回 + 意见） */
export const auditFormSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '审核结果',
    field: 'pass',
    component: 'RadioButtonGroup',
    defaultValue: true,
    required: true,
    componentProps: {
      options: [
        { label: '审核通过', value: true },
        { label: '驳回', value: false },
      ],
    },
  },
  {
    label: '审核意见',
    field: 'auditRemark',
    component: 'InputTextArea',
    dynamicRules: ({ values }) => {
      // 驳回时意见必填（后端也会二次强制）
      return values.pass === false ? [{ required: true, message: '驳回时必须填写驳回原因!' }] : [];
    },
  },
];
```

并在文件顶部补上 `h`、`Tag`、`Tooltip` 与 `listAllEnabled` 的 import：

```ts
import { h } from 'vue';
import { Tag, Tooltip } from 'ant-design-vue';
import { listAllEnabled } from '../supplier/supplier.api';
```

- [ ] **Step 3: 创建 `components/OrderModal.vue`**

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="640px">
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" setup>
  import { ref, computed, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form/index';
  import { formSchema } from '../order.data';
  import { saveOrUpdateOrder } from '../order.api';

  const emit = defineEmits(['success', 'register']);
  const isUpdate = ref(true);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate)) {
      setFieldsValue({ ...data.record });
    }
  });

  const title = computed(() => (unref(isUpdate) ? '编辑订单' : '新增订单'));

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await saveOrUpdateOrder(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 4: 创建 `components/AuditModal.vue`**

（审核不能用同一个 Modal 复用，表单 schema 不同，单独一个组件更清晰。）

```vue
<template>
  <BasicModal v-bind="$attrs" @register="registerModal" title="订单审核" @ok="handleSubmit" width="560px">
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" setup>
  import { useModalInner } from '/@/components/Modal';
  import { BasicModal } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form/index';
  import { auditFormSchema } from '../order.data';
  import { auditOrder } from '../order.api';

  const emit = defineEmits(['success', 'register']);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: auditFormSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    resetFields();
    setModalProps({ confirmLoading: false });
    setFieldsValue({ id: data?.record?.id, pass: true });
  });

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      await auditOrder(values);
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
```

- [ ] **Step 5: 创建 `index.vue`**

```vue
<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:order:add'" @click="handleCreate">
          新增
        </a-button>
        <a-dropdown v-if="selectedRowKeys.length > 0">
          <template #overlay>
            <a-menu>
              <a-menu-item key="1" @click="batchHandleDelete">
                <Icon icon="ant-design:delete-outlined" />
                批量删除
              </a-menu-item>
            </a-menu>
          </template>
          <a-button v-auth="'wms:order:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <OrderModal @register="registerModal" @success="handleSuccess" />
    <AuditModal @register="registerAuditModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-order" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import OrderModal from './components/OrderModal.vue';
  import AuditModal from './components/AuditModal.vue';
  import { columns, searchFormSchema } from './order.data';
  import { list, deleteOrder, batchDeleteOrder, dispatchOrder, resyncOrder } from './order.api';

  const { prefixCls } = useDesign('wms-order');
  const { createMessage } = useMessage();

  const [registerModal, { openModal }] = useModal();
  const [registerAuditModal, { openModal: openAuditModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 260 },
    },
  });

  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  function handleAudit(record) {
    openAuditModal(true, { record });
  }

  /** 下达：成功与否看返回的 synced 字段——下达本身成功但同步可能失败 */
  async function handleDispatch(record) {
    const res: any = await dispatchOrder({ id: record.id });
    if (res && res.synced === false) {
      createMessage.warning(
        `已下达，但同步 ERP/MES 失败（已重试 ${res.retryCount} 次）：${res.syncError}。可在列表中重新同步。`,
        8
      );
    } else if (res && res.synced === true) {
      createMessage.success('下达成功，已同步 ERP/MES');
    } else {
      createMessage.info('该订单正在被其它操作处理，本次未重复同步');
    }
    reload();
  }

  async function handleResync(record) {
    const res: any = await resyncOrder({ id: record.id });
    if (res && res.synced === true) {
      createMessage.success('同步成功');
    } else {
      createMessage.warning(`同步仍失败（已重试 ${res?.retryCount} 次）：${res?.syncError}`, 8);
    }
    reload();
  }

  async function handleDelete(record) {
    await deleteOrder({ id: record.id }, handleSuccess);
  }

  async function batchHandleDelete() {
    const ids = selectedRowKeys.value.filter((key) => !String(key).includes('loadChild'));
    if (ids.length === 0) {
      return;
    }
    await batchDeleteOrder({ ids: ids.join(',') }, handleSuccess);
  }

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function getTableAction(record) {
    const editable = record.orderStatus === '待审核' || record.orderStatus === '已驳回';
    const resyncable =
      record.orderStatus === '已下达' && (record.syncStatus === '同步失败' || record.syncStatus === '待同步');
    return [
      {
        label: '编辑',
        auth: 'wms:order:edit',
        ifShow: editable,
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '审核',
        auth: 'wms:order:audit',
        ifShow: record.orderStatus === '待审核',
        onClick: handleAudit.bind(null, record),
      },
      {
        label: '下达',
        auth: 'wms:order:dispatch',
        ifShow: record.orderStatus === '已审核',
        popConfirm: {
          title: '确定下达该订单吗? 下达后将同步至 ERP/MES。',
          confirm: handleDispatch.bind(null, record),
        },
      },
      {
        label: '重新同步',
        auth: 'wms:order:resync',
        ifShow: resyncable,
        onClick: handleResync.bind(null, record),
      },
      {
        label: '删除',
        auth: 'wms:order:delete',
        ifShow: editable,
        popConfirm: {
          title: '确定删除吗?',
          confirm: handleDelete.bind(null, record),
        },
      },
    ];
  }
</script>
```

- [ ] **Step 6: 提交**

```bash
cd D:/yongxin-WMS/JeecgBoot
git add jeecgboot-vue3/src/views/wms/order/
git commit -m "feat(wms): BD-002 订单管理前端页（按类型/状态/同步状态筛选）"
```

---

### Task 12: 端到端验收取证

**Files:** 无新建，只做验证与如实记录。

- [ ] **Step 1: 全量后端构建（含测试）**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot
bash /d/tools/m.sh -P dev clean package -DskipTests=false
```

Expected: `BUILD SUCCESS`。**把每个 WMS 测试类的 `Tests run: N, Failures: 0, Errors: 0` 原样记录下来。**
若失败，原样贴出失败输出再定位——不要跳过。

- [ ] **Step 2: 前端构建（含 vue-tsc 类型检查）**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecgboot-vue3
pnpm build
```

Expected: 构建成功，`dist/` 产出。若 `vue-tsc` 报类型错误，逐条修（常见：`order.data.ts` 里 `h`/`Tag`/`Tooltip`/`listAllEnabled` 的 import 缺失）。

- [ ] **Step 3: 启动应用，验证 Flyway 与任务注册**

```bash
cd D:/yongxin-WMS/JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start
bash /d/tools/m.sh -P dev spring-boot:run > /tmp/boot-e2e.log 2>&1
```

另一个终端：

```bash
# 1) Flyway 未报错
grep -c "flyway执行sql脚本失败" /tmp/boot-e2e.log        # 期望 0
# 2) 迁移执行过
grep -c "数据库版本自动升级" /tmp/boot-e2e.log            # 期望 >=1
# 3) 补偿任务被自注册（这是「全新环境自愈开箱即用」的证据）
grep -c "补偿任务.*注册成功" /tmp/boot-e2e.log            # 期望 >=1
```

三项都要满足。若 (3) 为 0，查是否被 `wms.sync.job.enabled=false` 关掉，或看日志里的注册 WARN。

- [ ] **Step 4: 登录取 token**

```bash
# 图形验证码先取一次（dev 环境通常校验码固定或可跳过，若失败改用下方说明）
curl -s -X POST 'http://localhost:8080/jeecg-boot/sys/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456","captcha":"","checkKey":""}' | tee /tmp/login.json
```

- 若登录被验证码拦住：改为**点浏览器界面**操作（`pnpm dev` 起前端，端口 3100）并用浏览器 DevTools 复制 token；或直接把下述接口验证放到浏览器里点按完成。
- 取到 token 后：`TOKEN=$(python -c "import json;print(json.load(open('/tmp/login.json'))['result']['token'])")`

- [ ] **Step 5: 供应商唯一性取证**

```bash
H="X-Access-Token: $TOKEN"
# 新增
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/supplier/add' -H "$H" -H 'Content-Type: application/json' \
  -d '{"supplierCode":"SUP001","supplierName":"永鑫五金","contact":"张三","phone":"13800000000","supplyScope":"原材料"}' | tee /tmp/s1.json
# 重复提交 → 必须提示编码已存在
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/supplier/add' -H "$H" -H 'Content-Type: application/json' \
  -d '{"supplierCode":"SUP001","supplierName":"另一家"}' | tee /tmp/s2.json
```

Expected: `/tmp/s1.json` 的 `success=true`；`/tmp/s2.json` 的 `success=false` 且 `message` 含「编码已存在」。

- [ ] **Step 6: 订单创建 → 审核 → 下达 取证**

```bash
H="X-Access-Token: $TOKEN"
# 创建（记录返回的 id）
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/add' -H "$H" -H 'Content-Type: application/json' \
  -d '{"orderNo":"PO20261014001","orderType":"采购订单","supplierId":"<上一步供应商id>"}' | tee /tmp/o1.json
# 重复单号 → 必须提示订单号已存在
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/add' -H "$H" -H 'Content-Type: application/json' \
  -d '{"orderNo":"PO20261014001","orderType":"采购订单"}' | tee /tmp/o2.json
```

Expected: `/tmp/o1.json` `success=true`；`/tmp/o2.json` `success=false` 且含「订单号已存在」。

审核通过：

```bash
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/audit' -H "$H" -H 'Content-Type: application/json' \
  -d '{"id":"<订单id>","pass":true}' | tee /tmp/a1.json
```

再建一单并驳回，验证驳回原因必填：

```bash
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/audit' -H "$H" -H 'Content-Type: application/json' \
  -d '{"id":"<另一订单id>","pass":false}' | tee /tmp/a2.json     # 期望 success=false「驳回时必须填写驳回原因」
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/audit' -H "$H" -H 'Content-Type: application/json' \
  -d '{"id":"<另一订单id>","pass":false,"auditRemark":"价格超预算"}' | tee /tmp/a3.json   # 期望 success=true
```

下达（未配 `wms.sync.erp.url` → 同步必失败，返回 `synced=false, retryCount=3`）：

```bash
curl -s -X POST 'http://localhost:8080/jeecg-boot/wms/order/dispatch?id=<订单id>' -H "$H" | tee /tmp/d1.json
```

Expected: `success=true`，`result.synced=false`，`result.retryCount=3`，`result.syncError` 含「未配置」。

- [ ] **Step 7: 验收标准②取证 —— 同步失败日志与落库**

日志：

```bash
grep -n "同步 ERP/MES 失败" /tmp/boot-e2e.log | tail -3
```

Expected: 出现 `ERROR` 行，含订单号 `PO20261014001`、订单类型、重试次数 3、失败原因。**把这一行原样贴进交付报告。**

落库（通过列表接口读回，无需 SQL 客户端）：

```bash
curl -s 'http://localhost:8080/jeecg-boot/wms/order/list?orderNo=PO20261014001' -H "$H" | tee /tmp/q1.json
```

Expected: `result.records[0]` 满足 `orderStatus=已下达`、`syncStatus=同步失败`、`retryCount=3`、`lastSyncError` 非空且含「未配置」。

- [ ] **Step 8: 自愈取证**

（a）僵死回收 —— 先手工造一条僵死数据（这是唯一需要写库的一步；无 SQL 客户端时改用「立即执行」前先把 `last_sync_time` 通过业务手段置旧，若确实无法造数，则记录为「未验证」并说明原因，**不得假装验证过**）：

到「**系统监控** → 定时任务」找到「WMS订单同步失败补偿任务」，点该行的「**更多**」→**立即执行**，然后：

```bash
grep -n "补偿任务本轮结束" /tmp/boot-e2e.log | tail -3
```

Expected: 出现本轮汇总行（候选/认领/成功/失败 四个数）。

（b）周期触发 —— 等 5 分钟：

```bash
grep -c "补偿任务本轮" /tmp/boot-e2e.log     # 期望随时间增长
```

- [ ] **Step 9: 菜单取证（浏览器）**

`pnpm dev` 起前端 → 登录 → 左侧应见「**基础管理**」目录（原「物料管理」已改名），其下三项：物料与分类 / 供应商管理 / 订单管理。点进两个新页面，确认列表、搜索（含**订单类型**筛选）、行操作按状态显隐正常。

- [ ] **Step 10: 汇总交付报告**

在 `docs/superpowers/plans/` 下写 `2026-10-14-yx-wms-bd-002-验收报告.md`，逐条列出：

| 验收标准 | 证据 | 结论 |
| --- | --- | --- |
| 订单能正常创建、审核、下达 | Step 6 的 4 个响应 JSON + Step 1 的测试计数 | 待填 |
| 同步失败时日志中有明确记录 | Step 7 的 ERROR 日志原文 + `/tmp/q1.json` 的落库字段 | 待填 |
| 同步失败可自愈（追加需求） | Step 3 的注册日志 + Step 8 的汇总行 | 待填 |

**如实填写**：跑不通的写「未通过」并附原始输出；无法验证的写「未验证」并说明原因。

---

## 自检记录

**1. Spec 覆盖**

| Spec 章节 | 对应任务 |
| --- | --- |
| §4.1/4.2 数据模型（含 `同步中`） | Task 1 |
| §5.1/5.2 状态机与守卫 | Task 3（审核/编辑/删除守卫）、Task 6（下达/重同步守卫） |
| §5.3 审核语义（含驳回回待审核） | Task 3 |
| §6.1 同步分层边界 | Task 4（Gateway）+ Task 5（编排） |
| §6.2 重试与告警 + announce 防轰炸 | Task 5 |
| §6.3 事务边界与并发认领 | Task 6 |
| §6.4 HTTP 实现（未配置即失败） | Task 4 |
| §6.5 定时补偿任务 + 自注册器 | Task 8 |
| §6.6 接口清单 | Task 2（供应商）、Task 7（订单） |
| §7 前端两页 | Task 10、Task 11 |
| §8 Flyway 与菜单改名 | Task 1、Task 9 |
| §9 依赖变更 | Task 8 Step 1 |
| §10 验证与验收 | Task 12 |
| §11 遗留风险 | 已在计划中体现（配置开关、批量上限、认领 SQL 靠 E2E 验证） |
| §12 交付物清单 | 文件结构表 |

无遗漏。

**2. 占位符扫描**：无 TBD/TODO；所有代码步骤均给出可直接落地的完整代码或明确的修正指令。

**3. 类型一致性**：`syncOrder(WmsOrder, boolean)` 在 Task 5 定义、Task 6 与 Task 8 消费，签名一致；`WmsOrderSyncAttempt`（`sync/` 顶层类）的 `isSuccess()/getErrorMessage()/getRetryCount()` 与全参构造器 `(boolean, String, int)` 在各任务与测试中用法一致；`claimForSync(String, Date)` 与 XML 的 `#{id}/#{staleBefore}` 对应；`selectSyncCandidates(Date, int)` 与 XML 的 `#{staleBefore}/#{limit}` 对应；落库只有 `IWmsOrderService.persistSyncResult` 一处实现，Task 6 与 Task 8 共用；`WmsOrderSyncOutcomeVO` 的 `synced/syncError/retryCount` 在控制器、前端 `handleDispatch` 中字段名一致。

**4. Review Focus 落实**：#1→Task 4 Step 1；#2→Task 2 Step 1、Task 3 Step 1；#3→Task 6 Step 1、Task 8 Step 2；#4→Task 5 Step 1；#5→Task 3 Step 1。
