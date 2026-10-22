# 永鑫WMS CK-004（库存调拨）设计

- 日期：2026-10-22
- 依据：`docs/superpowers/specs/2026-10-22-yx-wms-ck-004-transfer-interface-analysis.md`（接口口径已实测）
- 上游决策：`docs/superpowers/specs/2026-10-21-yx-wms-v22-impact-analysis.md` §6（P2）
- 状态：**评审中**（§2 设计前置条件未闭环前不得进入 plan/编码）

---

## 0. 用户已确认的约束

| # | 约束 | 落地章节 |
|---|---|---|
| 1 | **WMS 调拨单必须等金蝶 `FDocumentStatus=C`（已审核）才算「已完成」**；实物做完但 ERP 未审 → 停在「待ERP审核」 | §5 双时钟状态机 |
| 2 | 实物时钟与 ERP 时钟**分开存字段，不许合并成一个 status 拍平** | §5.2 三个字段 |
| 3 | **审批人/角色、审批时效、驳回后处理责任人**属设计前置条件，必须先落定（否则「已完成」会长期滞留） | §2 |
| 4 | `ZJDB01_SYS` 有工作流，WebAPI `Audit` 被拒；WMS 只能 `Save + Submit`，**不得写「自动审批」路径** | §7.2 |
| 5 | 米塔只同仓、只能到**区域级**（`zoneCode`），跨仓段不经米塔 | §6.2 |
| 6 | 101 的 D.01/D.02 不启用仓位 → **不做 WMS 库位 ↔ 金蝶仓位内码映射** | §11 |

## 1. 已确认事实（引自接口分析，不重复论证）

| 项 | 结论 |
|---|---|
| 金蝶执行单据 | `STK_TransferDirect`（直接调拨单），单据类型 `ZJDB01_SYS` |
| 101 跨仓 | `FTransferBizType=InnerOrgTransfer`、`FTransferDirect=GENERAL`；不传仓位可 Save(A)/Submit(B) |
| 审批 | `ZJDB01_SYS` 关联工作流，Submit 后停在 **B**；`Audit` 报「已关联工作流实例，不支持通过WebAPI接口进行传统审批」 |
| 状态取值 | `A` 暂存 / `B` 审核中 / `C` 已审核 / `D` 作废（语义待金蝶确认） |
| 仓管员 | `FStockerId` 必填，取 **`BD_WAREHOUSEWORKERS`** 编码且须对组织启用（测试 `0050` 熊丹妮） |
| 米塔移库 | `POST /api/v2/task/transfer`（批量，单条失败即整体失败）；`zoneCode` 必填；**不能指定 `stackCode`** |
| 米塔错误码 | `300001` 任务已存在（幂等成功）、`300007` 容器不存在、`300013` 区域不存在 |
| 既有可复用 | `wms_sync_log`、`WmsOrderSyncAttempt`（退避重试）、`IWmsRmsGateway`、`/wms/rms/task-feedback`、`/wms/rms/container-location`、`wms_inventory`（`quantity/locked_qty`） |

## 2. 设计前置条件（**2026-10-22 已全部确认**）

> 本节原为阻塞项，2026-10-22 由需求方逐条确认（确认单：`plans/2026-10-22-yx-wms-ck-004-前置条件确认单.md`）。
> **Phase B 可以开工**；下列结论已作为实现口径。

