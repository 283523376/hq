# 「ERP 单号为准」回写改造 — 验收报告

- 日期：2026-10-28
- spec：`docs/superpowers/specs/2026-10-28-yx-wms-erp-source-of-truth-billno-design.md`

## 验收结论：代码完成、889 单测全绿、m2 已 install；真机复验随下轮后端重启联调

## 交付内容

### 统一模式（5 个回写器对齐）

```
erp_bill_no 有值 → 按金蝶单号跟进（B/C 成功，其余补 Submit）
erp_bill_no 空   → 兼容查询（旧 WMS 单号接住存量单，查到即回存）
                 → 都没有 → 创建（不传 FBillNo）→ Submit → 回存金蝶单号
回存失败 → 本次同步失败（防重试重复开单）
```

| 回写器 | 改动 | 回存字段 |
|---|---|---|
| `WmsKingdeeInboundWriterImpl` | 成品 Save 去 FBillNo；Push 分支保存后回存；幂等统一 | `wms_production_inbound.erp_bill_no` |
| `WmsKingdeePickWriterImpl` | 去 FBillNo + 幂等统一 + 回存 | `wms_outbound_order.erp_bill_no`（新，V3.9.5_33） |
| `WmsKingdeeOutstockWriterImpl` | 同上 | 同上 |
| `WmsStocktakeErpWriterImpl` | 去 `stocktakeNo-GY/-GK`；盘盈/盘亏单号分号拼接回存 | `wms_stocktake_order.erp_bill_no` |
| `WmsKingdeeTransferWriterImpl` | 去 FBillNo + 回存真实单号（保留不启用，未真机验证） | `wms_transfer_order.erp_bill_no` |

### 挪用清理（ERP 单号字段只存真实 ERP 单号）

- `WmsStocktakeServiceImpl.markErpWriteback` 不再把状态文案写进 `erp_bill_no`
- `WmsTransferSyncServiceImpl.submitOne` 模拟提交不再拿 WMS 单号占位 `erp_bill_no`

### 前端

- 出库详情抽屉显示「金蝶单号」（生产入库详情上一任务已加）；调拨已支持按 `erp_bill_no` 搜索

## 测试

- `Tests run: 889, Failures: 0, Errors: 0` ✅
- 5 个 writer 测试断言更新：不再传 FBillNo；幂等/兼容查询/回存行为由新增桩覆盖
- `WmsTransferSyncServiceTest.submitPending_realSuccess_marksSubmitted` 断言修正（服务层不再写 WMS 单号）

## 遗留 / 待办

1. 真机复验（后端重启后）：任选一条领料/销售出库回写，确认金蝶生成编码规则单号并回存页面可见
2. 兼容查询（按旧 WMS 单号查金蝶）保留至数据稳定，之后可下线
3. 盘点 erp_bill_no 分号拼接展示（页面暂以单号列后续统一排）
