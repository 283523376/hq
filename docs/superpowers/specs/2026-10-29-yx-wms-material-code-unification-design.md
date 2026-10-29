# 物料编码统一（消除物料双身份）设计方案 — B2

- 日期：2026-10-29
- 状态：**待决策，尚未执行**
- 前置：B1（解析优先级修正 + 双身份监控）已于 2026-10-29 落地，commit `98f24ce8`
- 相关：`WmsMaterialCodeResolver`、`WmsKingdeeMaterialSyncServiceImpl`、记忆 `ck-004-cross-warehouse-scope`

---

## 1. 问题

物料同步（`WmsKingdeeMaterialSyncServiceImpl.upsertMaterial`）会把金蝶物料**原样**建成 WMS 物料：
`material_code = 金蝶 FNumber`、`kingdee_code = 自身（恒等映射）`。

而 WMS 里同时存在**手工/YAML 映射**的历史物料。于是同一条金蝶物料有两套 WMS 身份：

| WMS 编码 | kingdee_code | 库存 |
| --- | --- | --- |
| `FG001` | `NULL`（YAML → `WA.2205.2200.03000550.B003`） | **7** |
| `WA.2205.2200.03000550.B003` | 自映射 | 0 |
| `PBC001` | `NULL`（YAML → `WA.2206.2201.02000430.A003`） | **35** |
| `WA.2206.2201.02000430.A003` | 自映射 | 0 |

**库存在 YAML 映射的那条，自映射记录是空壳。**

B1 已把 `reverseKingdeeCode` 改成 YAML 优先，解析不再走空壳；但**库里两条记录的根因仍在**：
- 新同步的物料只要在 YAML 里出现过，仍会再建一条自映射记录（B1 只告警不阻断）
- 两套编码并存，报表/对账会看到「同一物料两行」

## 2. 目标 / 非目标

### 目标

1. 每一条金蝶物料在 WMS 只有**一条**物料记录，`material_code = kingdee_code = 金蝶 FNumber`（与「仓库编码 1:1」的既有决策同构）。
2. 迁移历史业务数据，使库存、单据、工单都指向统一后的记录。
3. 删除 YAML 的 `material-map` / `material-product-type` 两处映射（产品类型改由物料表的 `kingdee_product_type` 列承载）。
4. 迁移后 B1 的 YAML 优先逻辑自然失效（YAML 空了），`reverseKingdeeCode` 退化为纯表列解析——**代码无需回改**。

### 非目标

- 不改金蝶侧任何数据
- 不改 `WmsMaterialCodeResolver` 的接口语义（B1 已改好）
- 不处理「同一金蝶物料在 WMS 有 3 条以上记录」的情况（当前不存在）

## 3. 影响面（2026-10-29 实测）

### 3.1 需要迁移的表（引用 `material_id` / `material_code` 的全部表）

| 表 | 命中行数 | 备注 |
| --- | --- | --- |
| `wms_production_inbound` | 24 | 生产入库单 |
| `wms_work_order` | 22 | 工单（含 `material_code` 冗余列） |
| `wms_inventory` | 21 | 库存 |
| `wms_outbound_detail` | 12 | 出库明细 |
| `wms_transfer_detail` | 3 | 调拨明细 |
| `wms_sales_order_detail` | 1 | 销售订单快照明细 |
| `wms_inbound_detail` / `wms_stocktake_detail` / `wms_inventory_warning` / `wms_warning_rule` | 0 | 无数据，但迁移脚本仍要覆盖（防现场有） |

合计约 **83 行**。

### 3.2 关键前置事实

- **重复物料（`WA…` 两条）自身没有任何 `wms_inventory` 行** → 合并库存不存在唯一键冲突（`uk_wms_inv_mat_loc_batch_container`）。
- 生产代码中**没有**硬编码 `FG001`/`PBC001`（只有单测把它当任意 fixture，自建设置 YAML，不受影响）。
- YAML 两处映射：
  - `wms.sync.erp.kingdee.master-data.material-map`：`PBC001 → WA.2206…A003`、`FG001 → WA.2205…B003`
  - `material-product-type`：`PBC001 → 半成品`、`FG001 → 成品`

## 4. 迁移方案

### 4.1 映射

| 旧（保留方） | 新（唯一记录） | 产品类型（迁入 `kingdee_product_type`） |
| --- | --- | --- |
| `FG001` | `WA.2205.2200.03000550.B003` | 成品 |
| `PBC001` | `WA.2206.2201.02000430.A003` | 半成品 |

> 选择「保留方 = FG001/PBC001」还是「保留方 = WA…」是本节唯一的重大决策，见 §6 D1。

### 4.2 执行顺序（必须在单个事务内）

