# YX-WMS-BD-002 供应商与生产订单管理 — 设计说明

- 日期：2026-10-14
- 蓝图流程：`YX-WMS-BD-002` 供应商与生产订单管理流程
- 落点模块：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`
- 状态：待评审

---

## 1. 目标

在永鑫 WMS 运行时中，让管理员能够：

1. 维护供应商信息（含编码唯一性校验）；
2. 创建公司订单 / 采购订单 / 委外订单（含订单号唯一性校验）；
3. 对订单执行审核（通过 / 驳回）与下达；
4. 下达后同步订单至 ERP/MES；同步失败自动重试 3 次，仍失败则告警并记录日志；
5. 按类型、状态、供应商、订单号查询订单。

## 2. 需求来源

权威拓扑取自 `wms-blueprint/mmd.json` 的 `YX-WMS-BD-002`，它比口头需求多出三条硬约束：

| 蓝图节点 | 约束 |
| --- | --- |
| `S1` 校验供应商编码唯一性 → `X1` 编码重复提示 | 供应商编码唯一 |
| `S2` 校验订单号唯一性 → `X2` 订单号重复提示 | 订单号唯一 |
| `S4` 状态置为已下达 → `E1` 同步订单至 ERP/MES → `X3` 同步失败重试并告警 | 下达触发同步；失败重试 + 告警 |

### 2.1 对蓝图的两处刻意偏离（已与需求方确认）

1. **状态机三态化**：蓝图 `A5 审核通过` 直接连到 `S4 状态置为已下达`（同一动作），即蓝图只有「待审核 → 已下达」两态；而验收标准要求「创建、审核、下达」三者各自可验。故拆为 **待审核 → 已审核 → 已下达**。
2. **新增驳回**：蓝图异常处理泳道原本无驳回分支。按需求方要求补入「审核驳回」→ 状态 `已驳回`，且已驳回订单可编辑后重新提交审核。

## 3. 范围

### 3.1 本期实现

- `wms_supplier` / `wms_order` 建表（Flyway）
- 供应商 CRUD、订单创建 / 审核（通过 · 驳回）/ 下达 / 重新同步 / 查询
- ERP/MES 同步 SPI + 可配置 HTTP 实现 + 重试 3 次 + 告警
- 前端供应商管理页、订单管理页（支持按类型筛选）
- 菜单与按钮权限

### 3.2 本期明确不做（YAGNI，需要时另行提出）

| 不做项 | 原因 |
| --- | --- |
| Excel 导入 / 导出 | 需求未提出；`material` 有但非必需 |
| 订单明细行 / 物料子表 | 给出的表设计中无子表 |
| 蓝图 `S5` 订单关联入库/出库记录 | 入库/出库表属其它流程，当前不存在 |
| 审批流引擎（BPM）集成 | 审核为单级状态流转，无需工作流引擎 |
| `sys_announcement` 之外的外部告警通道（钉钉/邮件） | 站内消息 + ERROR 日志已满足验收 |

## 4. 数据模型

### 4.1 `wms_supplier`

| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | varchar(32) | 主键，`IdType.ASSIGN_ID` |
| `supplier_code` | varchar(64) | NOT NULL，唯一索引 `uk_wms_supplier_code` |
| `supplier_name` | varchar(100) | NOT NULL，索引 `idx_wms_supplier_name` |
| `contact` | varchar(50) | 联系人 |
| `phone` | varchar(30) | 联系电话 |
| `supply_scope` | varchar(200) | 供货范围 |
| `status` | varchar(1) | NOT NULL DEFAULT '1'，1启用 0停用 |
| `del_flag` | tinyint(1) | NOT NULL DEFAULT 0，`@TableLogic` |
| `create_by` / `create_time` / `update_by` / `update_time` | | 审计四列（`JeecgEntity` 提供） |

### 4.2 `wms_order`

| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | varchar(32) | 主键 |
| `order_no` | varchar(64) | NOT NULL，**唯一索引 `uk_wms_order_no`** |
| `order_type` | varchar(20) | NOT NULL，公司订单 / 采购订单 / 委外订单（存中文字面量） |
| `supplier_id` | varchar(32) | 指向 `wms_supplier.id`，索引 `idx_wms_order_supplier` |
| `order_status` | varchar(20) | NOT NULL DEFAULT '待审核'，待审核 / 已审核 / 已下达 / 已驳回 |
| `audit_by` | varchar(50) | 审核人 |
| `audit_time` | datetime | 审核时间 |
| `audit_remark` | varchar(500) | 审核意见（驳回时必填） |
| `dispatch_by` | varchar(50) | 下达人 |
| `dispatch_time` | datetime | 下达时间 |
| `sync_status` | varchar(20) | NOT NULL DEFAULT '待同步'，待同步 / 同步成功 / 同步失败 |
| `retry_count` | int | NOT NULL DEFAULT 0，**最近一次同步尝试中实际发生的重试次数** |
| `last_sync_error` | varchar(500) | 最后一次同步失败原因（验收取证点） |
| `last_sync_time` | datetime | 最后一次同步尝试时间 |
| `remark` | varchar(500) | 备注 |
| `del_flag` + 审计四列 | | 同 4.1 |

复合索引 `idx_wms_order_type_status(order_type, order_status)` 支撑「按类型筛选」。

> `order_type` / `order_status` / `sync_status` 采用中文字面量存储，与既有 `wms_material_category.category_type`（半成品/成品/辅料）做法一致，避免引入数据字典这一额外配置依赖。

## 5. 状态机与守卫

### 5.1 流转

```
                        ┌── 审核通过 ──▶ 已审核 ── 下达 ──▶ 已下达 ──▶ 同步 ERP/MES
