# 永鑫WMS 生产入库流程（YX-WMS-RK-002）设计

日期：2026-10-19
状态：已定稿（含 2026-10-19 二维码/载具口径修正、builder check 收口、范围项确认），待实施
需求来源：`wms-blueprint/flows.json` 的 `YX-WMS-RK-002`（生产入库流程·半成品/成品）
对接资料：`D:\资料\米塔机器人资料\RMS任务接口文档-V1.6.pdf`、`D:\资料\米塔机器人资料\米塔机器人概述.docx`

## 1. 意图与背景

生产完成后，操作员扫码工单，WMS 调出工单信息 → 录入良品/不良品数量 → 不良品直接写入废品仓库存 → 良品按产品类型分配目标库位并下发 WCS/RMS 任务 → 设备上架完成后 WMS 更新库存、绑定载具标识 → 同步 ERP/MES/WMS。

**分流规则（需求方拍板）**：

| 产品类型 | 载具 | 存储区 | 设备 | 绑定标识 | 任务接口 |
|---|---|---|---|---|---|
| 半成品 | 周转盘 | 立库（144 个库位已存在） | 堆垛机（WCS） | RFID（贴在周转盘上，一盘一码） | WCS 协议**待对方提供**，本期占位 |
| 成品 | 料箱 | 敏捷仓（604218 料箱） | SCR 机器人（米塔 RMS） | 二维码（贴在料箱上，一箱一码） | 按 RMS V1.6 文档实现 |

验收标准：半成品与成品两条链路都能走通；RMS 接口按 V1.6 文档真实可对接（未配置地址时走模拟受理，便于无设备环境验收）；四条异常提示逐字落地。

## 2. 目标 / 非目标 / 反目标

**目标**

1. 扫码工单 → 数量校验 → 不良品入废品仓 → 良品分配库位 → 下发任务 → 设备回执 → 更新库存 + 绑定载具标识 → 同步 ERP/MES/WMS 全链路。
2. 四条异常：工单不存在提示「工单号无效」；数量超范围提示「数量超出工单范围」；设备故障告警并转人工；同步失败自动重试并以告警兜底。
3. 前端提供扫码作业台 + 入库任务状态跟踪（5 秒轮询 + 手动刷新 + 详情抽屉）。

**非目标**

- 与真实 MES/ERP 的联调（地址、账号未知）——只预留配置项、报文格式与账号占位符，并在本文档记录。
- 堆垛机 WCS 的真实协议——只做可配置网关 + 未配置时模拟受理；回执接口为占位契约。
- RFID 读写器、二维码打印机的硬件集成——载具码由操作员扫码录入。
- 按单件（逐 PCS）追溯——**一件载具一个码**（料箱/周转盘），不是逐产品生成二维码。
- 出库、盘点、移库、不良品后续处置（RK-005）。
- 载具主数据管理（料箱/周转盘台账）——载具码由 RMS/现场管理，WMS 只记录使用关系。

**反目标**

- 「界面显示入库成功但库存没写」——库存写入与状态流转必须同事务；外部调用不得放在事务内。
- 重复回执导致库存翻倍——FINISHED 回调必须条件更新认领，幂等。
- 载具未完结时被重复提交——同一载具码同时只允许一条未完结记录。
- 静默吞错——Flyway 失败要 grep 日志；WCS/RMS/同步失败必须落库可见并发告警。

## 3. 约束

- 只改 `JeecgBoot` 仓库（后端 wms 模块 + Flyway + 前端页面 + `application-dev.yml` 占位配置）。
- 遵循 `jeecg-boot/CLAUDE.md`：`wms_` 前缀、`Result<T>`、中文状态常量进 `WmsConstants`、`deleted_at` 时间戳逻辑删除、无物理外键、Mapper XML 放 `src/main/java/.../mapper/xml/`。
- 改完 wms 模块必须 `install` + **重启后端 JVM**，否则新接口 404。
- Flyway 脚本必须幂等（`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE` / 带条件 UPDATE）；版本号 `V3.9.5_13`。
- 来源系统（MES/RMS/WCS）无法持有用户 JWT：推送与回调接口用方法级 `@IgnoreAuth` + 共享令牌校验（配置项），**不得**给普通业务接口加 `@IgnoreAuth`。
- 数量与库存一律 `BigDecimal`。
- 新增 `wms_inventory` 引用后，在物料/库位删除守卫中追加 blocker（物料侧已有预留框架）。

## 4. 已定决策（需求方已确认，勿再重开）

