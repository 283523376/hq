# KC-004 库存查询与报表导出 设计方案

- 日期：2026-10-29
- 需求编号：KC-004
- 状态：待评审
- 关联：`V3.9.5_37__wms_inventory_warning.sql`（库存预警）、`WmsBatchTraceController`（批次追溯）

---

## 1. 背景

`wms_inventory` 表存的是「物料 + 库位 + 批次 + 容器」维度的当前数量，但**没有对外查询接口**：

- `WmsInventoryMapper` 只有出库内部用的 `selectAvailable` / `selectAvailableByWarehouse`（按物料 FIFO 取可用量），
  以及 `lockInventory` / `releaseLocked` / `deductLocked` 三个扣减方法。
- 前端 `src/views/wms/inventory/index.vue` 是**批次追溯**（输入单个批次号看库存与流转），
  不是「按多条件查库存台账」。

所以现在仓管员想看「某物料在哪些库位还剩下多少」只能去盘点单或直接查库；
财务月底要一份库存台账没有出口。

## 2. 目标与非目标

### 目标

1. **多条件组合查询**：物料（编码/名称）、批次号、库位编码、仓库，任意组合；分页返回。
2. **明细展示**：物料编码/名称/规格/单位、批次号、仓库编码/名称/状态、库位编码、料箱/托盘编码、
   库存数量、锁定数量、可用量、批次首次入库时间、库龄天数。
3. **无匹配记录给出提示**：接口返回空列表，页面显示「未找到匹配的库存记录」，不是空白或转圈。
4. **导出 Excel**：复用项目既有的 AutoPoi，**不引入 EasyExcel**。
5. **大批量异步导出 + 重导**：任务落库、后台线程池生成、任务列表里下载/重试。
6. **操作日志**：查询与导出各记一条 `@AutoLog`。

### 非目标（明确不做）

| 项 | 理由 |
| --- | --- |
| **历史时点结存**（如「9 月 30 日结存」） | 当前只有 `wms_inventory` 当前数量，没有库存流水表。**后续可能要，那要另建流水/快照表，是另一个量级的工程**，需单独立项 |
| CSV 格式 | xlsx 已满足；几十万行 xlsx 在 104 万行上限内。真需要时增量加，不影响本次结构 |
| 汇总页 / 透视 | 需求是明细台账；汇总用 Excel 自己的透视表 |
| 行级数据权限 | 有权限即可看全部库区。若财务要按仓隔离，另议 |
| 「在途库存」单列 | `2026-10-22-yx-wms-ck-004-transfer-design.md` §13 风险 #3 要求 KC-004 单列在途库存。该要求**已作废**：跨库调拨改由金蝶 ERP 承担，`wms.transfer.enable-cross-warehouse=false`，WMS 内不存在长期停留在暂存库位的在途状态（2026-10-29 确认）。暂存库位即便有残留库存也按普通库位计入台账 |
| 导出文件长期归档 | 7 天自动清理，符合「月底导一次」的节奏 |

### 关于「实时快照」的口径

导出的是**点导出那一刻的实时库存**。实现上在**一个只读事务**内完成整次导出，
MySQL `REPEATABLE READ` 会给该事务一个一致性快照，所以导出内容在事务开始那一刻是一致的，
不会因为导出过程中有入库/出库而出现「同一份文件里前后不自洽」。

任务记录 `snapshot_time` = 事务开始时间，会写进 Excel 表头，也会在页面上显示。

## 3. 数据来源

查询与导出都基于同一段 SQL，四表 join：

```
wms_inventory  i
  JOIN wms_material  m ON m.id = i.material_id  AND m.deleted_at = 0
  JOIN wms_location  l ON l.id = i.location_id  AND l.deleted_at = 0
  JOIN wms_warehouse w ON w.id = l.warehouse_id AND w.deleted_at = 0
```

