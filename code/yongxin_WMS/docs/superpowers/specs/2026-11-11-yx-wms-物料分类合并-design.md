# 物料分类合并设计（同名分类去重，分类→物料 1:N）

- 日期：2026-11-11
- 状态：设计稿（用户已确认三项口径）
- 背景：`wms_material_category` 现有 **2046 条分类、仅 715 个不同名称**；物料同步按
  `erp_group_number` **1:1 建分类**，每个金蝶分组生成一条同名分类（如 `接头` 有 8 条，
  分别对应 `HC.0101.0502.C` 等不同分组）。
- 目标：同名同类型分类合并为一条，物料统一挂到存活分类，形成「一个分类 → 多个物料」的 1:N 关系。

## 1. 已确认口径（2026-11-11 用户确认）

1. **合并键 = `category_name` + `category_type`**（同名不同类型不合并）；预计 2046 → **758**。
2. **编码策略**：每组保留一条**存活分类**的原 `category_code`，其余分类逻辑删除；
   金蝶组号与分类的对应关系写入**新映射表**，不丢追溯。
3. **本期范围**：设计 + Flyway 迁移脚本 + 开发库可回滚演练；生产库执行另行确认。

## 2. 现状事实（实测）

| 项 | 值 |
| --- | --- |
| 分类总数 / 不同名称 | 2046 / 715 |
| 按名称合并后 | 715 |
| 按名称+类型合并后 | 758 |
| 同名但类型冲突的名称 | 43 |
| 分类层级 | 全平铺（`parent_id` 全为 0，`has_child` 全为 0） |
| 无 `erp_group_number` 的分类 | 7 条（手工创建） |
| 物料总数 / 已挂分类 | 46846 / 46779 |
| 被引用的分类数 | 2041 |
| 现有唯一键 | `(category_code, deleted_at)`——**名称无约束**，这是重复产生的土壤 |

重复的根因：`WmsKingdeeMaterialSyncServiceImpl.loadCategoryIdByGroup()` 以
`category.erp_group_number` 建立「组号 → 分类 ID」，同步时若分组不存在就新建分类，
分类与分组天然 1:1。

## 3. 设计

### 3.1 新表：分组 → 分类映射

```sql
wms_material_category_group(
  id, group_number, group_name, category_id, deleted_at, create_by, create_time,
  UNIQUE KEY (group_number, deleted_at),
  KEY (category_id)
)
```

- `group_number` 取值：分类的 `erp_group_number`，为空时退化为原 `category_code`；
- 合并后**一个 category_id 可对应多个 group_number**，即 1:N 的映射层；
- 该表同时是 ERP 同步的**新数据源**（见 §4）。

### 3.2 合并算法（迁移脚本 `V3.9.5_60`）

1. 建三张辅助表：映射表、分类合并备份、物料改挂备份（备份表常驻，供回滚与审计）。
2. 计算存活分类：每个 `(category_name, IFNULL(category_type,''))` 分组内，按
   **物料数降序 → create_time 升序 → id 升序**取第一条（确定性）。
3. 物料改挂：`wms_material.category_id` 从被合并分类改到存活分类，改前逐行备份。
4. 写映射表：所有合并前的分类（含存活）都写一行 `组号 → 存活分类`，重复组号用
   `ON DUPLICATE KEY UPDATE` 收敛。
5. 逻辑删除被合并分类：`deleted_at = 当前毫秒时间戳`（不物理删除，可回滚）。

### 3.3 回滚脚本

- 物料：按备份表把 `category_id` 改回旧值；
- 分类：把被合并的分类 `deleted_at` 置回 0；
- 映射表：删除本次写入的行（`create_by='merge'`）。

## 4. 代码改造（第 2 阶段，防复发）

1. `WmsKingdeeMaterialSyncServiceImpl.loadCategoryIdByGroup()` 改为读
   `wms_material_category_group`（组号 → 分类），查不到时回退旧字段，保证兼容；
2. 同步遇到**未知分组**时的策略（二选一，待第 2 阶段确认）：
   - 建新分类（现状，会再次产生同名分类）；
   - 按「组名+类型」查找已有分类，找不到才建（推荐，天然防重复）。
3. 手工新增分类：后端校验同名同类型是否已存在，给出可读提示（不做唯一索引，
   因为逻辑删除下唯一索引无法表达）。

## 5. 验收

1. 迁移后 `SELECT COUNT(*) ... deleted_at=0` = **758**（按名称+类型）；
2. 不存在重复的 `(category_name, category_type)` 组合；
3. 物料总数不变、`category_id` 非空率不下降（46779）；
4. 映射表条数 = 合并前分类数（2046），且任一 `group_number` 唯一；
5. 回滚脚本执行后，分类数回到 2046、物料挂载与迁移前完全一致。

## 6. 风险

- **大批量更新**：约 4.6 万物料行改挂，需在业务低峰执行；脚本按主键批量更新，单条 SQL 完成。
- **ERP 同步未改前不要开同步**：否则会重新建出同名分类（阶段 2 必修）。
- **默认分类/演示数据**：若某分类被前端缓存或报表按 ID 引用，逻辑删除后需刷新缓存。
