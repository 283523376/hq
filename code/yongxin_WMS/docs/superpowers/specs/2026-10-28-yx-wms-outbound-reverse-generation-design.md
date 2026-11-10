# 永鑫WMS — 出库单反向生成（领料待办）设计文档

- 日期：2026-10-28
- 状态：已批准（任务队列 #4，依据记忆 `wms-automation-first-principle` P1-3）
- 关联：`2026-10-28-yx-wms-material-master-sync-design.md`、`2026-10-28-yx-wms-erp-source-of-truth-billno-design.md`

## 1. 问题

出库单的**创建主动权在 WMS**：用户点「新建领料」、扫码工单、再逐行核对物料数量。
信息虽来自金蝶（PPBOM 需求带出），但「要领什么、领多少」仍要人算——单多时是纯重复劳动，
且容易漏领/多领。

## 2. 方案（本期：领料）

新增 `IWmsPickDemandService.generatePendingPicks()`：

```
扫描 WMS 工单（已下达/生产中，上限 200）
  └─> 读金蝶用料清单需求（IWmsKingdeePpbomReader，已存在：需求 = FNeedQty2 - FPickedQty）
        └─> 缺口 = 需求 - WMS 在途领料单已申请量
              （在途 = 状态不属于 已完成/已关闭 的领料出库单，按物料汇总）
              └─> 缺口 > 0 → 调 IWmsOutboundService.createPick 生成「待分配」领料单
                    （需求来源=金蝶用料清单，备注=系统按金蝶用料清单自动生成）
              └─> 缺口 <= 0 → 跳过
```

- **触发**：工单同步任务（`WmsWorkOrderSyncJob`）第④阶段自动执行（受 `sync.auto` 闸门控制）；
  出库页「生成领料待办」按钮手工兜底。
- **幂等**：在途量参与计算，重复执行不会重复建单；单据完成后需求自然消失。
- **失败隔离**：单张工单用料清单读不到（无 PPBOM/金蝶不可用）只跳过并记原因，不影响其它工单。
- **工单状态**：只有「已下达/生产中」才生成；未下达不领料、已关闭不补料。

生成的领料单仍是「待分配」状态，后续走既有的扫码执行 → 出库确认 → 回写金蝶链路，
用户从「算 + 录」变成「扫码确认」。

## 3. 本期不做（下一期）

**销售出库由金蝶发货通知生成待办**：需先摸清金蝶 `SAL_DELIVERYNOTICE`（发货通知单）的字段与
可用性（是否启用、状态口径、与销售订单的关联），未做真机验证前不实现，避免造错单。
计划：先只读探测字段与样例 → 出子 spec → 再实现（与本期同构：ERP 单据 → WMS 待办）。

## 4. 改动清单

| 层 | 改动 |
|---|---|
| service | 新增 `IWmsPickDemandService` + `WmsPickDemandServiceImpl`；`WmsPickDemandResultVO` |
| job | `WmsWorkOrderSyncJob` 增加第④阶段（自动生成领料待办） |
| controller | `POST /wms/outbound/generate-pick-demands`（权限 `wms:outbound:add`） |
| 前端 | 出库页「生成领料待办」按钮（结果按 created 数量给 success/warning） |
| 单测 | `WmsPickDemandServiceImplTest` 5 例（全量缺口、扣减在途、无缺口跳过、单工单失败隔离、开关） |

## 5. 验收口径

1. 单测全绿（912）；
2. 手工点「生成领料待办」后，有缺口的工单自动生成「待分配」领料单，明细数量=缺口；
3. 再次点击不重复建单；无用料清单/无缺口的工单跳过并给出原因；
4. `sync.auto=false` 时定时任务不生成（手工按钮仍可用）。