创建 ──▶ 待审核 ────────┤                                        │
                        └── 审核驳回 ──▶ 已驳回 ── 编辑保存 ──┐   ├─ 成功 ──▶ 同步成功
                                          ▲                   │   └─ 失败 ──▶ 同步失败 ──[重新同步]─┐
                                          └─── 回到 待审核 ◀────┘                              │
                                                                        ◀────────────────────┘
```

### 5.2 守卫（后端强制，非仅前端隐藏按钮）

| 动作 | 允许的前置状态 | 违反时报错 |
| --- | --- | --- |
| 编辑保存 | 待审核 / 已驳回 | 仅待审核或已驳回状态的订单可编辑 |
| 审核 | 待审核 | 仅待审核状态的订单可审核 |
| 下达 | 已审核 | 仅已审核状态的订单可下达 |
| 重新同步 | 已下达 且 `sync_status=同步失败` | 仅同步失败的订单可重新同步 |
| 删除 | 待审核 / 已驳回 | 仅待审核或已驳回状态的订单可删除 |

### 5.3 审核语义

- `pass = true` → `order_status = 已审核`，写 `audit_by` / `audit_time`，`audit_remark` 可空。
- `pass = false` → `order_status = 已驳回`，写 `audit_by` / `audit_time`，**`audit_remark` 必填**（为空则报错「驳回时必须填写驳回原因」）。
- 已驳回订单被编辑保存后 → `order_status = 待审核`，并清空 `audit_by` / `audit_time` / `audit_remark`（重新进入待审，历史意见由操作日志承担）。

## 6. 后端结构

全部落在 `org.jeecg.modules.wms`，逐项对齐既有 `WmsMaterial` 范式。

```
entity/     WmsSupplier.java
            WmsOrder.java                  （含 @TableField(exist=false) supplierName，服务回填）
dto/        WmsOrderAuditDTO.java          （id, pass, auditRemark）
vo/         WmsOrderSyncOutcomeVO.java     （synced, syncError, retryCount）—— 下达/重同步的响应体
mapper/     WmsSupplierMapper.java  WmsOrderMapper.java
service/    IWmsSupplierService.java        impl/WmsSupplierServiceImpl.java
            IWmsOrderService.java           impl/WmsOrderServiceImpl.java
            IWmsOrderSyncService.java       impl/WmsOrderSyncServiceImpl.java    ← 重试编排