| # | 前置条件 | 确认结论 | 责任方 | 状态 |
|---|---|---|---|---|
| P1 | `ZJDB01_SYS` 审批人/角色 | 指定 **1 主 1 备** 两个审批角色；代理人由业务在请假时临时授权（具体角色名由实施时录入系统） | 业务/金蝶 | ✅ 已确认 |
| P2 | 审批时效 | **24 小时**；超时发站内告警，**不自动关单** | 业务 | ✅ 已确认 |
| P3 | 驳回处置 | 处置人 = **仓储主管**；方式 = **生成反向调拨单冲销**（系统已支持）；无实物差异时可留痕关单 | 业务 | ✅ 已确认 |
| P4 | 仓管员编码（`BD_WAREHOUSEWORKERS`） | 测试期 **`0050`（熊丹妮）**，对 101 启用；上线前由业务替换为生产编码 | 业务/金蝶 | ✅ 已确认 |
| P5 | 跨仓搬运与在途确认 | **人工/叉车**搬运；**启用两段式**（出发 → 在途 → 到达 → 上架） | 业务 | ✅ 已确认 |
| P6 | 在途暂存库位 | 由业务在**目标仓**建「在途暂存」库位（`location_id` 非空）；测试期用 `LB-R01-C01-L17` 验证后已恢复原状，正式库位待业务建 | 业务 | ⚠️ 结论已确认，**库位待建** |
| P7 | 同仓目标区域与位置回调 | **调拨不要求精准到库位**（单据只到仓库/区域级）；同仓 `zoneCode` 缺省时回落配置 `wms.sync.rms.transfer-default-zone`（默认 `PRE_ORDER`）；米塔**位置回调覆盖搬运任务** | 米塔/业务 | ✅ 已确认 |
| P8 | 组织范围 | **只做 101 同组织内部调拨**（`InnerOrgTransfer`），跨组织在建单时拒绝 | 业务/金蝶 | ✅ 已确认 |
| P9 | 金蝶 `D`（作废）语义 | 等同「驳回」，统一按 `ERP驳回 → 异常 → 待处置` | 金蝶 | ✅ 已确认 |
| P10 | 批号是否必填 | **可空**；空则执行时按 FIFO 取批次 | 业务 | ✅ 已确认 |
| P11 | 关单权限 / WMS 内部审批 / 反向拉单 | 关单仅 **admin**（后续再分配角色）；**不做** WMS 内部审批；ERP→WMS 反向拉单留到 SB-001 | 业务 | ✅ 已确认 |
| P12 | 测试单 `WMS-TEST-TR-20261022-02` | 保留作证据；上线前由金蝶侧撤回 | 金蝶/业务 | 已记录 |

**仍需业务动一下的唯一一条**：P6 的正式「在途暂存」库位（测试期临时借用已恢复，当前 `transit-location-id` 为空 → 跨仓建单会被拒绝，属预期保护）。

## 3. 范围

**本期做（Phase 1）**

1. WMS 发起调拨单（跨仓 / 同仓区域两种执行模型）；
2. 金蝶 `Save + Submit`（幂等键 `FBillNo` = WMS 调拨单号）；
3. `WmsTransferSyncJob` 轮询 `FDocumentStatus`，到 C 才置「已完成」，驳回置「异常」；
4. 实物执行：跨仓两段式（出发/到达），同仓区域走米塔 `task/transfer`；
5. 库存移动、库位占用/释放、并发锁定（复用 `wms_inventory`）；
6. 前端调拨单列表/新建/详情/驳回处置；
7. 菜单、按钮权限、Job 种子、同步日志。

**本期不做（写明理由，避免范围蔓延）**

| 不做 | 理由 |
|---|---|
| ERP→WMS 调拨单拉取（反向同步） | 本期 WMS 是执行方，双写来源会与 SB-001 对账口径冲突；列入 Phase 2 与 SB-001 一起做 |
| 调拨申请单 `STK_TransferApply` | 客户流程未要求先申请；如需要另立流程 |
| 跨组织调拨（100↔101） | 只实测了同组织 `InnerOrgTransfer`；跨组织 `FTransferBizType` 未验证 |
| 精确到库位的 ERP 仓位映射 | 101 不启用仓位，已实测无需 |
| 库存汇总推送 ERP / 差异清单 | 属 SB-001 |
| 设备任务监控页 | 属 SB-002；本期只在调拨详情展示任务状态 |

## 4. 数据模型

### 4.1 `wms_transfer_order` 调拨单