| # | 决策 | 决定方 |
|---|---|---|
| 1 | 生产工单暂时**没有** MES/ERP 接口地址：本期建本地 `wms_work_order` 表 + 预留 MES 查询/推送接口地址、报文格式、测试账号占位符，并记录在本文档与 `application-dev.yml` | 需求方 |
| 2 | RMS 对接照 `RMS任务接口文档-V1.6.pdf` 实现；已有文档，不再模拟协议 | 需求方 |
| 3 | 新建「废品仓」库区；不良品**不做库位分配**，直接写入废品仓默认库位的库存 | 需求方 |
| 4 | 半成品/成品由**工单上的产品类型字段**判定，不按物料分类推导 | 需求方 |
| 5 | **载具即标识**：成品=料箱，二维码贴在料箱上、一箱一码；半成品=周转盘，RFID 贴在周转盘上、一盘一码 | 需求方 |
| 6 | 操作员**只扫一次载具码**。成品扫码值直接作为 RMS 的 `containerCode`；半成品扫码值作为周转盘码随 WCS 任务下发 | 需求方 |
| 7 | 一次扫码提交 = 一条 `wms_production_inbound` 记录 = 一个载具；同一工单可分多次提交，累计数量受工单计划量约束 | Claude 定 |
| 8 | 良品库存：半成品写到分配到的立库库位；成品聚合写到敏捷仓「在库默认位」（RMS 负责料箱实际位置，WMS 记录 `stack_code` 做追溯） | Claude 定 |
| 9 | 不良品库存：提交时立即 upsert 到废品仓默认库位，与良品任务互不阻塞 | Claude 定 |
| 10 | 设备故障判定：只查**绑定到目标库区**且类型匹配的设备，状态为「故障」才转人工；无绑定设备时视为可用；离线不阻断 | Claude 定 |
| 11 | 同步对象为 ERP/MES/WMS 三个地址，任一已配置目标失败即整体「同步失败」；全部未配置时模拟成功并标注 mocked | Claude 定 |
| 12 | 载具码在未完结记录（待上架/执行中/异常待人工）中不可复用；已入库/已关闭后可再次使用。**应用层预检 + 数据库生成列唯一索引双保险**：`active_carrier` 仅在未完结时等于 `carrier_code`，终态为 NULL（MySQL 唯一索引允许多个 NULL），跨工单并发由唯一索引兜底并映射为「载具正在使用中」 | Claude 定（审查加固） |
| 13 | 工单计划量在**提交时即占用**：统计范围为所有未删除且状态非「已关闭」的记录；submit 对工单行 `SELECT ... FOR UPDATE` 防并发超量 | Claude 定 |
| 14 | 重复下发幂等：任务号在提交时生成并固定；RMS 返回 `300001`（任务号已存在）视为已受理；`300006`（容器已存在）判失败并转人工 | Claude 定 |
| 15 | 回执宽容：FINISHED 允许从 `待上架 / 执行中 / 异常待人工` 条件认领，解决「HTTP 超时但设备已实际受理」的收敛问题 | Claude 定 |
| 16 | 提交事务内先置 `status=待上架, task_status=未下发`，事务提交后下发；补偿任务扫描超过 2 分钟仍 `未下发` 的记录重新下发，JVM 崩溃可自愈 | Claude 定 |
| 17 | 提供「关单」：从 `待上架 / 异常待人工` 可关单，不写良品库存、释放半成品立库库位、状态=已关闭、同步=无需同步；关单前尽力调用取消接口（RMS `/api/v2/task/cancel`，WCS 占位），失败只记录不阻塞。**关单只释放良品占用的计划量；不良品已实际进入废品仓，其数量继续计入工单占用**，防止关单后重提同一批导致废品库存重复累计 | Claude 定（审查加固） |
| 18 | 转人工完成时可选传入新载具码：传了则校验未完结占用后覆盖，不传则沿用提交时的值 | Claude 定 |
| 19 | 开发环境预置非空令牌（`dev-rms-token` / `dev-wcs-token` / `dev-mes-token`），保证模拟回执可验收；生产必须替换 | Claude 定 |
| 19a | 提交接口的设备下发改为**异步线程池**：HTTP 只返回已落库结果，避免 RMS/WCS 超时把请求拖过前端 axios 超时；人工「重新下发」保持同步，状态不允许时明确报错 | Claude 定（审查加固） |
| 19b | 「重新下发」允许从 `待上架` 与 `异常待人工`（`task_status=失败/未下发`）触发；下发结果回写一律使用**带状态条件的 UPDATE**，在途下发不得覆盖已关单/已入库记录 | Claude 定（审查加固） |
| 19c | 料箱位置更新只改该载具**最近一条**记录的 `stack_code`（`ORDER BY id DESC LIMIT 1`），不篡改历史 | Claude 定（审查加固） |
| 20 | **本期交付「生产工单手工维护页面」**（列表 + 新增/编辑，与 MES 推送共用 `wms_work_order`）与**关单**（决策 17） | 需求方 |

