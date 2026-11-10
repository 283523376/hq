# 物料主数据同步与编码映射落库 — 验收报告

- 日期：2026-10-28
- spec：`docs/superpowers/specs/2026-10-28-yx-wms-material-master-sync-design.md`
- 依据：记忆 `wms-automation-first-principle` P0（物料主数据纯手工是最大违规点）

## 验收结论：代码完成、900 单测全绿、m2 已 install；真机同步待后端重启后验证

## 交付内容

### 物料主数据同步（ERP → WMS）

- 新增 `IWmsKingdeeMaterialSyncService` + `WmsKingdeeMaterialSyncServiceImpl`：
  分页拉 `BD_MATERIAL`（按 `master-data.org-id` 过滤使用组织），按 `material_code = 金蝶 FNumber`
  upsert 到 `wms_material`，`kingdee_code` 恒等；
- 挂进 `WmsMasterDataSyncJob`（仓库→分类→物料，互不阻断）；
- 新增接口 `POST /wms/material/sync-from-kingdee`（权限 `wms:material:add`，`@AutoLog`）。

### 编码映射落库

- Flyway `V3.9.5_34__wms_material_kingdee_code.sql`：
  `wms_material` 加 `kingdee_code`、`kingdee_product_type`；
- 新增 `WmsMaterialCodeResolver`（三级优先：表列 → YAML → 原样/null，另含反向 `reverseKingdeeCode`）；
- 7 处调用点全部改走 resolver：
  `DefectWriter` / `InboundWriter` / `OutstockWriter` / `PickWriter` / `TransferWriter` /
  `PpbomReader`（反向）/ `WorkOrderSync`（productType + 反向）；
- YAML `material-map` / `material-product-type` 保留为兜底，存量部署零迁移。

### 前端

- 物料页「从金蝶同步」按钮（二次确认 + 结果按 message 提示，跳过走 warning）；
- 列表加「金蝶编码」列，表单加「金蝶编码 / 产品类型」字段。

## 测试

- `Tests run: 900, Failures: 0, Errors: 0` ✅（新增 resolver 6 例、sync service 5 例）
- 既有 7 个测试类的构造器/桩同步更新（resolver 桩按 YAML 语义，保持原断言有效）

## 设计取舍

- **不做分类匹配**：WMS 分类与金蝶物料分组是两套体系，强配会造错数据；
- **不做删除**：金蝶侧停用/删除的物料只跳过，本地保留（防误删 WMS 侧引用）；
- **产品类型不由金蝶推**：金蝶 `FProductType` 是主/联/副产品，不是半成品/成品，仍人工维护（已落库为可维护字段）。

## 遗留

1. 真机：后端重启后点一次「从金蝶同步」，确认物料落库与页面展示；
2. 存量 14 条手工物料（M001 系）需人工在页面补 `kingdee_code`（或继续用 YAML 兜底）；
3. 物料同步量级未知，首次同步若超过 200 页会截断（日志告警），届时调整 `MAX_PAGES`。