用 `INNER JOIN` 与既有 `selectAvailable` 口径一致。**已知风险**：如果某库位/物料被逻辑删除但库存行还在，
这些行查不出来。这属于数据异常——删除守卫本就该拦住「删有库存的库位」，真出现了要用盘点/调拨修数据，
不应该靠报表掩盖。

### 过滤条件

| 参数 | 匹配方式 | 说明 |
| --- | --- | --- |
| `materialKeyword` | `m.material_code LIKE %x% OR m.material_name LIKE %x%` | 一个输入框同时搜编码和名称 |
| `batchNo` | `i.batch_no LIKE %x%` | 模糊，支持前缀 |
| `locationCode` | `l.location_code LIKE %x%` | 模糊 |
| `warehouseId` | 精确 | 下拉选仓库 |
| `onlyPositive` | `i.quantity <> 0` | **默认 true**，隐藏扣到 0 的历史行 |

**不按仓库启用状态过滤**：停用仓库（`wms_warehouse.status = 0`）里的库存照样进台账，
只在结果里带一列 `warehouseStatus`（启用/停用）。2026-10-29 确认口径——月结台账藏掉停用仓的存量会造成账实不符，
让财务自己看得见、自己处理更安全。实测当前库里有 3 个停用仓库、挂着 5 行库存。

**不含在途库存单列**：见 §2 非目标。

### 排序

`ORDER BY i.material_id, i.location_id, i.batch_no, i.container_code`

这四列**正好是 `wms_inventory` 唯一键 `uk_wms_inv_mat_loc_batch_container` 的列顺序**，所以：

- 排序天然唯一（唯一键保证），不用担心 keyset 游标撞行；
- 导出分批时 `WHERE (material_id, location_id, batch_no, container_code) > (上一批末行)` 是一次索引范围扫描，
  **不用 `OFFSET` 深翻页，也不用为排序做 filesort**；
- 同一物料的行走在一起，台账看着是分组的（组内按库位、批次、容器）。

代价是分组顺序按 `material_id`（UUID）而非物料编码字母序——**不按物料编码排序是有意的**：
`material_code` 在 `wms_material` 表上，跨表排序列无法被索引利用，几十万行会退化成全量 filesort。
财务若要按编码排序，在 Excel 里点一下列头即可。

页面查询与导出**用同一排序**，两处看到的数据顺序一致。

## 4. 数据库变更

新 Flyway 脚本 `V3.9.5_42__wms_inventory_ledger.sql`（当前最新是 `_41`）。

### 4.1 导出任务表 `wms_export_task`

做成**通用**导出任务表（不叫 `wms_inventory_export_task`），后续别的报表导出能直接复用。

```sql
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
  `expire_time`   datetime      DEFAULT NULL COMMENT '清理时点：任何终态（SUCCESS/FAILED）完成时写 finish_time+7天',
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
```

**不加 `deleted_at`**：任务是短生命周期的工作产物，7 天后**物理删除**（记录和文件一起删），
留着大量已失效的行只增加噪音。失效任务的行不该做逻辑删除语义。

### 4.2 库存表补索引

```sql
-- 批次号是高频过滤条件，现有唯一键 (material_id, location_id, batch_no, container_code)
-- 的第二列是 location_id，按 batch_no 单独过滤用不上
ALTER TABLE `wms_inventory` ADD INDEX `idx_wms_inv_batch` (`batch_no`) USING BTREE;
```

物料维度已有唯一键前缀 `(material_id, ...)` 覆盖，库位维度已有 `idx_wms_inv_location`，不重复加。
（脚本要按 `information_schema` 判断索引是否存在再建，与 `V3.9.5_37` 的写法一致，保证可重复执行。）

### 4.3 菜单与权限

顶尖菜单「库存查询」，`sort_no = 97`（现有：入库 91、出库 92/93、盘点 94、预警 95、批次追溯 96）。