## 5. 流程与状态机

### 5.1 主流程

```
扫码工单（GET 工单信息）
   └─ 不存在 → 「工单号无效」
录入良品/不良品数量 + 扫一次载具码
   └─ 累计数量 > 工单计划量 → 「数量超出工单范围」
提交（POST /submit，单事务）
   ├─ 不良品 > 0 → 写废品仓默认库位库存
   ├─ 良品 = 0 → 状态=已入库，进入同步（载具码仍绑定）
   └─ 良品 > 0
        ├─ 半成品 → 分配立库空闲库位（条件更新为占用）
        ├─ 成品   → 目标库位=敏捷仓在库默认位
        └─ 事务提交后下发任务（task_status=未下发 → 已受理/失败）
             ├─ 设备故障 / 下发失败 → 状态=异常待人工 + 站内告警
             └─ 受理成功 → 状态=待上架
设备回执（RMS / WCS）
   ├─ RUNNING / OUTSTATION → 状态=执行中
   └─ FINISHED → 条件认领「已入库」→ 写良品库存 + 绑定载具 → 触发同步
人工兜底
   ├─ 异常待人工 → 转人工完成 → 写良品库存 + 绑定载具 → 已入库 → 触发同步
   └─ 待上架 / 异常待人工 → 关单 → 释放库位 → 已关闭（不同步）
补偿
   ├─ task_status=未下发 且 submit_time 超过 2 分钟 → 重新下发
   └─ 同步失败 → 3 次退避重试 → 定时任务补偿 → 超限告警
```

### 5.2 `wms_production_inbound.status`

| 状态 | 含义 | 允许的下一状态 |
|---|---|---|
| 待上架 | 已提交、任务已受理或待下发，等待设备执行 | 执行中、异常待人工、已入库、已关闭 |
| 执行中 | 设备回执 RUNNING/OUTSTATION | 已入库、异常待人工、已关闭 |
| 已入库 | 库存已写、载具已绑定 | 终态 |
| 异常待人工 | 设备故障 / 下发失败 / 回执异常 | 已入库（人工完成）、已关闭 |
| 已关闭 | 人工关单，未写良品库存 | 终态 |

良品为 0 的记录提交后直接 `已入库`。

### 5.3 `task_status`

`未下发 / 已受理 / 执行中 / 已完成 / 失败 / 已关闭`。关单时置 `已关闭`。

### 5.4 `sync_status`

沿用 RK-001：`待同步 / 同步中 / 同步成功 / 同步失败 / 无需同步`。`待上架/执行中/异常待人工/已关闭` 的单据 `sync_status = 无需同步`，入库完成时才置 `待同步` 并发起同步。

## 6. 数据模型

### 6.1 `wms_work_order`（生产工单，新表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) | 主键 |
| work_order_no | varchar(64) | 工单号，唯一（含 deleted_at） |
| material_id / material_code / material_name | varchar | 物料（冗余编码/名称） |
| product_type | varchar(10) | 半成品 / 成品（**唯一分流依据**） |
| plan_qty | decimal(12,3) | 计划数量 |
| batch_no | varchar(64) | 批次号（默认带入入库单） |
| work_order_status | varchar(20) | 待生产 / 生产中 / 已完成 / 已关闭 |
| source | varchar(20) | MES / 手工 |
| mes_order_no | varchar(64) | MES 侧单号（可空） |
| remark | varchar(500) | 备注 |
| deleted_at + create_by/create_time/update_by/update_time | | 同现有约定 |

索引：`uk_wms_work_order_no(work_order_no, deleted_at)`、`idx_wms_work_order_material(material_id)`、`idx_wms_work_order_status(work_order_status)`。

### 6.2 `wms_production_inbound`（生产入库，新表）