| 字段 | 类型 | 说明 |
|---|---|---|
| `transfer_no` | varchar(64) | WMS 调拨单号，**同时作为金蝶 `FBillNo`** |
| `source` | varchar(16) | `WMS` / `ERP`（本期只写 `WMS`） |
| `erp_bill_no` | varchar(64) | 金蝶单号（Save 成功后回填，通常 = `transfer_no`） |
| `erp_document_status` | varchar(8) | 金蝶原始状态 `A/B/C/D`，**只读留痕**（同 BD-002 双字段口径） |
| `erp_org_code` | varchar(32) | 库存组织，默认 `101` |
| `out_warehouse_id` / `out_warehouse_code` | varchar(32)/(64) | 调出仓 |
| `out_zone_code` | varchar(63) | 调出区域（米塔口径，可空） |
| `in_warehouse_id` / `in_warehouse_code` | varchar(32)/(64) | 调入仓 |
| `in_zone_code` | varchar(63) | 调入区域（同仓搬运时必填，跨仓可空） |
| `transfer_mode` | varchar(16) | `跨仓` / `同仓区域`（由仓库是否相同推导，落库冗余便于查询） |
| `exec_status` | varchar(16) | 实物时钟，见 §5.2 |
| `sync_status` | varchar(16) | ERP 时钟，见 §5.2 |
| `status` | varchar(16) | 单据总状态（**唯一对外展示状态**），见 §5.3 |
| `transit_location_id` | varchar(32) | 跨仓在途暂存库位（配置给定，见 §6.2） |
| `approval_alert_time` | datetime | 审批超时告警已发时间（幂等，避免反复告警） |
| `reject_reason` / `reject_time` | varchar(255)/datetime | ERP 驳回原因与时间 |
| `reject_handle_status` | varchar(16) | `待处置` / `已冲销` / `已人工关闭` |
| `reverse_transfer_no` | varchar(64) | 冲销（反向）调拨单号 |
| `retry_count` / `sync_fail_count` | int | 退避重试计数 / 连续失败计数（沿用 RK-002） |
| `last_sync_error` / `last_sync_time` | varchar(500)/datetime | 同步失败原因 / 最近同步时间 |
| `operator` / `submit_time` / `exec_finish_time` / `erp_approve_time` / `complete_time` | — | 操作与三个时间锚点 |
| `close_reason` / `close_by` / `close_time` | — | 关单 |
| `remark` | varchar(255) | 备注 |
| 审计列 + `deleted_at` | — | 沿用 `JeecgEntity` + `@TableLogic` 时间戳 |

### 4.2 `wms_transfer_detail` 调拨明细

| 字段 | 说明 |
|---|---|
| `transfer_id` / `line_no` | 所属调拨单、行号 |
| `material_id` / `material_code` / `material_name` | 物料 |
| `batch_no` | 批次（可空 → 执行时按 FIFO 取批次） |
| `container_code` | 料箱/托盘（敏捷仓同仓搬运必填；立体库为空串） |
| `unit` | 单位 |
| `transfer_qty` | 计划调拨数量 |
| `out_location_id` / `out_location_code` | 实际调出库位（执行时落库，不写 ERP） |
| `in_location_id` / `in_location_code` | 实际调入库位（位置回调 `stackCode` 落库） |
| `actual_qty` | 实发数量 |
| `deleted_at` | 逻辑删除 |

### 4.3 `wms_transfer_task` 搬运任务

一张调拨单可拆多个容器任务，单独成表（也为 SB-002 设备任务表铺路）：

| 字段 | 说明 |
|---|---|
| `transfer_id` / `detail_id` | 关联 |
| `task_no` | 任务号，前缀 `RMS`（`TASK_NO_PREFIX_RMS`） |
| `container_code` / `zone_code` | 容器 / 目标区域 |
| `task_status` | 复用 `未下发/已受理/执行中/已完成/失败/已关闭` |
| `task_error` | 失败原因（受理成功不写） |
| `mocked` | 是否模拟受理（RMS 地址未配置） |
| `feedback_time` | 设备回执时间 |
| `deleted_at` | 逻辑删除 |

### 4.4 索引与迁移

```sql
-- V3.9.5_21__wms_transfer.sql
CREATE TABLE IF NOT EXISTS `wms_transfer_order` (...) COMMENT='库存调拨单';
CREATE TABLE IF NOT EXISTS `wms_transfer_detail` (...) COMMENT='调拨明细';
CREATE TABLE IF NOT EXISTS `wms_transfer_task`   (...) COMMENT='调拨搬运任务';

UNIQUE INDEX `uk_wms_transfer_no` (`transfer_no`, `deleted_at`);      -- wms_transfer_order
INDEX `idx_wms_transfer_status` (`status`);
INDEX `idx_wms_transfer_erp` (`erp_document_status`, `sync_status`);
INDEX `idx_wms_transfer_detail` (`transfer_id`);
INDEX `idx_wms_transfer_task_no` (`task_no`);
```