| ID | 名称 | 类型 | perms | 说明 |
| --- | --- | --- | --- | --- |
| `1910000000000000701` | 库存查询 | 目录 `menu_type=0` | — | url `/wms/inventoryLedger`，redirect `/wms/inventoryLedger/list` |
| `1910000000000000702` | 库存台账 | 菜单 `menu_type=1` | — | component `wms/inventoryLedger/index` |
| `1910000000000000711` | 库存-查询 | 按钮 `menu_type=2` | `wms:inventory:ledger` | |
| `1910000000000000712` | 库存-导出 | 按钮 `menu_type=2` | `wms:inventory:export` | 含任务列表查看与下载 |

admin 角色授权，写法照抄 `V3.9.5_37__wms_inventory_warning.sql` 第 107-118 行。

### 4.4 清理定时任务

「导出任务清理」每天 03:00，抄 `WmsInventoryWarningScanJobRegistrar` 的自注册器模式：

- 常量 `WMS_EXPORT_TASK_CLEAN_JOB_ID`、`WMS_EXPORT_TASK_CLEAN_JOB_CRON`（`0 0 3 * * ?`）放 `WmsConstants`。
- 开关 `wms.export.clean.enabled`，**默认 true**；关闭时必须 `scheduler.deleteJob` 摘除（Quartz 是 JDBC 存储，
  只把开关改 false 不摘除，重启后照跑——这个坑 `WmsInventoryWarningScanJobRegistrar` 的注释里已经写明）。

## 5. 后端接口

统一前缀 `/wms/inventoryLedger`，全部返回 `Result<T>`。

### 5.1 分页查询

```
GET /wms/inventoryLedger/page
perms: wms:inventory:ledger
```

参数：`materialKeyword`、`batchNo`、`locationCode`、`warehouseId`、`onlyPositive`、
`pageNo`（默认 1）、`pageSize`（默认 20，**上限 200**）。

返回 `Result<IPage<WmsInventoryLedgerVO>>`：

```java
WmsInventoryLedgerVO {
    String id;                 // 行键
    String materialCode;
    String materialName;
    String spec;
    String unit;
    String batchNo;
    String warehouseCode;
    String warehouseName;
    String warehouseStatus;    // 1 启用 / 0 停用，台账里原样带出
    String locationCode;
    String containerCode;
    BigDecimal quantity;
    BigDecimal lockedQty;
    BigDecimal availableQty;   // SQL 里算 quantity - locked_qty
    Date firstInboundTime;
    Integer stockAgeDays;      // DATEDIFF(NOW(), first_inbound_time)
}
```

**空结果**：返回空 `records`，`total = 0`。前端据 `total === 0` 显示「未找到匹配的库存记录」，
不额外造一个 `empty` 标志位——分页语义里 `total=0` 就是无匹配。

`pageSize` 上限 200 是硬限制：这个接口是给人看的，不是给导出的；导出走异步任务。

### 5.2 创建导出任务

```
POST /wms/inventoryLedger/export  (body: 同查询条件，不含分页)
perms: wms:inventory:export
```

流程：
1. 校验并序列化查询条件 → `query_params`（JSON 带 `"v":1` 版本号，以后条件结构变了能识别旧任务）。
2. **单用户并发预检**：同一账号已有 `PENDING`/`RUNNING` 任务时直接拒绝，
   返回 `Result.error("你已有导出任务正在进行，请等待完成后再提交")`。
3. 落一条 `status=PENDING` 的任务，`create_by` = 当前登录账号。
4. 提交到导出线程池。
5. 立即返回 `Result.OK(taskId)`。

**提交被拒（队列满）必须把已落库的行收尾**：捕获 `RejectedExecutionException` 后立即把该行更新为
`FAILED`、`error_msg='导出任务队列已满，请稍后重试'`、`finish_time=now`、`expire_time=now+7d`，
再返回 `Result.error("导出任务较多，请稍后再试")`。**不能只返回错误就不管那一行**——
否则它会永远停在 `PENDING`，前端按「存在 PENDING 就轮询」的规则会一直转下去。

**没有条数预检**：几十万行要 `COUNT(*)` 一次全表，跟我们想避免的开销一样大，且不改变「必须异步」的结论。
改成并发限制（下面 6.1）。