需求方指定字段加粗：**id、work_order_no、product_type、quantity、good_qty、bad_qty、status**。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) | 主键 |
| inbound_no | varchar(64) | 入库单号（`SC` 前缀），唯一（含 deleted_at） |
| **work_order_no** | varchar(64) | 工单号 |
| **product_type** | varchar(10) | 半成品 / 成品 |
| material_id / material_code / material_name | varchar | 自工单带出 |
| batch_no | varchar(64) | 批次号 |
| **quantity** | decimal(12,3) | 本次总量 = good_qty + bad_qty |
| **good_qty** | decimal(12,3) | 良品数量 |
| **bad_qty** | decimal(12,3) | 不良品数量 |
| **status** | varchar(20) | 待上架 / 执行中 / 已入库 / 异常待人工 / 已关闭 |
| carrier_code | varchar(64) | **载具码**（成品=料箱码/RMS containerCode；半成品=周转盘 RFID 号） |
| ident_type | varchar(10) | 二维码 / RFID（由产品类型决定） |
| location_id | varchar(32) | 良品目标库位（半成品=立库库位；成品=敏捷仓在库默认位） |
| scrap_location_id | varchar(32) | 不良品写入的废品仓库位 |
| stack_code | varchar(64) | RMS 回执的料箱库位编码（追溯用） |
| wcs_task_no | varchar(64) | 下发任务号（提交时生成并固定） |
| task_status | varchar(20) | 未下发 / 已受理 / 执行中 / 已完成 / 失败 / 已关闭 |
| task_error | varchar(500) | 下发/回执失败原因 |
| defect_remark | varchar(500) | 不良原因 |
| close_reason / close_by / close_time | | 关单留痕 |
| sync_status / retry_count / last_sync_error / last_sync_time | | 同 RK-001 |
| operator | varchar(50) | 操作员 |
| submit_time / complete_time | datetime | 提交/入库完成时间 |
| deleted_at + 审计字段 | | 同现有约定 |

索引：`uk_wms_production_inbound_no(inbound_no, deleted_at)`、`idx_wms_prod_inbound_wo(work_order_no)`、`idx_wms_prod_inbound_carrier(carrier_code)`、`idx_wms_prod_inbound_status(status)`、`idx_wms_prod_inbound_task(task_status)`、`idx_wms_prod_inbound_sync(sync_status, retry_count)`。

**载具码不做数据库唯一索引**：载具会重复使用；未完结占用由 submit 的应用层校验保证（决策 12）。

**不做明细表**：一次提交对应一个载具，数量与载具一一对应（决策 7）。

### 6.3 库区/库位种子（`V3.9.5_13`）

- 废品仓：`FPC01 / 废品仓 / warehouse_type=废品仓`，默认库位 `FPC-DEFAULT`（row=1,col=1,layer=1，status=占用）。
- 敏捷仓在库默认位：挂到现有 `成品敏捷仓`（`MJ`，运行库 id `2110885982046593025`），库位 `MJ-DEFAULT`（row=1,col=1,layer=1，status=占用）。运行时按 `warehouse_type=敏捷仓` 查库区，不硬编码 id。
- 立库 144 个空闲库位已存在，无需种子。

### 6.4 库存写入口径

| 场景 | 库位 | 时机 |
|---|---|---|
| 半成品良品 | 分配到的立库库位（提交时条件占用） | 设备 FINISHED / 转人工完成 |
| 成品良品 | 敏捷仓 `MJ-DEFAULT` | 设备 FINISHED / 转人工完成 |
| 不良品 | 废品仓 `FPC-DEFAULT` | 提交事务内立即写 |

库存 upsert 复用 `WmsInventoryMapper` 的 `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)`。

## 7. 后端接口

### 7.1 生产入库（`/wms/production`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/wms/production/work-order` | 参数 `workOrderNo`。返回工单信息 + `inboundedQty` + `remainingQty`。不存在或已删除 → `Result.error("工单号无效")` |
| POST | `/wms/production/submit` | body `{workOrderNo, goodQty, badQty, carrierCode, batchNo?, defectRemark?}` |
| POST | `/wms/production/{id}/dispatch` | `task_status=失败/未下发` 时手工重新下发（复用原任务号） |
| POST | `/wms/production/{id}/manual-complete` | body `{carrierCode?, remark?}`。仅异常待人工可用；写库存+绑定+已入库+同步 |
| POST | `/wms/production/{id}/close` | body `{reason}`。待上架/异常待人工可关单；不写良品库存、释放半成品库位、尽力取消设备任务、状态=已关闭 |
| POST | `/wms/production/{id}/sync` | 手动重试同步（乐观认领防重复） |
| GET | `/wms/production/list` | 分页，支持 `inboundNo/workOrderNo/productType/status/taskStatus/syncStatus` 筛选 |
| GET | `/wms/production/{id}` | 详情（含工单、任务、同步信息） |

**7.1.1 submit 校验与动作**

校验顺序与文案：

