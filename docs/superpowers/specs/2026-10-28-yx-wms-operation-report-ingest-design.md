# 永鑫WMS — 工序汇报落半成品入库草稿 设计文档

- 日期：2026-10-28
- 状态：已实现（阶段 C），用户已批准「先落地 C，等后续再切 B」
- 关联：`2026-10-28-yx-wms-inventory-warning-batch-design.md`、记忆 `wms-warehouse-mirrors-kingdee`

## 1. 背景与口径

用户明确（2026-10-28）：

> 金蝶 ERP 里没有成品、半成品之分，**半成品是工序汇报产生的库存，成品是完工入库**。

所以产品类型不是物料属性，而是「这批货从哪道手续产生的」：

| 来源 | 产品类型 | 金蝶单据 |
|---|---|---|
| 工序汇报 | 半成品 | `SFC_OperationReport`（GXHB*，页面：智慧车间MES → 智慧车间 → 车间报工执行） |
| 完工入库 | 成品 | `PRD_INSTOCK`（现有链路） |

## 2. 阶段划分

| 阶段 | 内容 | 状态 |
|---|---|---|
| **C（本次）** | 拉取工序汇报 → 生成 WMS **半成品入库草稿**：不分配库位、不下发 WCS、不回写金蝶 | ✅ 已实现 |
| B（后续） | 草稿推进到真实物理库区（D.02 半成品立库 / D.01 敏捷仓），走库位分配 + WCS + 回写 | 待仓库与库位方案确定 |

## 3. 实现

### 3.1 数据

`wms_production_inbound` 加 `erp_entry_key`（`金蝶单号#工序序列#工序号`）+ 唯一索引
（一段工序汇报单有多个分录行，只用单号去重会互相覆盖）。Flyway `V3.9.5_38`。

新增状态常量 `PROD_STATUS_DRAFT = "草稿"`。

### 3.2 读取

`WmsKingdeeOperationReportReader` 查 `SFC_OperationReport`：

```
FilterString = FDocumentStatus='C' AND FPrdOrgId.FNumber='101' AND FDate 区间
FieldKeys    = FBillNo,FDate,FPrdOrgId.FNumber,FWorkShopID.FName,
               FMaterialId.FNumber,FMaterialId.FName,FMoNumber,FLot_Text,
               FQuaQty,FSeq,FOperNumber,FStockId.FNumber,FStockId.FName
```

> 字段必须带 `F` 前缀，否则金蝶报「元数据中标识为 xxx 的字段不存在」。

### 3.3 落库规则

| 情况 | 处理 |
|---|---|
| 合格数量 ≤ 0 | 跳过 |
| `FStockId` 为空 | 跳过（只汇报未入库，货还在车间） |
| 入库仓是「外部仓」（CK007 VMI） | 跳过（货权不在自己） |
| 物料不在 `wms_material` | 跳过并报编码 |
| 同 `erpEntryKey` 已存在且状态=草稿 | 刷新数量/批次 |
| 同 `erpEntryKey` 已存在且状态已推进 | 跳过（不覆盖现场作业） |
| 其余 | 新建草稿 |

草稿字段：`productType=半成品`、`status=草稿`、`identType=RFID`、`erpBillNo=GXHB*`、
`inboundNo=单号-序列-工序号`、`manualRemark` 记录金蝶入库仓与车间、**`locationId`/`wcsTaskNo` 为空**。

### 3.4 接口

`POST /wms/production/sync-operation-report?startDate=&endDate=&maxRows=`
（权限 `wms:production:manual`）

## 4. 非目标

- 不分配库位、不下发 WCS、不回写金蝶（阶段 B 再做）
- 不做工序汇报的增量游标（按日期区间手工触发，后续接定时任务时再定）
- 不处理「工序汇报入成品仓」的特殊情况（按来源一律判半成品，待业务细化）