### 5.3 我的导出任务列表

```
GET /wms/inventoryLedger/export/task/page?pageNo=&pageSize=
perms: wms:inventory:export
```

**强制** `create_by = 当前登录账号`，不接受前端传账号——「只有导出人自己看得到」在服务端落地，
不是靠前端过滤。前端传 `createBy` 参数一律忽略。

返回任务列表，含 `status`、`rowCount`、`createTime`、`finishTime`、`expireTime`、`errorMsg`。

### 5.4 下载

```
GET /wms/inventoryLedger/export/task/{id}/download
perms: wms:inventory:export
```

校验：任务存在 → `create_by` 等于当前账号 → 状态为 `SUCCESS` → 文件仍存在。
任一不满足返回 `Result.error(...)`，**文案逐字透出**（不让全局异常处理器加前缀，同项目既有约定）。

下载用 `FileDownloadUtils.downloadFile(response, storePath, fileName)`（`jeecg-boot-base-core` 已有），
不自己拼 `Content-Disposition`。

### 5.5 重试

```
POST /wms/inventoryLedger/export/task/{id}/retry
perms: wms:inventory:export
```

只允许 `FAILED` 状态；校验归属；重置为 `PENDING`、清空 `error_msg`/`finish_time`/`file_*`，重新提交线程池。

`PENDING` / `RUNNING` 状态下重试返回「任务正在处理中」；`SUCCESS` 返回「任务已完成，请直接下载」。

### 5.6 删除任务

```
DELETE /wms/inventoryLedger/export/task/{id}
perms: wms:inventory:export
```

校验归属后删记录 + 删文件。加这个是因为用户可能想立刻清掉自己导出错的台账，不必等 7 天。
`PENDING`/`RUNNING` 状态不允许删（任务正在写文件），提示「任务处理中，无法删除」。

## 6. 异步导出实现

### 6.1 线程池

用一个显式定义的 `ThreadPoolTaskExecutor` Bean，**不用 `@Async`**：

- 项目里**没有任何 `@EnableAsync`**，加它是个全局开关，影响面不可控。
- 显式 `executor.execute(runnable)` 行为可预测，线程池满时能自己接住异常给出友好提示。

参数：核心 2、最大 2、队列 20、`AbortPolicy`、线程名前缀 `wms-export-`、优雅关闭等待 60s。

**2 个并发**的理由：导出是 IO + CPU 混合的重活，几十万行跑起来会吃掉不少内存和连接；
并发开大只会互相拖慢，还挤占正常业务接口的数据库连接。

**单用户同时最多 1 个进行中任务**（见 5.2 第 2 步）。只有全局 2 并发是不够的：
一个用户连点 20 次就能占满队列，别人全被拒；20 个几十 MB 的文件也压盘。
按人限流之后，全局队列的 20 个槽位不会轻易被单个人吃光。

### 6.2 生成流程

```
PENDING → RUNNING
  ├─ 开只读事务（@Transactional(readOnly = true)）
  ├─ snapshot_time = 事务开始的当前时间
  ├─ AutoPoi: ExcelExportUtil.exportBigExcel(params, WmsInventoryLedgerExcelRow.class, server, ctx)
  │     server.selectListForExcelExport(ctx, page) 每批 5000 行，keyset 分页，返回空列表即结束
  ├─ workbook.write(临时文件 .part) → workbook.close() → SXSSF 的 dispose()
  ├─ 临时文件改名为最终文件名（同目录 rename，避免下载到半个文件）
  ├─ 行数守卫：累计超过 1048575 行时立即失败，不继续生成
  └─ SUCCESS：回写 file_name/file_size/row_count/finish_time、expire_time = finish_time + 7d
     FAILED ：回写 error_msg（截断 1000 字）、finish_time、expire_time = finish_time + 7d
```

**`expire_time` 对所有终态都要写**。只给 SUCCESS 写，失败任务就永远不过期、永远不被清理，
「我的导出任务」会无限累积。