1. 工单存在且 `work_order_status ∈ {生产中, 已完成}`：不存在 → `工单号无效`；状态不允许 → `工单状态不允许入库`。
2. `goodQty ≥ 0`、`badQty ≥ 0`、`goodQty + badQty > 0`，否则 `数量必须大于 0`。
3. 工单行 `SELECT ... FOR UPDATE`；累计已占用（未删除且 `status <> '已关闭'` 的 `quantity` 之和）`+ goodQty + badQty > plan_qty` → **`数量超出工单范围`**。
4. `carrierCode` 必填；同码存在 `status ∈ {待上架, 执行中, 异常待人工}` 的记录 → `载具正在使用中`。
5. 半成品：分配立库空闲库位（条件更新 `status='空闲' → '占用'`）；无空闲库位 → `立库库位不足，已告警`（同时发站内告警）。
6. 成品：目标库位固定为敏捷仓在库默认位；敏捷仓库区或默认位缺失 → `敏捷仓未配置，请联系管理员`。

动作（**同一事务**）：生成 `inbound_no`（`SC` + `yyyyMMddHHmmss` + 4 位序号）与 `wcs_task_no`（`RMS`/`WCS` + 时间戳 + 序号）、写主表（`status=待上架, task_status=未下发, sync_status=无需同步`）、写不良品库存、占用库位。**事务提交后**执行设备故障检查与任务下发（外部 HTTP 不放在事务内）。

下发结果处理：

- 设备故障（决策 10）→ `status=异常待人工`、`task_status=失败`、`task_error=设备故障（设备编码）`、发站内告警。
- 网关返回失败 → 同上，`task_error` 记录对方 code/msg。RMS `300000` 在网关内先重试 3 次（1/2/4s）；`300001` 视为已受理；`300006` 直接判失败并转人工。
- 受理成功 → `status=待上架`、`task_status=已受理`。
- 良品为 0 → 不检查设备、不下发，直接 `已入库` + 同步。

**7.1.2 关单语义**

- 仅 `待上架 / 异常待人工` 可关单；已入库不可。
- 尽力调用取消接口：成品 → RMS `/api/v2/task/cancel`（`type=INBOUND`，携带 `containerCode` + `taskNo`）；半成品 → WCS 占位网关。取消失败只写 `task_error`，不阻塞关单。
- 释放本记录占用的半成品立库库位（条件更新 `status='占用' → '空闲'`）；成品不释放 `MJ-DEFAULT`，废品仓库存不回滚（不良品已实际入废品区）。
- `status=已关闭`、`task_status=已关闭`、`sync_status=无需同步`、写 `close_reason/close_by/close_time`。

### 7.2 设备网关

**RMS（成品，真实实现）** `IWmsRmsGateway`

- 配置：`wms.sync.rms.url`、`wms.sync.rms.station-code`、`wms.sync.rms.warehouse`（默认 `MITA`）、`wms.sync.rms.priority`（1~5，默认 1）、`wms.sync.rms.timeout-ms`（默认 60000）、`wms.sync.rms.callback-token`。
- 下发：`POST {url}/api/v2/task/inbound`

```json
{
  "warehouse": "MITA",
  "taskNo": "RMS202610191530000001",
  "stationCode": "MITA-STATION-01",
  "container": { "containerCode": "<操作员扫入的料箱二维码内容>" },
  "priority": 1
}
```

- 响应 `{code, msg}`；`code=0` 成功。`code != 0` 时按错误码分类（见 7.1.1）。
- 取消：`POST {url}/api/v2/task/cancel`，body `{type:"INBOUND", details:[{containerCode, taskNo}]}`。
- `wms.sync.rms.url` 未配置 → **模拟受理**，结果标记 `mocked=true`；配置后走真实 HTTP。

**WCS（半成品，占位协议）** `IWmsWcsGateway`

- 配置：`wms.sync.wcs.inbound-url`、`wms.sync.wcs.cancel-url`、`wms.sync.wcs.username`、`wms.sync.wcs.password`、`wms.sync.wcs.callback-token`。
- 下发报文（**待对方确认，先固定为**）：

```json
{
  "taskNo": "WCS202610191530000001",
  "locationCode": "LB-...",
  "carrierCode": "<周转盘 RFID 号>",
  "materialCode": "PBC001",
  "productType": "半成品",
  "quantity": 10,
  "batchNo": "B20261019A",
  "identType": "RFID"
}
```

- 响应约定 `{code:0,msg:"success"}`；未配置 → 模拟受理（mocked）。真实协议确认后只改适配层，不改业务层。

