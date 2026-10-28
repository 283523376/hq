# 永鑫WMS — 物料主数据同步与编码映射落库 设计文档

- 日期：2026-10-28
- 状态：已批准（任务队列 #2，依据记忆 `wms-automation-first-principle` P0：物料主数据纯手工是最大违规点）
- 关联：`2026-10-28-yx-wms-erp-source-of-truth-billno-design.md`

## 1. 问题

1. `wms_material` 纯手工维护，金蝶 `BD_MATERIAL` 没有同步进来（分类/仓库已同步，物料反而没有）；
2. WMS码→金蝶码映射在 `application-dev.yml` 的 `material-map`（改配置要重启），且是同步链路单点
   （7 处回写/读取代码靠它，没配就原样透传金蝶→查不到→失败）；
3. `material-product-type`（半成品/成品）同样在 YAML。

## 2. 方案

### 2.1 物料同步（ERP → WMS）

`WmsKingdeeMaterialSyncServiceImpl.syncMaterial()`（挂进 MasterDataSyncJob + 物料页手工按钮）：

- 分页拉 `BD_MATERIAL`（复用 category sync 的分页/过滤模式，按 `master-data.org-id` 过滤使用组织）；
- upsert `wms_material`：`material_code = 金蝶 FNumber`（**恒等映射**，`kingdee_code` 同值），
  名称/规格/基本单位更新（金蝶为准，不覆盖空值策略：非空即更新）；
- **不做**：分类匹配（WMS 分类体系与金蝶物料分组是两套，留人工）、删除（金蝶停用物料只跳过不删本地）；
- 结果 VO 复用 `WmsKingdeeStockSyncResultVO`，写 sync log，模式与仓库/分类同步一致。

### 2.2 编码映射落库 + 解析层

- `wms_material` 加列：`kingdee_code`（金蝶物料编码）、`kingdee_product_type`（半成品/成品，人工/导入维护，
  金蝶无此概念）；
- 新建 `WmsMaterialCodeResolver`（sync 包）：
  - `resolveKingdeeCode(wmsCode)`：表列 `kingdee_code` 非空 → 用它；否则 YAML `material-map` 兜底；
    再否则原样返回（现行为）；
  - `resolveProductType(wmsCode)`：表列 `kingdee_product_type` → YAML `material-product-type` → null。
- **7 处调用点改走 resolver**：Defect/Inbound/Outstock/Pick/Transfer writer、PpbomReader、
  WorkOrderSync（productType）。YAML 保留为兜底，存量部署零迁移成本。

### 2.3 前端

物料页：加「金蝶编码」列与编辑字段、「从金蝶同步」按钮（successMessageMode 惯例与其它同步按钮一致）。

## 3. 改动清单

| 层 | 改动 |
|---|---|
| Flyway | `V3.9.5_34__wms_material_kingdee_code.sql` |
| entity | `WmsMaterial` + `kingdeeCode` / `kingdeeProductType` |
| sync | `WmsMaterialCodeResolver`（新）；5 writer + PpbomReader + WorkOrderSync 改走 resolver |
| service | `IWmsKingdeeMaterialSyncService` + Impl（分页拉取 upsert） |
| job/controller | MasterDataSyncJob 挂物料；`WmsMaterialController` 加 `/syncFromKingdee` |
| 前端 | material/index.vue + material.data.ts + api |
| 单测 | resolver、sync service |

## 4. 验收口径

1. 单测全绿；resolver 三级优先有覆盖；
2. 物料页可手工触发同步，金蝶物料落库且 `kingdee_code` 恒等；
3. 存量 14 条手工物料不受影响，YAML 映射继续兜底生效。
