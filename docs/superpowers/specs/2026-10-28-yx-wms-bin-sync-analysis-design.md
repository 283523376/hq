# 金蝶仓位（库位）信息探测与 WMS 同步方案设计

- 日期：2026-10-28
- 性质：**只读探测 + 方案设计**（未改任何金蝶数据、未提交实现代码）
- 关联：`2026-10-28-yx-wms-sales-delivery-notice-probe.md`、记忆 `kingdee-dept-warehouse-not-built`

## 1. 金蝶侧有没有仓位基础资料：**有**

| 对象 | 说明 |
|---|---|
| `BD_FLEXVALUES` | **仓位值集**（表单名「仓位值集」）：一个值集 = 一套仓位维度，分录 `FEntity` 下挂具体仓位值（`FFlexValueNumber` 编码 / `FFlexValueName` 名称 / `FForbid` 禁用） |
| `BD_FLEXVALUESDETAIL` | 值集资料维度数据（维度明细） |
| `BD_STOCK.FIsOpenLocation` | 仓库是否启用仓位管理 |
| `BD_STOCK.FStockFlexItem` / `FStockFlexDetail` | 仓库启用的仓位维度 / 具体仓位值 |
| `BD_STOCKLOC` | **不存在**（无此业务对象，别按这个名字查） |

### 实测数据（2026-10-28，只读）

| 组织 | 仓库数 | 启用仓位管理的仓库 |
|---|---|---|
| 100 江西兆鑫 | 19 | **11**（02.001、02.002、02.011、02.013.001~004、02.014、02.015 等） |
| **101 宜昌永鑫** | **81** | **0**（`FIsOpenLocation` 全为 false） |

已有仓位值集：`001`（组织 100，仓位 01=A / 02=B / 03=C）、`002`（100）、`003`（**组织 104**）。

**结论：金蝶确实有仓位基础资料并已在使用（100 组织），但 101 宜昌永鑫一个仓位都没建**——
与用户所说「101 相关仓库尚未建立」一致（仓库有 81 个，但都未启用仓位管理）。

## 2. WMS 现状：仓库同步了，**仓位完全没同步**

| 维度 | 现状 | 证据 |
|---|---|---|
| 仓库 | ✅ 已同步 | `wms_warehouse.erp_stock_code / erp_stock_name`，由仓库档案同步（BD_STOCK）写入 |
| 仓位/库位 | ❌ **没有同步** | `wms_location` 只有 `locationCode/rowNo/colNo/layerNo` 等**WMS 自建**字段，**没有任何 ERP 映射列**；全代码库无仓位同步服务 |
| 回写时怎么拿到仓位 | ⚠️ 临时解析 | 靠 YAML `stock-loc`（仓库编码→仓位编号，**一仓只能配一个仓位**），回写时再 `View BD_FLEXVALUES` 把编号翻成内码（`WmsKingdeeLookupSupport.stockLocId`） |

### 由此产生的三个实际缺口

1. **无反查**：金蝶里建了仓位，WMS 不知道；WMS 建的库位，金蝶也不知道（两套库位各自为政）
2. **一仓一仓位**：`stock-loc` 是 `Map<仓库, 仓位>`，只能表达「这个仓只用一个仓位」；
   真实业务里一个仓有多个仓位（如 02.001 有 01/02/03），现设计表达不了
3. **配置漂移**：仓位编号写死在 YAML，金蝶侧改仓位/停用仓位时，WMS 只在回写报错时才发现

## 3. 方案设计：金蝶仓位 → WMS 库位同步（预留，等 101 建仓后启用）

### 3.1 数据模型

`wms_location` 增加三列（Flyway）：

| 列 | 说明 |
|---|---|
| `erp_stock_code` | 所属金蝶仓库编码（对应 `wms_warehouse.erp_stock_code`） |
| `erp_flex_number` | 金蝶仓位编码（`BD_FLEXVALUES.FEntity.FFlexValueNumber`） |
| `erp_flex_id` | 金蝶仓位内码（`FLEXVALUESENTRY[].Id`，回写 `FStockLocId` 直接用它，省掉每次 View 解析） |