### 7.3 设备回执

**RMS（`/wms/rms`）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/wms/rms/task-feedback` | RMS → WMS 任务反馈 |
| POST | `/wms/rms/container-location` | RMS → WMS 料箱位置更新 |

请求头 `X-RMS-Token` 与 `wms.sync.rms.callback-token` 比对；不符返回 `Result.error("RMS 回调令牌无效")`。方法级 `@IgnoreAuth`。

任务反馈请求体（照 RMS 文档）：

```json
{
  "details": [{
    "taskNo": "RMS202610191530000001",
    "containerCode": "MITA_00101",
    "taskStatus": "FINISHED",
    "stationCode": "MITA-STATION-01",
    "stackCode": "MITA-STACK-01-15",
    "weight": 5000.011,
    "layer": 3
  }]
}
```

- 状态映射：`RUNNING` / `OUTSTATION` → `执行中`；`FINISHED` → 条件认领（`status IN (待上架, 执行中, 异常待人工)` 更新为 `已入库`，影响行数 1 才写库存）→ 写良品库存 + 记录 `stack_code` → 触发同步。重复 FINISHED 幂等返回成功、不重复写库存。
- 响应照 RMS 文档：`{code:0,msg:"success",details:[{taskNo,containerCode,code,msg}]}`；未知任务明细 `code=-1` 且整体 `code=1`（部分成功）。
- 料箱位置更新：按 `carrier_code` 更新**最近一条**生产入库记录的 `stack_code`（`ORDER BY id DESC LIMIT 1`）；找不到返回明细错误，不抛异常。

**WCS（`/wms/wcs`，占位契约）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/wms/wcs/task-feedback` | 堆垛机/WCS → WMS 任务回执 |

请求头 `X-WCS-Token` 与 `wms.sync.wcs.callback-token` 比对。请求体：

```json
{
  "details": [{
    "taskNo": "WCS202610191530000001",
    "carrierCode": "RFID-...",
    "taskStatus": "FINISHED",
    "locationCode": "LB-...",
    "message": ""
  }]
}
```

- `RUNNING` → `执行中`；`FINISHED` → 与 RMS 相同的条件认领逻辑；`FAILED` → `异常待人工` + 告警。
- 响应结构与 RMS 一致（支持部分成功）。

### 7.4 工单接入（`/wms/work-order`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/wms/work-order/push` | MES → WMS 推送/更新工单（`@IgnoreAuth` + `X-MES-Token` 校验 `wms.sync.mes.push-token`） |
| GET | `/wms/work-order/list` | 工单分页查询（登录态） |
| POST | `/wms/work-order/add` | 手工新增工单（登录态） |
| PUT | `/wms/work-order/edit` | 手工编辑工单（登录态） |
| DELETE | `/wms/work-order/{id}` | 删除工单；已被 `wms_production_inbound` 引用时拒绝（删除守卫） |

推送报文（**占位契约，MES 侧确认后调整**）：

```json
{
  "workOrderNo": "MO20261019001",
  "materialCode": "PBC001",
  "productType": "半成品",
  "planQty": 100,
  "batchNo": "B20261019A",
  "workOrderStatus": "生产中",
  "mesOrderNo": "MES-20261019-001"
}
```

按 `workOrderNo` upsert；物料必须存在于 `wms_material`，否则返回 `物料编码不存在`。

**MES 查询兜底（占位，暂不启用）**：`GET {wms.sync.mes.work-order-url}?workOrderNo=...`，Basic Auth 使用 `wms.sync.mes.username/password`；响应 `{code:0,data:{workOrderNo,materialCode,productType,planQty,batchNo,workOrderStatus}}`。仅当本地 `wms_work_order` 查不到且该 URL 已配置时调用。

### 7.5 同步（ERP/MES/WMS）

- SPI：`IWmsProductionInboundSyncGateway.sync(WmsProductionInbound, WmsWorkOrder) → WmsSyncResult`。
- 目标配置：
  - `wms.sync.erp.production-inbound-url` + `wms.sync.erp.username/password`
  - `wms.sync.mes.production-inbound-url` + `wms.sync.mes.username/password`
  - `wms.sync.wms.production-inbound-url`
- 报文（三地址同一份，**占位契约**）：

```json
{
  "inboundNo": "SC202610191530000001",
  "workOrderNo": "MO20261019001",
  "productType": "成品",
  "materialCode": "FG001",
  "materialName": "成品电路板",
  "batchNo": "B20261019A",
  "quantity": 10,
  "goodQty": 9,
  "badQty": 1,
  "warehouseType": "敏捷仓",
  "locationCode": "MJ-DEFAULT",
  "carrierCode": "MITA_00101",
  "identType": "二维码",
  "completedAt": "2026-10-19 15:30:00"
}
```