- **不改 `wms_inventory` 结构**：调拨只做 `quantity` / `locked_qty` 的条件更新与库位迁移，无需新列。
- 同脚本内追加：菜单（「库存管理」目录，`sort=93`）、按钮权限 `wms:transfer:*`、Job 种子
  （`1920000000000000350`，`WmsTransferSyncJob`，cron `0 23/10 * * * ? *`）。
- 回滚：`DROP TABLE wms_transfer_task / wms_transfer_detail / wms_transfer_order` + 菜单/按钮/Job 反向删除
  （脚本头注释写明「已应用迁移不可改 checksum，回滚段仅供运维手工执行」）。

## 5. 双时钟状态机（核心）

### 5.1 为什么必须两个时钟

实物移动由 WMS/设备完成，发生在前；ERP 库存移动由**金蝶工作流审批**触发，发生在后。
二者之间天然存在时间差，**且 ERP 可能驳回**——合并成单一状态就会出现「既已完成又没审批」这类无法表达的状态。

### 5.2 三个字段

| 时钟 | 字段 | 取值 | 谁改 |
|---|---|---|---|
| 实物执行 | `exec_status` | `待执行` → `执行中` →（`在途` → `待入库`）→ `实物完成`；`异常` | WMS 执行动作 / 设备回执 / 位置回调 |
| ERP 单据 | `sync_status` | `无需同步` / `待同步` / `同步中` / `已提交待审核`(B) / `已审核`(C) / `ERP驳回` / `同步失败` | 金蝶 Writer / `WmsTransferSyncJob` |
| （留痕） | `erp_document_status` | `A` / `B` / `C` / `D`（原始值，只读） | `WmsTransferSyncJob` |

`sync_status` 新增 3 个常量到 `WmsConstants`（其余复用现有 `SYNC_STATUS_*`）：

```java
String SYNC_STATUS_SUBMITTED  = "已提交待审核";   // 金蝶 B
String SYNC_STATUS_APPROVED   = "已审核";         // 金蝶 C
String SYNC_STATUS_ERP_REJECTED = "ERP驳回";      // D / 工作流驳回
```

### 5.3 总状态 `status` 的唯一派生规则

`status` **不由任何动作直接赋值**，只由单一方法 `deriveStatus(execStatus, syncStatus)` 计算（集中在
`WmsTransferStatusResolver`，禁止各 service 手写），保证不会出现两处口径不一致：

| `exec_status` | `sync_status` | `status` |
|---|---|---|
| `待执行` | 任意 | **待执行** |
| `执行中` / `在途` / `待入库` | 任意 | **执行中** |
| `异常` | 任意 | **异常** |
| `实物完成` | `待同步` / `同步中` / `同步失败` / `已提交待审核` | **待ERP审核** |
| `实物完成` | `已审核` | **已完成** |
| `实物完成` | `ERP驳回` | **异常**（`reject_handle_status=待处置`） |
| `实物完成` | `无需同步`（`source=ERP` 且已 C） | **已完成** |
| 任意（非终态） | 任意 | 手工关单 → **已关闭** |

> 关键点：**「已完成」当且仅当 `exec_status=实物完成` 且 `sync_status=已审核`**。
> `sync_status=同步失败` 不算完成——它在「待ERP审核」里等重试，通过告警暴露。

### 5.4 迁移图

```
exec_status:
  待执行 ──dispatch/出发确认──► 执行中 ──跨仓出发──► 在途 ──跨仓到达──► 待入库 ──上架/回调──► 实物完成
     │                          │                                                      │
     │                          └────同仓：RMS FINISHED + 位置回调──────────────────────┘
     └──任一步失败/库存不足/设备故障──► 异常 ──人工修复──► 回到原步骤

sync_status（仅 source=WMS）:
  待同步 ──Save+Submit──► 同步中 ──Submit 成功──► 已提交待审核(B)
     ▲                        │                        │
     │                        └─异常─► 同步失败 ──退避重试─┘
     │                                                 │
     └────────A（暂存，视为未提交）────────────────────┤
                                                       ├─C──► 已审核
                                                       └─D/驳回──► ERP驳回

status = derive(exec_status, sync_status)   // §5.3
```