唯一约束：`(erp_stock_code, erp_flex_number, deleted_at)`；`locationCode` 生成规则
`{金蝶仓库编码}-{仓位编码}`（如 `02.001-01`），避免与 WMS 自建库位编码冲突。

### 3.2 同步流程

```
IWmsKingdeeLocationSyncService.syncLocations()
  └─> 1. 读 BD_STOCK：FNumber, FUseOrgId.FNumber, FIsOpenLocation  （按组织过滤，默认 101）
        └─> 只处理 FIsOpenLocation = true 的仓库
  └─> 2. 每个仓库取其仓位值集：BD_STOCK.FStockFlexItem.FFlexId → BD_FLEXVALUES.FNUMBER
  └─> 3. View BD_FLEXVALUES：取 FLEXVALUESENTRY[] {Number, Name, Id, Forbid}
  └─> 4. upsert wms_location：
        ├─ 按 (erp_stock_code, erp_flex_number) 找
        ├─ 不存在 → 新建（locationCode={仓}-{仓位}，status=1，warehouseId=本地仓库）
        ├─ 存在 → 更新名称/启用状态（金蝶 Forbid → WMS status=0）
        └─ 金蝶侧消失的仓位 → **不删除**，置 status=0 并记日志（避免删掉有库存的库位）
  └─> 5. 返回统计（扫描仓库 / 新建 / 更新 / 停用），写 sync_log
```

**幂等**：以 `(erp_stock_code, erp_flex_number)` 为业务键；重复执行只更新不重建。

### 3.3 接口

| 类型 | 定义 |
|---|---|
| 服务 | `IWmsKingdeeLocationSyncService.syncLocations()` → `WmsKingdeeStockSyncResultVO`（复用既有 VO） |
| HTTP | `POST /wms/warehouse/location/sync-from-kingdee`（权限 `wms:location:add`，`@AutoLog`） |
| 定时 | 挂进 `WmsMasterDataSyncJob`（仓库 → 分类 → 物料 → **仓位**），受 `sync.auto` 闸门 |

### 3.4 回写侧改造（同批做，否则同步没有收益）

`WmsKingdeeLookupSupport.stockLocId(stockCode)` 改为三级：

1. 按 WMS 库位 id 查 `wms_location.erp_flex_id`（新，最快、最准）
2. 按 YAML `stock-loc` 现查 `BD_FLEXVALUES`（现行逻辑，兜底）
3. 都没有 → null（不传 `FStockLocId`，由金蝶报「仓位不能为空」）

同时把出库/入库明细的库位写入 `FStockLocId`（现在只有入库传、出库/领料不传，多仓位仓库会出错）。

## 4. 前置条件与阻塞（现在做会空转）

1. **101 组织必须先把仓库建好并启用仓位管理**（当前 81 个仓库全部 `FIsOpenLocation=false`，值集也没有 101 的）——
   在此之前同步扫描结果必然是「0 个启用仓位的仓库」
2. 需业务确认 **WMS 库位与金蝶仓位的粒度是否 1:1**：
   - 若 1:1 → 方案如上，WMS 库位由金蝶驱动
   - 若 WMS 的「行/列/层」立体货位是**物理货位**、金蝶仓位是**逻辑库位**（如 01/02/03）
     → 需要多一层映射（`wms_location.erp_flex_number` 多对一），同步规则要改
3. 库位被盘点冻结 / 有库存时的停用策略：建议「有库存不允许停用」，只记待处理

## 5. 建议的实施顺序

1. 先落**表结构 + 回写侧三级解析**（不依赖金蝶数据，能立即提升回写的健壮性）
2. 再落**同步服务 + 手工接口**（此时空转也没副作用，日志会说明「0 个启用仓位的仓库」）
3. 等 101 建仓、启用仓位后，打开定时任务即可自动同步

需要我按这个顺序开工吗？第 1 步现在就能做且不依赖任何外部数据。