- 任一已配置地址失败 → 整体失败，`last_sync_error` 记录失败目标与原因；全部未配置 → 模拟成功（mocked）。
- 重试：网关内 3 次（1/2/4s）；仍失败 `sync_status=同步失败` + 站内告警（复用 `wms.sync.alert.to-user`，默认 admin）；`WmsProductionSyncRetryJob` 每 5 分钟补偿（cron `0 13/5 * * * ? *`），同时扫描 `task_status=未下发` 超过 2 分钟的记录重新下发；乐观认领同 RK-001。
- 同步失败**不回滚**入库完成。

### 7.6 权限码

`wms:production:scan` / `wms:production:submit` / `wms:production:dispatch` / `wms:production:manual` / `wms:production:close` / `wms:production:sync`。

## 8. 前端（jeecgboot-vue3）

新增 `src/views/wms/production/`：

- `index.vue` —— 一页两区：
  - **扫码作业台**（`a-card`）：
    - 工单号输入框（自动聚焦，回车即查询，扫码枪兼容）+「查询」；查到后展示工单信息卡（工单号、物料、产品类型、计划数量、已入库、剩余）。
    - 录入区：良品数量、不良品数量、不良原因、批次号（默认带出）、**载具码**（半成品标签「周转盘 RFID」，成品标签「料箱二维码」，只扫一次）。
    - 提交按钮 loading 防重复；后端文案原样弹出。
  - **入库任务列表**（`BasicTable`）：入库单号、工单号、产品类型、物料、批次、良品/不良数量、状态、任务状态、载具码、库位、同步状态、提交时间；操作列：详情、重新下发（`task_status=失败/未下发`）、转人工完成（异常待人工）、关单（待上架/异常待人工）、重试同步（同步失败）。
  - 任务跟踪：页面可见时每 5 秒 `reload()`，并提供手动刷新按钮。
  - 详情抽屉：展示任务号、`task_status`、`task_error`、`stack_code`、`carrier_code`、同步错误与重试次数。
- `production.api.ts` / `production.data.ts`：接口与列/tag 映射。
- 页面沿用现有 wms 风格：`BasicTable` + `useListPage`，`a-*` 免 import；提交表单用原生 `a-form`（同 RK-001 收料台）。
- 菜单：挂在「入库管理」下，名称「生产入库」，`url=/wms/production/inbound`，`component=wms/production/index`，sort 3，权限码见 7.6。

**生产工单手工维护页**：新增 `src/views/wms/workOrder/`（`index.vue` 列表 + `components/WorkOrderModal.vue` 新增/编辑弹窗；`workOrder.api.ts` / `workOrder.data.ts`），菜单挂在「入库管理」下，名称「生产工单」，`url=/wms/work-order`，`component=wms/workOrder/index`，sort 2（生产入库 sort 3）。权限码 `wms:work-order:add/edit/delete`；删除前走删除守卫预检（被生产入库引用则不可删）。

## 9. 迁移脚本 `V3.9.5_13__wms_production_inbound.sql`（幂等）

1. 建表：`wms_work_order`、`wms_production_inbound`。
2. 库区/库位种子：废品仓 `FPC01` + `FPC-DEFAULT`；敏捷仓 `MJ-DEFAULT`（运行库已确认 `2110885982046593025`，`INSERT IGNORE`，注释说明来源）。
3. 菜单 + 按钮权限 + admin 授权（沿用 ID 段：权限 `19100000000000002xx`、角色授权 `19200000000000002xx`）。
4. `sys_quartz_job` 种子行：`WmsProductionSyncRetryJob`，cron `0 13/5 * * * ? *`。
5. **验收种子工单**（`INSERT IGNORE`，脚本注释标注「验收用」）：
   - `WO-DEMO-SEMI`：半成品，物料 `PBC001`（未加工电路板），计划 100，批次 `B20261019A`。
   - 成品物料若不存在，种子 `FG001 / 成品电路板`（分类「电路板」）+ `WO-DEMO-FG`：成品，计划 50，批次 `B20261019B`。
6. `application-dev.yml` 增加占位配置（含开发令牌，见 §10），不改变现有行为。

## 10. 配置占位清单（交付物，需写进 `application-dev.yml` 并保留注释）