```sql
START TRANSACTION;

-- 0) 前置校验：重复物料必须零库存
SELECT COUNT(*) FROM wms_inventory i JOIN wms_material m ON m.id = i.material_id
 WHERE m.material_code = 'WA.2205.2200.03000550.B003' AND i.quantity <> 0;   -- 必须 = 0

-- 1) 产品类型落库（否则删掉 YAML 后工单产品类型会丢）
UPDATE wms_material SET kingdee_product_type = '成品'
 WHERE material_code = 'WA.2205.2200.03000550.B003';
UPDATE wms_material SET kingdee_product_type = '半成品'
 WHERE material_code = 'WA.2206.2201.02000430.A003';

-- 2) 逐表迁移 material_id（示例：库存）
UPDATE wms_inventory SET material_id = @new_id_fg001
 WHERE material_id = @old_id_fg001;
-- … 对 §3.1 每张表重复（含 material_code 冗余列，见 D2）

-- 3) 停用旧物料
UPDATE wms_material SET status = '0' WHERE material_code IN ('FG001','PBC001');

COMMIT;
```

> 用 `Flyway` 落地时按项目惯例写成 `V3.9.5_43__wms_material_code_unify.sql`，
> 并且**必须幂等**（`WHERE material_code='FG001'` 这类条件天然幂等；重复执行影响 0 行）。

### 4.3 配置清理（代码改动）

1. 删除 `application-dev.yml` 的 `material-map` 与 `material-product-type` 两段（以及生产 profile 里的同名配置）。
2. `WmsKingdeeProperties` 保留字段但置空（避免反序列化报错）；后续可整体移除。
3. 保留 B1 的 YAML 优先逻辑：YAML 为空时自然退化为表列解析，**不需要回改代码**。

### 4.4 回滚

迁移前 `mysqldump` 相关 6 张表；回滚 = 恢复备份 + 恢复 YAML 配置。因为旧物料只被**停用**而未删除，
数据本身可追回。

## 5. 验收标准

1. `SELECT COUNT(*) FROM wms_material WHERE material_code IN ('FG001','PBC001') AND status='1'` = 0
2. 两对物料的库存、单据行数迁移前后**总数一致**（迁移前 83 行 → 迁移后仍 83 行，只是 `material_id` 变了）
3. `/wms/outbound/pick/scan?workOrderNo=FG001173` 仍返回 `matched=true`（产品类型与物料解析都不丢）
4. 金蝶物料同步跑一轮：`wms_material` 总行数**不再增加**（不再产生空壳）
5. 全量单测绿

## 6. 待决策项（执行前必须确认）

| # | 决策 | 选项 | 影响 |
| --- | --- | --- | --- |
| **D1** | 保留哪套编码？ | (a) 保留 `FG001`/`PBC001`，把 `WA…` 记录并入并停用<br>(b) 保留 `WA…`，把 `FG001`/`PBC001` 并入并停用 | (a) 改动小（库存留在原地，只迁 `WA…` 的 0 行）；但违背「编码=金蝶编码」的口径，以后还会再遇到<br>(b) 符合 1:1 口径、治本；但要把 83 行全迁过去 |
| **D2** | 历史单据的 `material_code` **快照列**改不改？ | (a) 一起改成新编码（列表显示一致）<br>(b) 保留原快照（历史单据仍显示 FG001） | (a) 语义上是「篡改历史快照」；<b>(b) 会出现同一物料在列表里显示两种编码</b> |
| **D3** | 旧物料删除还是停用？ | 停用（`status=0`，可回滚） / 删除 | 建议停用 |
| **D4** | 何时执行？ | 现在（测试数据）/ 现场真实数据进来后 / 上线前 | 现在做等于拿测试数据练手，真实数据进来后要再做一遍 |

**建议**：D1 选 **(b) 保留 WA…**、D2 选 **(a) 一起改**、D3 **停用**、D4 **等现场真实数据进入后、上线前执行一次**。
理由：物料编码 1:1 是既定方向（与仓库同构），现在就按最终形态做，避免二次迁移；D2 若保留旧快照，
财务/仓库在列表里会看到「同一个东西两个编码」，解释成本高于快照语义的坚持。

## 7. 执行前检查清单

- [ ] D1–D4 已确认
- [ ] 6 张表已备份（`mysqldump`）
- [ ] 重复物料零库存已复核（§4.2 步骤 0）
- [ ] `material-product-type` 已先落库（否则删 YAML 后工单产品类型丢失）
- [ ] 迁移脚本按 `V3.9.5_43` 编号，内容幂等
- [ ] 迁移后跑一轮金蝶物料同步，确认 `wms_material` 行数不增加