sync/       WmsOrderSyncGateway.java        （SPI 接口）
            WmsOrderSyncResult.java         （单次尝试结果：success, errorMessage）
            HttpWmsOrderSyncGateway.java    （可配置 HTTP 实现）
controller/ WmsSupplierController.java  WmsOrderController.java
constant/   WmsConstants.java               （追加工单类型/状态/同步常量）
```

### 6.1 同步的分层边界（本期唯一真架构决策）

刻意拆成两层，互不知道对方内部：

| 层 | 职责 | 不知道什么 |
| --- | --- | --- |
| `WmsOrderSyncGateway`（SPI） | **怎么和 ERP/MES 说话**：一次调用，返回成功/失败 | 不知道重试、退避、告警、落库 |
| `IWmsOrderSyncService` | **失败怎么重试、失败后怎么告警**：编排重试与告警 | 不知道 HTTP、报文格式、ERP 地址 |

未来接真实 ERP/MES，**只替换 `HttpWmsOrderSyncGateway` 一个实现类**。

### 6.2 重试与告警

`WmsOrderSyncServiceImpl.syncOrder(WmsOrder order)`：

- 最多尝试 **4 次 = 首次 1 次 + 重试 3 次**（`WmsConstants.SYNC_MAX_RETRY = 3`）。
- 退避 1s / 2s / 4s（最坏阻塞约 7 秒，需求方已确认接受）。
- **纯编排，不写库**——把结果交给订单服务落库，保证「谁拥有数据谁写」。
- 成功后 `log.info`，返回 `retryCount = 实际重试次数`。
- 4 次全失败后依次执行：
  1. `log.error` 打全上下文：订单号、订单类型、供应商ID、重试次数、失败原因；
  2. 调用 `ISysBaseAPI.sendSysAnnouncement(MessageDTO)` 向管理员发站内消息；**用 try/catch 包住**，避免告警自身失败反过来掩盖同步失败；
  3. 返回失败结果，由订单服务落 `sync_status=同步失败`、`retry_count=3`、`last_sync_error`、`last_sync_time`。

告警参数：`fromUser = "system"`，`title = "WMS订单同步失败告警"`，`content` 含订单号/类型/重试次数/失败原因。

### 6.3 事务边界（关键）

下达**不能**是单个长事务，否则会持锁 7 秒：

1. 【事务内】校验守卫 → 更新 `order_status=已下达`、`dispatch_by/time`、`sync_status=待同步` → 提交；
2. 【事务外】执行同步重试（可能耗时约 7 秒）；
3. 【单条更新】写回同步结果字段。

进程若在步骤 2 中途崩溃，订单停留在 `待同步`，可由「重新同步」补偿。

> 注：`sync_status=待同步` 且非失败态的订单不在「重新同步」的允许前置状态内，此为已知遗留（见 §11）。

### 6.4 HTTP 实现

`HttpWmsOrderSyncGateway`：

- 通过 `@Value("${wms.sync.erp.url:}")` 读取地址，**默认空**（无需修改 `application-*.yml`）。
- 地址为空 → 直接返回失败「未配置 ERP/MES 同步地址（wms.sync.erp.url）」。**因此未接真实系统时，重试与告警链路开箱即可跑通、可验收。**
- 已配置 → POST JSON（`orderNo`、`orderType`、`supplierId`、`supplierCode`、`supplierName`、`orderStatus`、`auditTime`、`dispatchTime`），带超时。
- HTTP 非 2xx → 失败（含状态码与响应体）；抛异常 → 失败（含异常消息）。

### 6.5 接口清单

**`WmsSupplierController`** `@RequestMapping("/wms/supplier")`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/list` | 分页；支持 `supplierCode` / `supplierName` / `status` |
| GET | `/queryById` | `id` |
| GET | `/checkCode` | `code`、`id`(可空) → 编码唯一性（约束①） |
| GET | `/listAll` | 启用中供应商的精简列表（id/code/name），供订单页下拉 |
| POST | `/add` | 新增 |
| PUT\|POST | `/edit` | 编辑 |
| POST | `/disable` | 停用 |
| DELETE | `/delete` | 逻辑删除 |
| DELETE | `/deleteBatch` | 批量逻辑删除 |