**为什么用 `exportBigExcel` 而不是 `exportXls`**：`JeecgController.exportXls` 走的是
`NormalExcelConstants.DATA_LIST` 把整个 `List` 塞进内存，几十万行必 OOM。
`exportBigExcel(ExportParams, Class, IExcelExportServer, Object)` 是 AutoPoi 的分批接口
（已在 `autopoi-2.0.7.jar` 核实存在），配合 SXSSF 只保留窗口内的行在内存里。

**keyset 分页**：不用 `OFFSET`。几十万行时 `LIMIT 5000 OFFSET 350000` 会让 MySQL 从头扫 35 万行。
用 `WHERE (material_id, location_id, batch_no, container_code) > (上一批最后一行)` 的游标写法
（列顺序与唯一键一致，见 §3 排序），每批都是唯一索引上的范围扫描，总成本与总行数线性相关。

实现上 `IExcelExportServer` 的回调是顺序调用的，把「上一批最后一行」放在传入的 `Object queryParams`
包装对象里按批推进即可，不需要线程安全。

**行数统计**：在回调里累加，不额外 `COUNT(*)`。

**xlsx 上限兜底**：单个 sheet 物理上限 1048576 行；台账文件带 2 行标题 + 1 行表头，所以**数据行守卫是 1048573**，
超过即任务 `FAILED`，文案「导出行数超过 Excel 单表上限，请缩小查询条件后分批导出」。
**不拆多文件、不拆多 sheet**——任务表是单文件模型，拆文件会把「一个任务一个文件」的下载/清理逻辑全打散，
而这个量级（用户口径几十万行）离上限还有三倍余量，真撞上限说明查询条件本身有问题。

> **实现时的关键发现（2026-10-29 实测 30 万行导出）**：AutoPoi 的 `ExportParams.maxNum` 默认取
> `ExcelExportUtil.USE_SXSSF_LIMIT = 100000`，超过就**滚动到没有表头的新 sheet**（Sheet1/Sheet2...）继续写——
> 数据不丢（30 万行实测分布在 4 个 sheet、行数分毫不差），但财务拿到手的 Sheet2/Sheet3 没有列头，没法用。
> 所以 `buildExportParams` 必须**显式 `setMaxNum(1048576)`** 把滚动分页禁掉，让「数据行守卫 1048573」成为唯一约束。
> 守卫在取数回调里、先于 AutoPoi 的滚动判断执行，顺序是安全的。

### 6.3 一致性快照

整个生成过程包在一个 `@Transactional(readOnly = true)` 方法里。
注意 **`@Transactional` 只在跨 Bean 调用时生效**，所以执行逻辑要放在独立的 Service 里，
由线程池任务调它（不能在同一个类里 `this.xxx()` 自调用，那样注解会被绕过）。

### 6.4 重启后的孤儿任务

应用启动时，把 `status = RUNNING` 的任务置为 `FAILED`，`error_msg = '服务重启，任务中断，请重试'`，
`finish_time = now`、`expire_time = now + 7d`。
不做「断点续传」——几十万行重跑一次几分钟，续传的复杂度不值得。

`PENDING` 任务同样置 `FAILED`（它们只存在于那个已经没了的线程池队列里），同样补 `finish_time`/`expire_time`。

### 6.5 文件存放

配置项 `wms.export.dir`，默认 `${jeecg.path.upload}/wms-export`（dev 是 `/opt/upFiles/wms-export`）。

- 启动时 `Files.createDirectories`，目录建不出来要**在日志里报 ERROR**，不要等到用户点导出才发现。
- 文件名：`库存台账_yyyyMMddHHmmss_<taskId前8位>.xlsx`（对用户可读，且不冲突）。
- `file_path` 只存相对路径，不存绝对路径——换机器/换目录不用改数据。
- 清理时先删文件再删记录；文件已不存在也算清理成功（幂等）。