### 5.5 非法迁移一律拒绝

集中在一个 `assertTransition(from, to)` 白名单里，越界抛 `RuntimeException`：

- `已完成` / `已关闭` 是**终态**：不接受 `exec_status`、`sync_status` 回退（迟到回执/迟到轮询一律丢弃并 warn）。
- `ERP驳回` 之后**不得**把 `exec_status` 改回 `待执行`——实物已动，只能：`reverse`（生成反向调拨）或
  `close`（人工关闭并写 `close_reason`）。
- `sync_status=已审核` 之后不得再置 `同步失败`（C 是金蝶终态）。

### 5.6 迟到回执/并发

- 设备回执、位置回调、Job 轮询全部走**条件更新**：`UPDATE ... WHERE id=? AND sync_status IN (...)`，
  返回 0 行即说明已被别的线程推进，放弃本次写入（与 CK-001/002 回执白名单同构）。
- `WmsTransferSyncJob` 用 Quartz 集群锁（沿用现有 Registrar 模式），并靠 `sync_status` 条件更新保证幂等。

## 6. 执行模型

### 6.1 两条执行路径

| 场景 | 判定 | 动作 |
|---|---|---|
| **同仓区域搬运** | `out_warehouse_id == in_warehouse_id` | 分配容器 → `dispatch` → 米塔 `task/transfer`（`zoneCode=in_zone_code`）→ `task-feedback FINISHED` → `container-location` 回调给 `stackCode` → `exec_status=实物完成`；**WMS 内部移库，不生成金蝶调拨单**（`sync_status=无需同步`，实物完成即「已完成」） |
| **跨仓调拨** | 仓库不同 | 源仓出库确认（`exec_status=在途`，源仓库存扣减）→ 目标仓收货确认（落暂存库位 → `待入库`）→ 上架确认（落实际库位 → `实物完成`）→ **回写金蝶直接调拨单并等 C** |

> **实现期修正（2026-10-22）**：金蝶直接调拨单要求「调出仓库+仓位 ≠ 调入仓库+仓位」，同仓（同一库区）无法表达；
> 因此**同仓区域搬运不走 ERP**（`sync_status=无需同步`），只有跨仓调拨才回写金蝶并等 `C`。

跨仓**不向米塔下发任务**（米塔只同仓）；`enable-transit=false` 时把「在途→待入库→上架」合并为一次到达确认。

### 6.2 跨仓在途库存落到哪（`location_id` NOT NULL 的约束）

`wms_inventory.location_id` 为 `NOT NULL`，跨仓在途期间库存必须有归属，因此：

- 配置 `wms.transfer.transit-location-id`（必填，指向目标仓的「在途暂存库位」，由业务建库位时标记）；
- 到达确认时：源仓库存行 `quantity -= qty`，目标仓暂存行 `quantity += qty`（按 material+batch+container upsert）；
- 上架确认时：暂存行迁到实际库位（同库位不同行则合并），并释放源库位 `status=空闲`；
- 未配置暂存库位 → 跨仓单**在创建时就报错**（不做静默降级）。

### 6.3 数量与批次

- 明细填了 `batch_no` 就只取该批次；未填则按 FIFO（`selectAvailable` 同 CK-001 排序口径）取批次。
- 分批跨行扣减时，每行都走 `lockInventory` 条件更新；任一失败整单回滚（`@Transactional(rollbackFor=Exception.class)`）。
- `actual_qty` 默认等于分配量，允许在执行确认时改小并写 `remark`。

## 7. 同步架构

### 7.1 组件

```
WmsTransferController ──► IWmsTransferService
                            ├─ 执行段：IWmsTransferExecutor（分配/下发/出发/到达/上架）
                            ├─ 库存段：WmsInventoryMapper（lockInventory / 条件更新）
                            ├─ ERP 段：IWmsKingdeeTransferWriter（Save + Submit + 查询状态）
                            └─ 日志段：IWmsSyncLogService（direction=WMS_TO_ERP, objectType=TRANSFER）
WmsTransferSyncJob（cron 0 23/10 * * * ? *）
   ├─ ① 状态回读：sync_status=已提交待审核 → 查 FDocumentStatus → C/D/A/B 分支
   ├─ ② 审批超时：B 且 now-submit_time > transfer-approval-timeout-hours → 告警（approval_alert_time 幂等）
   └─ ③ 失败重试：sync_status∈{待同步,同步失败} 且 retry_count<3 → 退避重试
```

