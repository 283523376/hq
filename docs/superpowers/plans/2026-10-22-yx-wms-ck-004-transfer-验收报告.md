# CK-004 库存调拨 —— 验收报告（Phase A 执行段）

- 日期：2026-10-22
- 设计：`docs/superpowers/specs/2026-10-22-yx-wms-ck-004-transfer-design.md`
- 计划：`docs/superpowers/plans/2026-10-22-yx-wms-ck-004-transfer.md`（Task 1–9）
- 状态：**Phase A（执行段）验收通过；Phase B（完成判定段）未开工**，阻塞于 spec §2 前置条件 P1–P4

---

## 1. 交付范围（Task 1–9）

| Task | 内容 | 落点 |
|---|---|---|
| 1 | 迁移 `V3.9.5_21__wms_transfer.sql` | 3 表 + 索引 + 菜单「库存管理」+ 7 个按钮 + admin 授权 |
| 2 | 常量/实体/Mapper/DTO·VO | `WmsConstants` TRANSFER 段、`WmsTransferOrder/Detail/Task`、3 个 Mapper、`WmsTransferCreateDTO`、`WmsTransferVO` |
| 3 | `WmsTransferStatusResolver` | `deriveStatus()` 八行表 + `assertTransition()` 白名单 + `isTerminal()` |
| 4 | 库存执行服务 | `IWmsTransferInventoryService`：按源仓 FIFO 锁定、释放、扣减、迁库位、目标仓入库 |
| 5 | 同仓区域搬运 | `IWmsRmsGateway.dispatchTransfer/cancelTransfer`、`WmsRmsClient.transfer`、`/api/v2/task/transfer` 报文与错误码分支 |
| 6 | 跨仓两段式 | `depart/arrive/putaway`（在途 → 暂存库位 → 上架），`enable-transit=false` 合并为一次 |
| 7 | Controller | `WmsTransferController` 10 端点（`sync-erp`/`reverse` 为 Phase B 占位，返回明确文案） |
| 8 | 前端 | `views/wms/transfer/`：列表、新建弹窗、详情抽屉（双时钟时间轴 + 待审停留时长） |
| 9 | 全链路验证 | 见 §3 |

## 2. 数据库变更

`V3.9.5_21__wms_transfer.sql`（已执行 `success=1`）：

- 表：`wms_transfer_order` / `wms_transfer_detail` / `wms_transfer_task`
- 比 spec 多两列（计划期补充，用于精确定位锁定行）：`wms_transfer_task.inventory_id`、`allocated_qty`
- 菜单：`1910000000000000401`（库存管理，sort=93）、`1910000000000000402`（库存调拨页）
- 按钮：`0411` 查询、`0412` 新建、`0413` 下发、`0414` 执行确认、`0415` ERP 同步、`0416` 反向冲销、`0417` 关单
- `sys_role_permission` 9 行（目录+页面+7 按钮，授权 admin）

## 3. 验证证据

### 3.1 自动化

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全模块单测 | `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms` | **722/722 pass**（新增 `WmsTransferStatusResolverTest` 24 例） |
| 前端类型构建 | `pnpm build`（jeecgboot-vue3） | **成功** |
| ESLint | `npx eslint src/views/wms/transfer --ext .vue,.ts` | 无 error |

### 3.2 迁移与运行态

- `flyway_schema_history`：`3.9.5.21` `success=1`；启动日志「flyway执行sql脚本失败」= **0**
- 后端 8081 启动 9.5s；`/wms/transfer/list` 带 token 返回 `{"code":200,...,"total":0}`（端点已注册，非 404）

### 3.3 同仓全链路（mock RMS + 真实回调）

仓：`MJ`（敏捷仓）；物料 `FG001`，容器 `E2E-BOX-20261022`，数量 1；目标区域 `PRE_ORDER`；位置回调 `MJ-R01-C02-L01`。

| 步 | 动作 | 结果 |
|---|---|---|
| 1 | `POST /wms/transfer` | 200，单号 `DB2113152961352630273`，`exec=待执行` |
| 2 | `POST /{id}/dispatch` | 200，`exec=执行中`；RMS 未配置 → 任务 `mocked=true`、`已受理` |
| 3 | `POST /wms/rms/container-location` | `code=0`，明细 `in_location_code=MJ-R01-C02-L01` |
| 4 | `POST /wms/rms/task-feedback`（FINISHED） | `code=0` |
| 5 | 详情 | **`exec=实物完成`、`status=待ERP审核`**、`execFinishTime` 有值、`waitingHours=0` |
| 6 | 库存 | 源行 `MJ-DEFAULT` 数量 0/锁定 0 且库位回「空闲」；目标行 `MJ-R01-C02-L01` 数量 1、库位「占用」 |

### 3.4 跨仓全链路（MJ → LB，两段式）

前置：临时以 `LB-R01-C01-L17` 作为在途暂存库位（启动时注入 `WMS_TRANSFER_TRANSIT_LOCATION_ID`），测试后已恢复。