**多实例部署风险**：当前是单机运行，文件落在本地盘没问题。将来要上多实例，
需要把导出目录挂到共享存储，或改用对象存储（`jeecg.uploadType` 已经支持 minio/alioss）。
本次不做，写在这里备案。

### 6.6 后台线程里取不到登录人

项目**没有 `MetaObjectHandler`**，审计字段一律由 `WmsUtils.fillCreate/fillUpdate` 显式写入，
而这两个方法内部读的是 Shiro 的当前 Subject。

导出线程池里的线程**没有 Shiro Subject**，`WmsUtils.currentUsername()` 会返回空。
所以：**任务行的 `create_by` 必须在 Controller 里（请求线程内）就定好并随任务一起传下去**，
后台线程只负责改 `status`/`file_*`/`row_count`/`finish_time`，
更新时 `update_by` 用任务自己的 `create_by`，绝不在线程池里调 `SecurityUtils.getSubject()`。

## 7. 操作日志

用项目统一的 `@AutoLog`（14 个 controller 在用）：

| 位置 | value | operateType |
| --- | --- | --- |
| `GET /page` | `库存-台账查询` | 1（查询） |
| `POST /export` | `库存-台账导出` | 1 |

**查询也记日志这件事值得说一句**：仓管员一天可能查几十次，`sys_log` 会持续增长。
但需求明确要求「记录查询人」，而 `@AutoLog` 恰好就是记录操作人 + 参数 + IP 的标准途径，先按需求做。
如果后面发现 `sys_log` 增长过快，再把查询日志降级成只记 `pageNo=1`（一次查询行为记一条），
这个改动很小，不影响其它设计。

下载**不记**日志：它是同一个导出动作的后续，重复记只会让日志里出现一堆「下载」噪音。

## 8. 前端

新目录 `src/views/wms/inventoryLedger/`（`index.vue` + `inventoryLedger.api.ts` + `inventoryLedger.data.ts`），
写法对齐现有的 `views/wms/inventory/index.vue`：`a-card` + `a-table`，不用 `BasicTable`。

页面结构：

```
┌ 查询条件 ──────────────────────────────────────────────┐
│ 物料[____] 批次[____] 库位[____] 仓库[▾] □只看有库存  │
│ [查询] [重置]                    [导出 Excel]          │
└────────────────────────────────────────────────────────┘
┌ 库存明细（total 条） ──────────────────────────────────┐
│ 表格分页 20/50/100                                     │
│ 空态：「未找到匹配的库存记录」                          │
└────────────────────────────────────────────────────────┘
┌ 我的导出任务 ──────────────────────────────────────────┐
│ 状态 | 行数 | 提交时间 | 完成时间 | 过期时间 | 操作     │
│ 排队中/生成中：进度提示，5 秒轮询                        │
│ 成功：[下载] [删除]    失败：[重试] [查看原因]           │
└────────────────────────────────────────────────────────┘
```

要点：

- **按钮权限**用 `v-auth`：查询按钮 `wms:inventory:ledger`，导出/任务操作 `wms:inventory:export`。
- **仓库下拉包含停用仓**，选项文案带后缀（如 `XB01 线边柜一仓（停用）`）。
- **「仓库状态」列**用 tag 展示（启用绿 / 停用灰），停用行的数量不做特殊颜色处理——只做提示，不替财务判断。
- **轮询**：任务列表里存在 `PENDING`/`RUNNING` 时每 5 秒刷新一次，全部结束后停止。
  组件卸载时必须清掉定时器，否则切走页面还在后台打接口。
- **导出提示**：点导出后 `message.success('导出任务已提交，可在下方任务列表查看进度')`，不弹遮罩、
  不阻塞页面——这正是异步的意义。
- **过期提醒**：任务行上显示「过期时间」，过期后按钮置灰并提示「文件已过期（7 天）」。

## 9. 测试

### 单测（Mockito，与项目现有风格一致，不连库）

