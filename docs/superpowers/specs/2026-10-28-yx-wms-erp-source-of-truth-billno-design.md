# 永鑫WMS — 「ERP 单号为准」回写改造 设计文档

- 日期：2026-10-28
- 状态：已批准（用户 2026-10-27 拍板，2026-10-28 排期开工，任务队列 #1）
- 关联：记忆 `erp-source-of-truth-bill-number`、`wms-automation-first-principle`、
  `2026-10-28-yx-wms-defect-outbound-erp-design.md`（defect writer 已按此模式先行落地）

## 1. 决策（用户原话口径）

1. **单据号以金蝶 ERP 为准**：回写时不再把 WMS 单号当 `FBillNo` 传给金蝶，由金蝶按编码规则生成
   （SCRK…/SOUT…/QTCK…）；成功后把 ERP 单号**回存 WMS**（`erp_bill_no`），供展示与对账。
2. **不覆盖 ERP 的任何信息**；金蝶取不到的才由 WMS 提供。

## 2. 现状（改造前）

| 回写器 | 金蝶表单 | FBillNo 现状 | 幂等现状 |
|---|---|---|---|
| `WmsKingdeeInboundWriterImpl` | PRD_INSTOCK | 成品 Save 传 WMS 入库单号；半成品 Push 天然不传 | 按 WMS 单号查 |
| `WmsKingdeePickWriterImpl` | PRD_PickMtrl | 传 WMS 出库单号 | 按 WMS 单号查 |
| `WmsKingdeeOutstockWriterImpl` | SAL_OUTSTOCK | 传 WMS 出库单号 | 按 WMS 单号查 |
| `WmsStocktakeErpWriterImpl` | STK_Miscellaneous / STK_MisDelivery | 传 `stocktakeNo-GY/-GK` | 无（只 Save 不提交） |
| `WmsKingdeeTransferWriterImpl` | STK_TransferDirect | 传 WMS 调拨单号 | 按 WMS 单号查 |

字段现状：`wms_production_inbound.erp_bill_no`（V3.9.5_32 新增）、
`wms_stocktake_order.erp_bill_no`（已有，但被 `markErpWriteback` 挪用存状态文案——需修正）、
`wms_transfer_order.erp_bill_no`（已有，但 mock 路径错误地把 WMS 单号写进去——需修正）、
`wms_outbound_order`（**缺失**，领料/销售出库共用此表）。

## 3. 改造方案（统一模式）

每个回写器统一为：

```
write(x):
  1. erp_bill_no 有值  → followUp：按 ERP 单号查金蝶状态
                          B/C → 成功；A/Z/D → 补 Submit；查不到 → 可读报错（可能被人工删除）
  2. erp_bill_no 为空  → 兼容查询：按旧 WMS 单号查一次金蝶（接住改造前已开的存量单）：
                          查到 → 补 Submit + 回存查到的单号（它就是金蝶里的单号）
                          查不到 → 创建（不传 FBillNo）→ Submit → 回存返回单号
  3. 回存失败 → 本次同步按失败收口（防重试重复开单），日志/告警带单号
```

并发防护不靠 ERP 侧单号，靠 WMS 本地状态机（`claimForSync` 同步中认领，已有）。

回存职责在 **writer 内部**（持对应 mapper，先例：stocktake/defect writer），
调用点（sync service/gateway/controller）零改动。

### 各 writer 落点

| writer | 回存字段 | 备注 |
|---|---|---|
| InboundWriter | `wms_production_inbound.erp_bill_no` | 成品 Save 分支去掉 FBillNo；Push 分支保存后回存返回单号 |
| PickWriter | `wms_outbound_order.erp_bill_no`（新） | |
| OutstockWriter | `wms_outbound_order.erp_bill_no`（新） | |
| StocktakeWriter | `wms_stocktake_order.erp_bill_no` | 盘盈/盘亏两张单，分号拼接回存；`markErpWriteback` 不再写该字段 |
| TransferWriter | `wms_transfer_order.erp_bill_no` | 保留不启用（跨库归金蝶）；去掉 FBillNo；`submitOne` mock 路径不再写 WMS 单号进 erpBillNo |

## 4. 兼容与风险

- **存量兼容**：上线时本地 `erp_bill_no` 全空，但金蝶里已有按 WMS 单号开的单据；
  兼容查询（步骤 2）接住它们，避免重复开单。兼容查询保留至数据稳定（至少一个盘点/出库周期）。
- 盘点 writer 原本只 Save 不 Submit——本次维持不动（审批仍走金蝶流程中心）。
- TransferWriter 不启用、无法真机验证，改造按模式对齐即可，标注未验证。

## 5. 改动清单

| 层 | 改动 |
|---|---|
| Flyway | `V3.9.5_33__wms_outbound_erp_bill_no.sql` |
| sync | 5 个 writer 按统一模式改；Inbound/Pick/Outstock/Stocktake 注入对应 mapper |
| service | `WmsStocktakeServiceImpl.markErpWriteback` 不再写 erpBillNo；`WmsTransferSyncServiceImpl.submitOne` 不再写 WMS 单号 |
| 单测 | 5 个 writer test：断言不再传 FBillNo、幂等/回存/兼容查询用例 |
| 前端 | 出库/盘点/调拨详情显示金蝶单号（最小改动） |

## 6. 验收口径

1. 单测全绿；每 writer 至少覆盖：不传 FBillNo / erpBillNo 幂等 / 兼容查询回存 / 回存失败即失败；
2. 金蝶侧单据编号全部由金蝶生成（SCRK…/SOUT…/QTCK…/QTRK…）；
3. 真机验证随下一轮联调（后端重启后）一并执行。