### 7.2 `IWmsKingdeeTransferWriter`

| 方法 | 行为 |
|---|---|
| `saveAndSubmit(order, details)` | `Save`（`STK_TransferDirect`，表头/明细按接口分析 §2.5，**不传 `FSrcStockLocId/FDestStockLocId`**）→ 成功后 `Submit`（`data.Numbers=[transferNo]`）→ 期望 `A→B`；**绝不调用 `Audit`** |
| `queryStatus(billNo)` | `ExecuteBillQuery` 取 `FBillNo,FDocumentStatus`；返回 A/B/C/D 原值 |
| 幂等 | `FBillNo=transferNo`；重复 Save 命中金蝶「单号重复」→ 视为已存在，转而查状态（与 RK-002 Writer 同构） |
| mock 分支 | `kingdee.enabled=false` 或必填配置缺失 → `mocked=true`，`sync_status=已提交待审核` 并写同步日志，便于演示；**不静默置「已审核」** |

### 7.3 三个时间锚点

| 锚点 | 写入时机 |
|---|---|
| `submit_time` | Submit 成功（进入 B） |
| `exec_finish_time` | `exec_status` 首次变 `实物完成` |
| `erp_approve_time` / `complete_time` | 轮询到 C；两者同一次写入 |

指标：`erp_approve_time - exec_finish_time` 即「审批延迟」，供业务评估 P2 时效是否合理。

### 7.4 驳回处置（不自动回退）

ERP 驳回/作废时：

1. `sync_status=ERP驳回`、`status=异常`、`reject_handle_status=待处置`、记 `reject_reason`；
2. 站内告警标题 `WMS调拨ERP驳回告警`，接收人 `wms.sync.alert.to-user`（P3 责任人）；
3. 处置入口（人工）：
   - `POST /{id}/reverse` → 生成**反向调拨单**（源/目标互换，`source=WMS`，备注指向原单），源单填 `reverse_transfer_no`；
   - 或 `POST /{id}/close`（必填 `close_reason`，含「实物已人工冲销/盘盈亏调整」说明）→ `reject_handle_status=已人工关闭`。
4. **不允许**把原单 `exec_status` 改回 `待执行`。

## 8. 库存与并发

| 动作 | 库存操作 |
|---|---|
| 同仓 `dispatch` | 按明细 `lockInventory` 锁定（`locked_qty += qty`）；锁定量 = 计划量 |
| 同仓 `实物完成` | `quantity -= qty` 且 `locked_qty -= qty`；库存行 `location_id` 迁到回调 `stackCode`（合并同物料/批次/容器行）；释放源库位 |
| 跨仓出发确认 | 源行 `quantity -= qty`、`locked_qty -= qty`；释放源库位 |
| 跨仓到达确认 | 暂存库位行 upsert `+= qty` |
| 跨仓上架确认 | 暂存行 → 实际库位（合并/迁移） |
| 异常/关单 | 释放 `locked_qty`（`GREATEST` 条件更新，禁止清掉他单锁定）与库位占用 |

并发要点（沿用 CK-001/002 的教训，写进单测）：

- `lockInventory` 返回 0 行 → 抛「库存已被其他单据占用」；
- 释放锁定必须 `WHERE locked_qty >= qty`，否则会把别单的锁一起清掉；
- 回执与 Job 状态推进全部条件更新，杜绝「迟到的 RUNNING 把已完成拉回」。