| 步 | 动作 | 结果 |
|---|---|---|
| 1 | 建单（MJ → LB，**不填区域**） | 200，`transferMode=跨仓`，区域回落默认 `PRE_ORDER` |
| 2 | `dispatch` | `exec=执行中`（跨仓只锁定，**不下发 RMS**） |
| 3 | `depart` | `exec=在途`，源仓库存扣减 |
| 4 | `arrive` | `exec=待入库`，暂存库位 `LB-R01-C01-L17` 数量 1 |
| 5 | `putaway`（指定 `LB-R01-C01-L18`） | **`exec=实物完成`、`status=待ERP审核`** |
| 6 | 库存 | 暂存 L17 数量 0；目标 L18 数量 1 且「占用」 |

### 3.5 负向与回归

| 场景 | 期望 | 实测 |
|---|---|---|
| 跨仓未配置暂存库位 | 建单报错 | 「跨仓调拨未配置在途暂存库位（wms.transfer.transit-location-id），请先在系统配置中指定」 |
| 库存不足（需求 999 / 可用 2） | 下发报错且无残留 | 「库存不足：FG001 需求 999，可用 2」；0 条任务行、无锁定残留（事务回滚生效） |
| 同仓 FINISHED 但无位置回调 | 转异常并告警 | `exec=异常`、`status=异常`；站内消息「WMS调拨设备异常告警」已投递 |
| 执行中关单 | 释放锁定 | 关单前 `locked_qty=1` → 关单后 `0`，`status=已关闭` |

### 3.6 执行期发现并修复的 4 个缺陷

| # | 缺陷 | 现象 | 修复 |
|---|---|---|---|
| 1 | 分配未按容器过滤 | 敏捷仓指定容器 A，却被分配到容器 B（FIFO 忽略 `container_code`） | `selectAvailableByWarehouse` 增加 `containerCode` 条件，敏捷仓按容器精确取数 |
| 2 | 设备回执状态比对错位 | RMS 回 `FINISHED`，后端按「已完成」比较 → 收到回执也不推进 | 新增 `DEVICE_STATUS_FINISHED/FAILED/RUNNING` 常量，`isDeviceFinished/Failed` 兼容原始值与内部值 |
| 3 | 关单漏释放锁定 | 「FINISHED 但缺库位」转异常后关单，因任务状态已「已完成」被跳过 → 锁定泄漏 1 | 释放判定改为按 `exec_status`（在途/待入库/实物完成=库存已动，不释放；其余释放） |
| 4 | 上架后暂存库位未释放 | 跨仓 putaway 后暂存库位数量为 0 但仍显示「占用」 | `deductFromLocation` 扣到 0 时同步把库位状态回「空闲」 |

### 3.7 测试数据回收

E2E 结束后已恢复现场：删除全部 `wms_transfer_*` 测试行、删除测试库位 `MJ-A01`、
库存 `E2E-BOX-20261022` 回到原库位 `MJ-DEFAULT` 且数量 1、`MJ-DEFAULT` 状态回「占用」、
临时借用的 `LB-R01-C01-L17/L18` 回「空闲」。临时注入的 `WMS_TRANSFER_TRANSIT_LOCATION_ID` 已随重启移去（当前为空，跨仓建单再次被拒——预期保护，等业务正式建库位）。

## 4. 与计划/设计的偏差（如实记录）

1. **Task 6 的 `IWmsTransferExecutor` 未单独成类**：执行动作合并在 `WmsTransferServiceImpl`（`dispatch/depart/arrive/putaway/close`），库存原语在 `IWmsTransferInventoryService`。原因：动作之间有强事务耦合，拆两个类反而要在类间传状态。
2. **`wms_transfer_task` 增两列** `inventory_id` / `allocated_qty`：分配行需要指向具体库存行才能精确释放/扣减，spec §4.3 未列此字段。
3. **同仓「实物完成」依赖位置回调**：未收到 `container-location` 时即使 `FINISHED` 也转异常（不允许无目标库位就移库存）。spec §6.1 已隐含，此处明确为硬规则。
4. **`sync-erp` / `reverse` 为占位**：Phase B 才实现，接口返回明确提示而非静默成功。

## 5. 未完成项（Phase B / 待外部确认）

| 项 | 说明 |
|---|---|
| 金蝶 `Save + Submit` 回写 | Task 10，未开工；阻塞于 P1 审批人/角色、P2 审批时效、P3 驳回责任人、P4 仓管员编码 |
| `WmsTransferSyncJob`（C 判定 / 超时告警 / 重试） | Task 11，未开工；「已完成」目前不会自动出现（符合设计：Phase A 只停在「待ERP审核」） |
| 驳回处置（反向调拨/人工关闭） | Task 12，未开工 |
| **跨仓 E2E** | 未跑：需先配置 `wms.transfer.transit-location-id`（当前为空，建单即被拒是预期行为） |
| 真实米塔联调 | `wms.sync.rms.url` 为空 → 搬运任务为 `mocked=true` 模拟受理 |

## 6. 回滚

见 `V3.9.5_21__wms_transfer.sql` 脚本头注释：删 3 张表 + 删 `19100000000000004%` 权限与授权。代码回滚按提交逆序 revert（迁移已应用不可改 checksum）。