```yaml
wms:
  sync:
    mes:
      work-order-url: ""      # MES 工单查询地址（占位；未配置则只查本地表）
      username: ""            # MES 测试账号（占位）
      password: ""            # MES 测试密码（占位）
      push-token: "dev-mes-token"        # MES 推送工单令牌（开发占位，生产必须替换）
      production-inbound-url: ""         # 生产入库同步 MES（占位）
    rms:
      url: ""                 # 米塔 RMS 根地址（占位；未配置走模拟受理）
      station-code: ""        # 入库工作站编码（占位）
      warehouse: "MITA"
      priority: 1
      timeout-ms: 60000
      callback-token: "dev-rms-token"    # RMS 回调令牌（开发占位，生产必须替换）
    wcs:
      inbound-url: ""         # 堆垛机 WCS 入库下发地址（占位，协议待确认）
      cancel-url: ""          # WCS 任务取消地址（占位）
      username: ""
      password: ""
      callback-token: "dev-wcs-token"    # WCS 回执令牌（开发占位，生产必须替换）
    erp:
      production-inbound-url: ""  # 生产入库同步 ERP（占位）
      username: ""
      password: ""
    wms:
      production-inbound-url: ""  # 同步上级 WMS（占位）
```

**待外部确认事项（已记录，不阻塞本期）**：MES/ERP 接口地址、报文格式最终版、测试账号；堆垛机 WCS 协议、地址与回执格式；RMS 回调 URL 的注册方式、工作站编码与仓库名。

## 11. 验证方案

1. **后端单测**（Mockito，照 RK-001 风格）：
   - 工单不存在 → 「工单号无效」；超计划量 → 「数量超出工单范围」；并发提交不超量（FOR UPDATE）。
   - 半成品/成品分流：库位、`ident_type`、任务网关与载具码入参。
   - 废品仓立即写库存；良品为 0 直接已入库；载具未完结不可复用。
   - 下发失败/设备故障 → 异常待人工 + 告警；`300001` 幂等受理、`300006` 转人工。
   - FINISHED 幂等（含从异常待人工收敛）；WCS 回执同逻辑。
   - 关单释放库位、不写库存、尽力取消。
   - 同步重试与超限告警；`未下发` 补偿重新下发。
2. **Flyway**：重启后 grep「flyway执行sql脚本失败」= 0；`flyway_schema_history` 中 `V3.9.5_13` `success=1`。
3. **API 端到端**（HTTP 直调 8081，带 token）：
   - 半成品：扫码 → 提交（良 8/不良 2，周转盘 RFID）→ 模拟 WCS 下发 → 带 `X-WCS-Token` 回调 FINISHED → 查库存（立库库位 +8、废品仓 +2）→ 同步成功。
   - 成品：扫码 → 提交（良 5/不良 0，料箱二维码）→ RMS 模拟受理 → 带 `X-RMS-Token` 回调 FINISHED（带 stackCode）→ 查库存（MJ-DEFAULT +5）→ 同步成功。
   - 异常：无效工单、超量、载具占用、库位不足、下发失败转人工、关单释放库位。
4. **浏览器实测**（chromium + playwright-core）：扫码查询、提交、状态轮询、详情抽屉、转人工完成/关单按钮。
5. **构建门禁**：`bash /d/tools/m.sh -P dev install -pl jeecg-boot-module/jeecg-boot-module-wms -DskipTests` + 后端重启；前端 `eslint` 新文件 + `pnpm build`。
6. **文档回写**：完工写 `docs/superpowers/plans/2026-10-19-yx-wms-rk-002-验收报告.md`。

## 12. 工作量（单人粗估）

| 部分 | 估时 |
|---|---|
| Flyway（2 表 + 种子 + 菜单 + 配置） | 0.5 天 |
| 实体/Mapper/常量/工单接口 | 0.5 天 |
| 生产入库主流程（校验、分配、不良品、关单、库存） | 1.5 天 |
| WCS/RMS 网关 + 双向回执幂等 | 1 天 |
| 同步重试 + Quartz 补偿 | 0.5 天 |
| 前端页面 + 任务跟踪 | 1 天 |
| 生产工单手工维护页（后端 CRUD + 删除守卫 + 前端页面） | 1 天 |
| 单测 + 端到端 + 验收报告 | 1 天 |
| **合计** | **约 7 人天** |

## 13. 已确认的范围项（2026-10-19）

1. **生产工单手工维护页面：做**。列表 + 新增/编辑弹窗 + 删除守卫，与 MES 推送共用 `wms_work_order`；菜单「生产工单」。
2. **失败关单：做**。按决策 17 与 §7.1.2 交付。
