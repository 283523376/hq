# 永鑫WMS — KC-001 库存预警与批次管理 验收报告

- 日期：2026-10-28
- 设计：`docs/superpowers/specs/2026-10-28-yx-wms-inventory-warning-batch-design.md`
- 结论：**通过**（单测 968 全绿；后端实跑接口 + 生命周期全链路验证完毕）

## 1. 交付内容

### 1.1 数据库（Flyway `V3.9.5_37__wms_inventory_warning.sql`）

| 变更 | 说明 |
|---|---|
| `wms_inventory` + `first_inbound_time` | 超期预警起算点；存量按 `create_time` 回填（31 行全部回填成功） |
| 新表 `wms_warning_rule` | 规则：类型 `LOW_STOCK`/`OVERDUE`、作用域 `MATERIAL`/`CATEGORY`/`ALL`、仓库可选 |
| 新表 `wms_inventory_warning` | 预警单：规则/物料快照、`dedup_key`、`hit_count`、处理留痕 |
| 菜单 | 库存预警（sort=95）、批次追溯（sort=96）+ 7 个按钮权限 + admin 授权 |

> `wms_inventory` 原表已存在且已有 `batch_no`（`V3.9.5_11`），本次未重建。

### 1.2 后端

- entity：`WmsWarningRule`、`WmsInventoryWarning`；`WmsInventory` 扩 `firstInboundTime`
- mapper：`WmsWarningRuleMapper`、`WmsInventoryWarningMapper`（聚合/超期查询）、`WmsBatchTraceMapper`（批次库存 + 5 类单据 UNION 流转）
- service：`WmsWarningRuleServiceImpl`（校验）、`WmsInventoryWarningServiceImpl`（扫描/合并/处理）、`WmsBatchTraceServiceImpl`
- controller：`/wms/warningRule/*`、`/wms/inventoryWarning/*`、`/wms/inventory/batch/*`
- job：`WmsInventoryWarningScanJob` + Registrar（`wms.warning.scan.enabled` 默认 true，cron 默认每天 08:00）
- 接入改动：`upsertInventory` 只在 INSERT 写 `first_inbound_time`；调拨建目标行继承源行；盘盈建行取当前时间

### 1.3 前端

- `views/wms/warning/`：预警处理（含「立即扫描」）+ 规则配置（新增/编辑/删除、作用域联动）
- `views/wms/inventory/`：批次追溯（输入批次号 → 当前库存明细 + 流转记录）

## 2. 自动化测试

命令：

```
bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms
```

结果：**Tests run: 968, Failures: 0, Errors: 0**（原 926，本次 +42）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `WmsWarningRuleServiceImplTest` | 22 | 全部非法组合被拒、作用域互斥清理、重名/同目标唯一 |
| `WmsInventoryWarningServiceImplTest` | 14 | 下限/超期生成、零库存物料仍报、合并累加、解除后再报新建、处理校验 |
| `WmsBatchTraceServiceImplTest` | 4 | 批次号必填、条数上限 |
| `WmsTransferInventoryFirstInboundTest` | 2 | 移库继承源行首次入库时间 |
| `WmsControllerPermissionsTest` | 1 | 3 个新控制器全部写端点带权限码（已加入扫描清单） |

## 3. 真机验证（后端 8081，MySQL yongxin_wms）

### 3.1 迁移

- 启动日志：`Successfully applied 1 migration to schema 'yongxin_wms', now at version v3.9.5.37`
- `flyway执行sql脚本失败` 计数 = **0**
- `flyway_schema_history`：`3.9.5.37 success=1`
- `SHOW TABLES LIKE 'wms_warning%'` → `wms_warning_rule`、`wms_inventory_warning`

### 3.2 规则校验（非法拒绝保存）

| 请求 | 响应 |
|---|---|
| `thresholdQty=0` | `下限数量必须大于 0` |
| `OVERDUE` 带 `thresholdQty=5` | `存放超期规则不允许填写下限数量，请改用库存下限规则` |

### 3.3 扫描 / 合并 / 处理闭环

| 步骤 | 结果 |
|---|---|
| 建规则 PBC001 下限 100 | 成功（id `2115319333713170434`） |
| 第一次扫描 | `命中 1，新建 1`；预警 `currentQty=35 < 100` |
| 第二次扫描 | `命中 1，新建 0，合并 1`；库内仍 1 条，`hit_count=2` |
| 空说明处理 | 拒绝：`处理说明不能为空（留痕审计）` |
| 正常处理（REPLENISH） | 成功；`status=RESOLVED`、`handled_by` 落库、`dedup_key` 追加 `#R#1793166417089` |
| 重复处理 | 拒绝：`该预警已处理，请勿重复操作` |
| 再次扫描 | `新建 1` —— 已解除的记录没有被改回待处理 |

### 3.4 超期扫描

建 `OVERDUE / ALL / 1 天` 规则后扫描：`新建 31`，`overdue_days=1`，
最早 `first_inbound_time = 2026-10-19 11:06:36`，符合预期（逐库存行告警）。

### 3.5 批次追溯

| 请求 | 结果 |
|---|---|
| `stock?batchNo=TST-BATCH-06` | 返回多库位明细（废品仓 FPC-DEFAULT + 立体库 LB-R01-C01-L20），含数量/锁定/可用/首次入库 |
| `flow?batchNo=TST-BATCH-06` | 返回 `PROD_INBOUND` 事件（单号 `SC2114918805493628930`、工单 `WORK01058`、qty=2） |
| `flow?batchNo=E2E1792379196355-0` | 返回 `INBOUND` 事件（单号 `SL202610190001`、库位 `LB-R01-C01-L20`… qty=5） |
| `stock?batchNo=`（空） | `批次号不能为空` |

> 出库/调拨/盘点三类流转暂无带批次的历史数据（现有单据 `batch_no` 为空或无差异），
> UNION 分支已就位，待后续单据产生数据即可自动出现。

### 3.6 定时任务

`QRTZ_JOB_DETAILS` / `QRTZ_TRIGGERS` 已注册 `1920000000000000380`，
`NEXT_FIRE_TIME=1793232000000`（2026-10-29 08:00 本地时间）。

### 3.7 清理

验证产生的 2 条规则（走 API 删除，含重复删除被拒校验）与 34 条预警（SQL 清理）已删除，
`wms_warning_rule` / `wms_inventory_warning` 均为 0 行。

## 4. 已知边界 / 后续

1. **预警不自动解除**：库存下限恢复后，旧预警仍为 `PENDING`，需人工处理留痕（D7）。
   前端列表展示 `currentQty`，仓管可直接判断是否已恢复。
2. **`ALL` 作用域只盯有库存记录的物料**：否则会把全库零库存物料全部报一遍；
   要覆盖零库存物料请用 `MATERIAL` 或 `CATEGORY` 作用域。
3. **消息推送未做**：短信/企业微信/钉钉不在本次范围。
4. **出库/调拨/盘点流转暂无批次数据**：现有历史单据 `batch_no` 为空，接口已就位。
5. **预警无删除入口**：只提供「处理并解除」，避免误删审计记录。
