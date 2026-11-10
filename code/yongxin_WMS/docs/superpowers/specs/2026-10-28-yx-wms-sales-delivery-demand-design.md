# 永鑫WMS — 销售出库待办（金蝶发货通知单）设计与验收

- 日期：2026-10-28
- 状态：已实现（用户确认口径：只涉 101 组织 / 等 C 已审核 / 允许分批多次生成；仓库与车间主数据未建，映射先预留接口）
- 前置探测：`2026-10-28-yx-wms-sales-delivery-notice-probe.md`
- 相关记忆：`kingdee-dept-warehouse-not-built`、`kingdee-erp-test-env`（只涉 101）

## 1. 口径（用户 2026-10-28 确认）

| 项 | 口径 |
|---|---|
| 组织 | 只处理 101 宜昌永鑫（`sales-delivery.org-number`，缺省取 `default-org-number`） |
| 单据状态 | **只取 C（已审核）**，B（已提交）不生成 |
| 数量 | 分录 `FRemainOutQty > 0`；本次 = 未出库数量 − WMS 在途已申请量 |
| 分批发货 | **允许同一行多次生成**（靠在途量扣减，不重复） |
| 仓库映射 | **预留接口**：金蝶车间/仓库主数据尚未建立，未命中映射即跳过该行并给可读原因 |

## 2. 实现

```
POST /wms/outbound/generate-sales-demands   （权限 wms:outbound:sales）
  └─> 查 SAL_DELIVERYNOTICE：FSaleOrgId=101 and FDocumentStatus='C' and FRemainOutQty>0
        └─> 按单号分组
              ├─ 仓库：WmsKingdeeStockMappingResolver.resolveWmsWarehouse(金蝶仓)
              │     ├─ 未配置/目标库区不存在/停用 → 跳过该行（原因含 sales-delivery.stock-map 提示）
              │     └─ 命中 → 继续
              ├─ 物料：WmsMaterialCodeResolver.reverseKingdeeCode（表列 → YAML → 原样）
              ├─ 数量：FRemainOutQty − 在途（同 source_bill_no、未完成/未关闭的销售出库单）
              └─> IWmsOutboundService.createSalesFromNotice（不依赖销售订单快照）
                     └─> 出库单：类型=销售出库、状态=待分配、source_bill_no=发货通知单号
```

### 关键设计点

- **不依赖销售订单快照**：探测发现发货通知的源销售订单未必已同步（`XSDD003944` 不在快照），
  新建 `createSalesFromNotice` 直接从通知单取客户/数量，不再走 `createSales` 的快照校验。
- **幂等靠 `source_bill_no`**：新增列（Flyway `V3.9.5_35`）+ 索引，记录 ERP 来源单号；
  在途量 = 该单号下未结束的销售出库单明细汇总，重复调用不会重复建单。
- **映射预留接口**：`WmsKingdeeStockMappingResolver` 只读配置；将来改为映射表落库时，
  替换该类实现即可，调用方（待办服务）不动。
- 逐行跳过不中断：一行映射缺失不影响同单其它行，也不影响其它单据。

## 3. 真机验证（2026-10-28，101 测试账套）

```
POST /wms/outbound/generate-sales-demands
→ 扫描发货通知单 60，生成出库单 0，跳过 74
→ 跳过原因全部为：「金蝶出货仓库[CK007/Y.41/S.09/H.01/…]未配置 WMS 库区映射
   （sales-delivery.stock-map；金蝶仓库主数据尚未建立，属预期内跳过）」
```

符合预期：接口跑通、口径生效（只扫 C 态、只算未出库量），映射为空时**零副作用**（未建任何单据）。

## 4. 待业务补齐后才能产出真实待办

在 `application-dev.yml` 的 `wms.sync.erp.kingdee.sales-delivery.stock-map` 登记
「金蝶出货仓库编码: WMS 库区编码」（如 `"S.05": "MJ"`），即可生成真实待办，无需改代码。

同时仍需注意：发货通知里的物料可能不在 `wms_material`（探测命中 0/4），
这类行会在 `createSalesFromNotice` 的物料校验处失败并记原因——需先扩物料同步范围或补档。

## 5. 本次未做

- 定时任务：目前只有手工接口（待映射配好、确认产出正常后再挂到销售订单同步任务后，
  避免每天刷出大量「映射未配置」的跳过记录）
- 组织 100：按用户口径不涉 100，直接跳过