**`WmsOrderController`** `@RequestMapping("/wms/order")`

| 方法 | 路径 | 入参 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `orderNo` `orderType` `orderStatus` `supplierId` `createTime_begin` `createTime_end` + 分页 | 分页查询，含按类型筛选 |
| GET | `/queryById` | `id` | 详情（回填 supplierName） |
| GET | `/checkOrderNo` | `code`、`id`(可空) | 订单号唯一性（约束②） |
| POST | `/add` | `WmsOrder` | 创建 → `待审核` |
| PUT\|POST | `/edit` | `WmsOrder` | 编辑（待审核 / 已驳回） |
| POST | `/audit` | `WmsOrderAuditDTO` | 审核通过 / 驳回 |
| POST | `/dispatch` | `id` | 下达 + 触发同步，返回 `Result<WmsOrderSyncOutcomeVO>` |
| POST | `/resync` | `id` | 手动重新同步，返回 `Result<WmsOrderSyncOutcomeVO>` |
| DELETE | `/delete` | `id` | 逻辑删除 |
| DELETE | `/deleteBatch` | `ids` | 批量逻辑删除 |

**下达/重同步的返回语义**：下达本身成功即状态已落 `已下达`，同步可能失败。故返回 `Result.OK(WmsOrderSyncOutcomeVO)`，由前端依据 `synced` 字段决定提示为成功还是告警：

- `synced = true` → 成功提示；
- `synced = false` → **警告**提示，文案含 `syncError` 与 `retryCount`（如「已下达，但同步 ERP/MES 失败（已重试 3 次）：<原因>。可在列表中重新同步。」）。

这样不会让用户误判「下达也失败了」。

**`QueryGenerator` 注意点**（沿用 `WmsMaterialController` 的既有教训）：`WmsOrder.supplierName` 是非持久化字段（表无该列），`/list` 与 `/exportXls` 中必须**先置空**再交给 `QueryGenerator`，否则会拼出 `supplier_name` 条件导致 SQL 报错。

## 7. 前端

```
src/views/wms/supplier/  index.vue  supplier.api.ts  supplier.data.ts  components/SupplierModal.vue
src/views/wms/order/     index.vue  order.api.ts     order.data.ts     components/OrderModal.vue
```

均以 `useListPage` + `BasicTable` + `useModal` 组织，与 `views/wms/material/index.vue` 同构。

**供应商页**：表单字段 编码 / 名称 / 联系人 / 电话 / 供货范围 / 状态；编码带异步查重（照抄 `material.data.ts` 的 `dynamicRules` + `isTransformResponse: false` 写法）；搜索项 编码 / 名称 / 状态；行操作 编辑 / 停用 / 删除。

**订单页**：

- 搜索项：**订单号 / 订单类型 / 订单状态 / 供应商**（订单类型即「按类型筛选」）。
- 列：订单号、类型、供应商、订单状态、同步状态、重试次数、创建时间。
- 同步失败时 `last_sync_error` 以 `Tooltip` 悬浮展示，失败原因一眼可见。
- 行操作按状态动态显隐：

| 操作 | 显示条件 | 权限码 |
| --- | --- | --- |
| 编辑 | 待审核 / 已驳回 | `wms:order:edit` |
| 审核 | 待审核 | `wms:order:audit` |
| 下达 | 已审核 | `wms:order:dispatch` |
| 重新同步 | 已下达 且 同步失败 | `wms:order:resync` |
| 删除 | 待审核 / 已驳回 | `wms:order:delete` |

- 订单表单供应商用 `Select`（数据源 `/wms/supplier/listAll`），与「供应商是平铺列表」一致，不用 `TreeSelect`。
- 审核弹窗：通过 / 驳回单选 + 审核意见文本域，选「驳回」时意见必填（前端校验，后端二次强制）。
- 下单/重同步后依据 `synced` 字段用 `useMessage()` 分别走 `success` / `warning`。