## 9. 接口清单（`WmsTransferController`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/wms/transfer/list` | `wms:transfer:list` | 分页（`status/transferMode/source/keyword/日期`） |
| GET | `/wms/transfer/{id}` | `wms:transfer:list` | 详情（明细 + 任务 + 同步日志） |
| POST | `/wms/transfer` | `wms:transfer:add` | 新建（校验仓库/区域/暂存库位/物料） |
| POST | `/wms/transfer/{id}/dispatch` | `wms:transfer:dispatch` | 分配 + 下发（同仓走 RMS；跨仓仅锁定并待出发） |
| POST | `/wms/transfer/{id}/depart` | `wms:transfer:execute` | 跨仓出发确认（→在途） |
| POST | `/wms/transfer/{id}/arrive` | `wms:transfer:execute` | 跨仓到达确认（→待入库，落暂存） |
| POST | `/wms/transfer/{id}/putaway` | `wms:transfer:execute` | 跨仓上架确认（→实物完成） |
| POST | `/wms/transfer/{id}/sync-erp` | `wms:transfer:sync` | 手工提交/重试 Save+Submit，或回读状态 |
| POST | `/wms/transfer/{id}/reverse` | `wms:transfer:reverse` | 生成反向调拨单（驳回处置） |
| POST | `/wms/transfer/{id}/close` | `wms:transfer:close` | 关单（释放锁定，理由必填） |

设备回执复用现有免登录端点，无需新增：`/wms/rms/task-feedback`（按 `task_no` 查 `wms_transfer_task`）、
`/wms/rms/container-location`（按 `containerCode` 更新 `in_location_id/in_location_code` 并推进状态）。

## 10. 前端（`src/views/wms/transfer/`）

| 文件 | 内容 |
|---|---|
| `index.vue` | 列表：单号/模式/调出→调入/状态/**ERP 状态**（A/B/C/D 小标签）/**待ERP审核停留时长**/操作 |
| `TransferCreateModal.vue` | 新建：调出仓+区域、调入仓+区域（同仓自动锁区域必填）、明细（物料/批次/容器/数量） |
| `TransferDetailDrawer.vue` | 详情：**双时钟时间轴**（实物完成 vs ERP 审核，含审批延迟）、明细与库位、搬运任务、同步日志、动作按钮 |
| `reject` 交互 | status=异常 且 `reject_handle_status=待处置` 时高亮「生成反向调拨 / 人工关闭」二选一 |

展示口径（前端唯一取 `status`，不得自行组合字段）：`待ERP审核` 用小黄点 + tooltip 显示
「实物已完成，等待金蝶审批（已等待 X 小时）」。

## 11. 配置新增（`application-dev.yml`）

```yaml
wms:
  transfer:
    transit-location-id: "${WMS_TRANSFER_TRANSIT_LOCATION_ID:}"   # 跨仓在途暂存库位，必填（跨仓单创建时校验）
    enable-transit: "${WMS_TRANSFER_ENABLE_TRANSIT:true}"         # false=跨仓一次确认
  sync:
    erp:
      kingdee:
        transfer-bill-type: "${KINGDEE_TRANSFER_BILL_TYPE:ZJDB01_SYS}"
        transfer-biz-type: "${KINGDEE_TRANSFER_BIZ_TYPE:InnerOrgTransfer}"
        transfer-direct: "${KINGDEE_TRANSFER_DIRECT:GENERAL}"
        stocker-number: "${KINGDEE_STOCKER_NUMBER:0050}"           # BD_WAREHOUSEWORKERS 编码（P4）
        transfer-approval-timeout-hours: "${KINGDEE_TRANSFER_APPROVAL_TIMEOUT_HOURS:24}"  # P2
    rms:
      transfer-zones: "${WMS_RMS_TRANSFER_ZONES:}"                # 区域白名单，逗号分隔（P6）
```

> 复用 `stock-code-by-org`（WMS 仓库 ↔ 金蝶仓库编码），**不需要**库位↔仓位映射。

## 12. 测试与验收

### 12.1 单测（目标 ≥ 25 个新用例）

| 组 | 覆盖点 |
|---|---|
| 状态派生 | §5.3 八种组合逐个断言；`deriveStatus` 是唯一出口（反射/静态检查） |
| 非法迁移 | 已完成/已关闭 拒绝回退；驳回后拒绝置「待执行」；C 之后拒绝置同步失败 |
| ERP 段 | Save 报文结构（表头 9 必填、不传仓位）、Submit、幂等（单号重复）、mock 分支不置已审核、B→C 推进、D→异常 |
| 超时告警 | B 超 `timeout` 发告警一次（`approval_alert_time` 幂等）、未超不发、发过不重复 |
| 执行段 | 同仓 dispatch 报文（`zoneCode`）、`300001` 幂等、`300007/300013` 转异常、跨仓两段式、`enable-transit=false` 合并 |
| 库存 | 锁定条件更新 0 行回滚、同仓改库位合并行、跨仓暂存 upsert、关单释放锁定不清他单、源库位释放 |
| 并发 | 回执与 Job 同时推进只生效一次 |
| 驳回处置 | reverse 生成反向单并回填 `reverse_transfer_no`；close 必填理由 |