| 类 | 覆盖点 |
| --- | --- |
| `WmsInventoryLedgerQueryTest` | 条件组装：空条件不拼 where；`materialKeyword` 同时匹配编码与名称；`onlyPositive` 默认 true；`pageSize > 200` 被夹到 200 |
| `WmsExportTaskServiceTest` | 创建任务落 PENDING + `create_by` 取当前登录；**已有进行中任务时拒绝**；**队列满时把已落库的行置 FAILED 并写 expire_time**；任务列表强制按当前账号过滤（前端传别的账号无效） |
| `WmsExportTaskDownloadTest` | 非本人不可下载/重试；非 SUCCESS 不可下载；文件丢失时给明确文案 |
| `WmsExportTaskRetryTest` | 只允许 FAILED；PENDING/RUNNING/SUCCESS 各自的拒绝文案 |
| `WmsExportTaskRunnerTest` | 成功路径回写 row_count/expire_time；异常路径回写 FAILED + 截断的 error_msg + expire_time；keyset 游标在批间正确推进；回调返回空即终止；**超过 1048575 行立即失败且文案是上限提示** |
| `WmsExportTaskRecoveryTest` | 启动时 RUNNING/PENDING → FAILED，文案与 expire_time 正确 |
| `WmsExportTaskCleanJobTest` | 按 `expire_time < now` 删，**SUCCESS 与 FAILED 都要被清到**；文件不存在也删成功（幂等） |

### 手工验证（真机）

1. 造 30 万行库存数据（脚本或 SQL 复制），导出 → 看内存曲线不飙、文件行数正确。
2. 导出进行中同时做入库/出库，导出文件内数量自洽（快照语义生效）。
3. 用 A 账号导出，B 账号登录看不到该任务，直接调下载接口返回「无权访问」。
4. 导出一份，把 `expire_time` 手工改成昨天，跑清理 Job，确认记录与文件都没了。
5. 导出过程中重启后端，重启后任务变 FAILED 且能重试成功。

## 10. 风险

| 风险 | 应对 |
| --- | --- |
| 几十万行 join 查询慢 | 专用批次索引 + keyset 分页；导出本就是后台任务，慢不影响前台 |
| 长只读事务持有 undo log | 导出时长以分钟计，可接受；真出问题再降级为「按 create_time 上限近似快照」 |
| 导出目录不可写 | 启动时建目录 + ERROR 日志；任务失败文案里带路径，方便运维定位 |
| `sys_log` 因查询日志膨胀 | 见第 7 节，必要时降级到只记首次查询 |
| AutoPoi SXSSF 临时文件占盘 | `dispose()` 必须在 `finally` 里调用，否则临时文件堆积 |

## 11. 我的判断（供评审时重点看这几条）

1. **不做 CSV**，只出 xlsx——几十万行在 xlsx 上限内，CSV 的编码问题（Excel 打开乱码）反而更烦。
2. **任务表做成通用的 `wms_export_task`**，不是库存专用——后续预警报表、盘点报表都能复用。
3. **查询日志按需求记录**，即使 `sys_log` 会涨；降级方案写在第 7 节。
4. **不加 `deleted_at`** 到任务表，7 天物理删除。
5. **`onlyPositive` 默认 true**——扣到 0 的行不进台账，但保留开关。
6. **启动时 RUNNING/PENDING 一律 FAILED**，不做断点续传。
7. **导出并发上限 2，单用户同时 1 个**——只限全局不够，一个用户连点就能占满队列。
8. **`expire_time` 对所有终态都写**，清理 Job 一套 SQL 管到底，不按状态分叉。
9. **撞 xlsx 行数上限直接失败**，不拆文件、不拆 sheet。
10. **删除任务接口纳入本期**（前端原型里已有按钮，实现成本很低）。
11. **不做在途库存单列**——跨库调拨归金蝶，WMS 无在途状态（2026-10-29 用户确认）。
12. **停用仓的库存全部纳入台账，另加「仓库状态」列**——用户 2026-10-29 确认的口径。