## 8. Flyway 与菜单

新增 `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_3__wms_supplier_order.sql`，沿用既有幂等风格（`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`）。

内容：

1. 两张表 DDL + 索引；
2. **既有目录改名**：`UPDATE sys_permission SET name='基础管理' WHERE id='1910000000000000001' AND name='物料管理';`
   —— 带 `AND name='物料管理'` 条件，重复执行不会覆盖后续人工改名。
3. 新菜单（父节点 = `1910000000000000001`，即改名后的「基础管理」）：

| id | 名称 | url | component | sort |
| --- | --- | --- | --- | --- |
| `1910000000000000021` | 供应商管理 | `/wms/supplier` | `wms/supplier/index` | 2.00 |
| `1910000000000000022` | 订单管理 | `/wms/order` | `wms/order/index` | 3.00 |

（既有「物料与分类」菜单 sort=1.00，故新菜单排 2.00 / 3.00。）

4. 按钮权限（`menu_type=2`）：

| id | 名称 | perms | 父菜单 |
| --- | --- | --- | --- |
| `1910000000000000031` | 供应商-新增 | `wms:supplier:add` | `…021` |
| `1910000000000000032` | 供应商-编辑 | `wms:supplier:edit` | `…021` |
| `1910000000000000033` | 供应商-删除 | `wms:supplier:delete` | `…021` |
| `1910000000000000041` | 订单-新增 | `wms:order:add` | `…022` |
| `1910000000000000042` | 订单-编辑 | `wms:order:edit` | `…022` |
| `1910000000000000043` | 订单-删除 | `wms:order:delete` | `…022` |
| `1910000000000000044` | 订单-审核 | `wms:order:audit` | `…022` |
| `1910000000000000045` | 订单-下达 | `wms:order:dispatch` | `…022` |
| `1910000000000000046` | 订单-重新同步 | `wms:order:resync` | `…022` |

5. `sys_role_permission` 给 admin（`f6817f48af4fb3af11b9e8bf182f618b`）授权，id 段 `1920000000000000021` / `…022` / `…031~033` / `…041~046`。

## 9. 依赖变更

`jeecg-boot-module-wms/pom.xml` 追加：

```xml
<dependency>
    <groupId>org.jeecgframework.boot3</groupId>
    <artifactId>jeecg-system-local-api</artifactId>
</dependency>
```

**原因**：站内告警需要 `ISysBaseAPI`，而该接口不在 `jeecg-boot-base-core` 中（已核实 base-core 不依赖 `jeecg-system-api`）。

**依据**：`jeecg-boot-module-airag/pom.xml` 已有同样写法（本地单体 `local-api` 生效、微服务 `cloud-api` 注释保留），本模块照此办理。已核实 `jeecg-system-local-api` 仅依赖其父 pom，**不构成循环依赖**。

模块本身已在 `jeecg-boot-module/pom.xml` 的 `<modules>` 与 `jeecg-system-start/pom.xml` 的依赖中登记，**无需改动这两处**（已核实）。

## 10. 验证与验收证据

### 10.1 单元测试

根 pom 已以 `<scope>test</scope>` 声明 `spring-boot-starter-test`，**所有模块自动继承，无需改 pom**。测试放在 `jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/`（该目录当前不存在，新建）。不启 Spring 上下文，直接 new + mock `WmsOrderSyncGateway`，跑得快。

| 测试类 | 覆盖 |
| --- | --- |
| `WmsOrderServiceImplTest` | 状态机守卫全覆盖：合法流转（创建→审核→下达；创建→驳回→编辑→待审核）+ 每种非法前置状态被拒（断言抛出的报错文案） |
| `WmsOrderSyncServiceImplTest` | 重试编排：① 首次即成功 → `retryCount=0` 且 gateway 恰调 1 次；② 第 2 次才成功 → `retryCount=1`；③ 4 次全失败 → 返回失败、`retryCount=3`、gateway 恰调 4 次、`errorMessage` 为最后一次的失败原因 |