### 12.2 端到端（金蝶测试环境 + mock RMS）

1. **同仓**：建单（D.01→D.02 同仓区域）→ dispatch（RMS 未配 → `mocked=true`）→ 模拟 `FINISHED` + 位置回调 →
   `exec_status=实物完成`、`status=待ERP审核` → 金蝶 `STK_TransferDirect` 出现 B → 金蝶 UI 审批到 C →
   Job 轮询 → `status=已完成`、`erp_approve_time` 有值；
2. **跨仓**：建单（无暂存库位 → 报错；配置后）→ 出发/到达/上架 → 暂存行与目标行正确 → ERP 到 C 完成；
3. **驳回**：测试环境把某单驳回/作废 → `status=异常`、告警、`reverse` 生成反向单；
4. **超时**：把 `transfer-approval-timeout-hours` 设 0 → 立即告警，且只告警一次；
5. 全量后端单测 + 前端 `pnpm build`；启动日志「flyway执行sql脚本失败」= 0；`flyway_schema_history` 中 `3.9.5.21` `success=1`。

### 12.3 验收放行条件

- [ ] §2 前置条件 P1–P3 已确认并回填本文件
- [ ] 「已完成」只出现在 `exec_status=实物完成 && sync_status=已审核`
- [ ] B 状态单据 100% 出现在「待ERP审核」并带等待时长
- [ ] 驳回场景有明确责任人、告警与处置记录
- [ ] 回滚脚本与迁移脚本同批提交

## 13. 风险

| # | 风险 | 应对 |
|---|---|---|
| 1 | 工作流审批长期不处理 → 「待ERP审核」堆积 | 超时告警 + 列表按等待时长排序；P2 时效公示到客户 |
| 2 | ERP 驳回但实物已移动 → 账实不符 | 只允许反向调拨/人工盘盈亏，禁止状态回退；驳回单强制责任人处置 |
| 3 | 跨仓在途期间库存挂暂存库位，报表口径变化 | 库存查询（KC-004）必须单列「在途库存」；本设计的 `transit_location_id` 即口径锚点 |
| 4 | 米塔 `zoneCode` 未配置 → 同仓搬运不可用 | 配置缺失即报错（不 mock），P6 提前确认 |
| 5 | 审批延迟导致 ERP 与 WMS 库存差异窗口 | 由 SB-001 差异清单兜底；本设计只保证时间锚点可度量 |
| 6 | 同一容器被调拨与出库同时占用 | `locked_qty` 条件更新；调拨锁定与出库锁定同口径 |

## 14. 设计自检

- [x] 用户核心约束「C 才算完成」落到 §5.3 唯一派生规则 + §5.5 非法迁移 + §12.1 单测。
- [x] **审批人/时效/驳回责任人作为前置条件单列在 §2**，并说明缺失时的降级行为与后果。
- [x] 不写「自动审批」路径：§7.2 明确禁止 `Audit`。
- [x] 实物时钟与 ERP 时钟分字段（`exec_status` / `sync_status` + `erp_document_status` 留痕），总状态只做派生。
- [x] 跨仓与同仓区域两条执行路径边界清晰（米塔只同仓、只到区域）。
- [x] 库存移动时机与暂存库位口径明确（`location_id NOT NULL` 约束已处理）。
- [x] 复用既有件：`wms_sync_log`、退避重试、`IWmsRmsGateway`、`/wms/rms/*` 回调、`lockInventory`、删除守卫约定（明细表随主表逻辑删除）。
- [x] 迁移脚本、回滚、菜单/按钮/Job 种子、测试与验收放行条件齐备。
- [ ] **待用户批准 §2 确认结果后进入 plan，再写代码。**
