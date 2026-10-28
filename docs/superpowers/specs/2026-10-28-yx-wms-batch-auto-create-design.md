# 永鑫WMS — 批号主档自动创建 设计文档

- 日期：2026-10-28
- 状态：已批准（任务队列 #3，依据记忆 `wms-automation-first-principle` P1-4）
- 关联：`2026-10-28-yx-wms-material-master-sync-design.md`、`kingdee-pickmtrl-save-structure`（批号陷阱来源）

## 1. 问题

金蝶批号主档 `BD_BatchMainFile` 新建时不传 `FLotStatus` 默认 `T`（不可用），
领料/入库会报「批号已作废或批号状态不正确」；且该表单**没有** Submit/Audit/Delete 接口，
过去只能人工到金蝶界面维护 —— 与「自动化优先」冲突，且错误文案对用户毫无指向性。

## 2. 方案

新增 `WmsKingdeeBatchSupport.ensureUsable(materialCode, lot, orgNumber)`：

| 场景 | 默认行为 | 开关 |
|---|---|---|
| 批号不存在 | 自动 `Save BD_BatchMainFile {FNumber, FMaterialId, FUseOrgId, FCreateOrgId, FLotStatus:'2'}` | `batch.auto-create-enabled`（默认 **true**） |
| 批号存在且可用 | 直接通过 | — |
| 批号存在但不可用 | 返回可读原因（提示开启 auto-repair） | `batch.auto-repair-enabled`（默认 **false**，打开则自动改为可用） |
| 查询失败 | 不拦路（交给金蝶保存时报） | — |

**为什么 auto-repair 默认关**：把批号从「不可用」改成「可用」是**改写金蝶主数据**，
可能是质量部门有意停用；按「不覆盖 ERP 信息」原则，默认不擅自改动，由顾问决定是否开启。

真机验证（2026-10-28，101 测试账套）：`Save BD_BatchMainFile` 直接带 `FLotStatus='2'` 创建成功，
建后查询状态即为 `2`，无需审核。测试残留批号 `TSTAUTO-001` 需界面删除（无 API 删除）。

## 3. 接入点

| 回写器 | 位置 |
|---|---|
| `WmsKingdeePickWriterImpl`（领料） | 原 `checkBatchUsable` 改为委托 `batchSupport.ensureUsable`（自动创建代替「请先在金蝶创建批号主档」） |
| `WmsKingdeeInboundWriterImpl`（生产入库） | 解析出物料/组织后、组装分录前保障 |
| `WmsKingdeeDefectWriterImpl`（纯不良品） | 同上 |

销售出库/调拨未接入：它们是消耗既有库存，批号必然已存在，接入收益为零。

## 4. 改动清单

| 层 | 改动 |
|---|---|
| sync | 新增 `WmsKingdeeBatchSupport`；Pick/Inbound/Defect 三处接入 |
| 配置 | `WmsKingdeeProperties.Batch{autoCreateEnabled=true, autoRepairEnabled=false}` + application-dev.yml（支持环境变量覆盖） |
| 单测 | `WmsKingdeeBatchSupportTest` 7 例；Pick writer 的不可用批号用例改为验证「保障不通过即不保存」 |

## 5. 验收口径

1. 单测全绿（907）；
2. 批号缺失时同步自动建号并继续，不再要求人工去金蝶建；
3. 既有可用批号行为不变；不可用批号默认仍拦截（但给出开启开关的指引）。