> **分层对应**（与 §6.2「编排不写库」一致）：`WmsOrderSyncServiceImplTest` 只断言**返回值**（成功与否、`retryCount`、失败原因文案、调用次数）——落库不是编排层的职责，故不在该测试中断言。`last_sync_error` / `retry_count` / `sync_status` 等**字段真正写进库**由 §10.3 步骤 6 的 SQL 查询取证。
>
> 诚实声明：单测覆盖的是**状态机与重试编排**这两处真逻辑。它们**不证明**前端页面渲染、`ISysBaseAPI` 告警真实投递、HTTP 报文格式被 ERP/MES 接受。这些的验证方式见 §10.3。

### 10.2 构建证据

将实际执行并**原样贴出输出**（失败也贴）：

```bash
mvn -P dev clean package                                   # 后端编译打包
mvn -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='Wms*Test'
cd jeecgboot-vue3 && pnpm build                            # 含 vue-tsc 类型检查
```

### 10.3 端到端手工验收（需 MySQL + Redis 的 dev 环境）

```bash
cd jeecg-module-system/jeecg-system-start && mvn spring-boot:run
```

1. 登录 → 左侧出现「基础管理」目录，其下「物料与分类 / 供应商管理 / 订单管理」。
2. 新增供应商 `SUP001`；重复提交 → 提示「编码已存在」。
3. 新增订单 `PO20261014001`，类型=采购订单 → 列表状态「待审核」；重复单号 → 提示「订单号已存在」。
4. 审核：选「通过」→ 状态「已审核」；另建一单选「驳回」→ 状态「已驳回」，列表可见驳回原因。
5. 对「已审核」订单点下达 → 状态「已下达」；因未配置 `wms.sync.erp.url`，同步必失败 → 弹出警告「已重试 3 次」。
6. **取证**：`SELECT order_no, sync_status, retry_count, last_sync_error, last_sync_time FROM wms_order WHERE order_no='PO20261014001';` → 应见 `同步失败 / 3 / 未配置 ERP/MES 同步地址… / <时间>`。
7. **取证**：后端控制台应见 `ERROR` 级日志，含订单号、类型、重试次数与失败原因 —— 即验收标准②「同步失败时日志中有明确记录」。
8. **取证**：`sys_announcement` 表新增一条标题为「WMS订单同步失败告警」的记录。

### 10.4 验收标准对照

| 验收标准 | 证据 |
| --- | --- |
| 订单能正常创建、审核、下达 | §10.2 构建通过 + §10.3 步骤 3–5 + `WmsOrderServiceImplTest` |
| 同步失败时日志中有明确记录 | §10.3 步骤 7 的 ERROR 日志 + 步骤 6 的落库字段 + `WmsOrderSyncServiceImplTest` 用例③ |

## 11. 已知遗留与风险

1. **`待同步` 僵死态**：进程在「已下达」提交后、同步结果写回前崩溃，订单停在 `sync_status=待同步`，且不在「重新同步」的允许前置状态内，需人工干预。未做定时补偿（需求方已选择同步阻塞式重试，未选定时任务）。如需要，后续补一个扫描 `sync_status IN ('待同步','同步失败')` 的 Quartz 任务即可。
2. **同步阻塞约 7 秒**：下达请求最坏被拖慢约 7 秒（退避 1/2/4s + 4 次调用耗时），需求方已确认接受。
3. **「重试 3 次」的解读**：按字面取「首次 1 次 + 重试 3 次 = 最多 4 次调用」。若需求方意图为「总计 3 次」，改 `WmsConstants.SYNC_MAX_RETRY` 一处常量即可。
4. **告警通道单一**：仅站内消息 + ERROR 日志，未接钉钉/邮件。
5. **订单无明细行**：当前订单只有头信息，无物料明细，与原材料/工序无关联。
6. **`order_type` / `order_status` 为中文字面量**：可读性好，但改文案即等于改数据。若后续要国际化或多语言，需迁移到数据字典。
