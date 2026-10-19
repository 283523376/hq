# 永鑫WMS 采购入库流程（YX-WMS-RK-001）设计

日期：2026-10-19
状态：已与需求方对齐（单据合一、菜单结构），待实施
需求来源：`wms-blueprint/flows.json` 的 `YX-WMS-RK-001`（采购入库流程·原料/外购料），异常处理四条见蓝图 exceptions

## 1. 意图与背景

供应商送货到厂，仓管员检查外包装 → 扫码/录入采购订单号生成收料通知单 → 自动通知检验员 → 检验合格按物料属性分配库位（半成品→立库，辅料→线边柜）→ 确认上架数量、更新库存 → 生成采购入库单（未审核）→ 自动审核 → 同步 ERP（失败自动重试）。

验收标准：**完整走通采购入库全流程**；四个异常场景（包装损坏 / 检验不合格 / 库位不足 / ERP 同步失败）各有明确提示与处理路径。

## 2. 目标 / 非目标 / 反目标

**目标**：上述 8 步全部落地；异常四条全落地。

**非目标**（明确不做）：
- YX-WMS-RK-005 不良品流程本体——检验不合格仅做状态标记 + 明确提示「转不良品处理流程（RK-005）」，RK-005 另行立项
- 真实 ERP 对接——ERP 侧只做网关接口 + 可配置 URL + 未配置时模拟成功（保证流程闭环可演示）
- 站内消息/推送通知——「通知检验员」= 生成检验任务，检验员在任务列表看到
- 冲销/取消入库、盘点、序列号管理、部分检验（整单一次检验）

**反目标**（绝对不许出现）：
- 「界面显示成功但库存没入库」——库存更新与单据状态流转必须同事务
- 静默吞错——Flyway 失败要 grep 启动日志验证（有前科）；同步失败必须落库可见 + 告警

## 3. 约束

- 前后端正在运行；**改完 wms 模块必须 `install` + 重启后端 JVM 一次**（新接口生效的框架前提，需求方已接受）
- 只改 JeecgBoot 仓库（后端 wms 模块 + Flyway 迁移 + 前端页面）；遵循 `jeecg-boot/CLAUDE.md` 全部约定（Result<T>、wms_ 前缀、deleted_at 逻辑删除 + @TableLogic、无物理外键、mapper xml 放 src/main/java 下 mapper/xml、禁止魔法值进 WmsConstants）
- **删除守卫**：新增 `wms_inventory` 引用物料与库位后，必须在 `WmsMaterialService` / `WmsLocationService` 的 checkDelete 里追加 blocker（物料侧已预留空白框架）
- Flyway 脚本幂等（INSERT IGNORE / 带 name 条件的 UPDATE），沿用既有 ID 段：权限 `1910000000000000xxx`、角色授权 `1920000000000000xxx`；下一个版本号 `V3.9.5_11`
- 批量单测命令注意 `-Dsurefire.failIfNoSpecifiedTests=false`

## 4. 已定决策（与需求方对齐的结果）

| # | 决策 |
|---|------|
| 1 | **单据合一**：收料通知单与采购入库单是同一张 `wms_inbound_order`，走状态机流转（需求方拍板） |
| 2 | 菜单：「基础管理」显示名改「**基础数据**」（条件 UPDATE 防覆盖人工改名）；新增**平级目录「入库管理」**；其下两个菜单页：**采购入库**（`/wms/inbound`）、**检验任务**（`/wms/inbound/inspection`）；**库位分配是弹窗组件**（挂在采购入库单详情内），不占菜单 |
| 3 | ERP 同步范式照抄 `wms_order`：sync_status/retry_count/last_sync_error/sync_time 字段 + Gateway 接口 + Http 实现 + Quartz 补偿 Job + 种子行 |
| 4 | 用户 PRD 的两张表是核心，另配两张必需表：`wms_inspection_task`（检验任务列表的数据源）、`wms_inventory`（库存）；共 4 张新表 |
| 5 | 检验粒度为**整单级**：一张收料单一个检验任务，不合格整单转不良品（蓝图未要求按行检验，YAGNI） |
| 6 | 收料时采购订单仅做**存在性校验**（匹配 `wms_order.order_no` 且 order_type=采购订单），并带出供应商；明细物料由扫码录入并校验 `wms_material` 存在（现有 wms_order 无明细表） |

### 4.1 builder check 后补充的口径（2026-10-19 第二轮对齐）

| # | 口径 | 决定方 |
|---|------|--------|
| 7 | **作废**：状态机增加「作废」终态；待检验/待分配/待上架（即尚未产生库存的三个状态）可作废，需填原因。需求方拍板「作废按你说的加」 | 需求方 |
| 8 | **库位占用口径**：上架（确认入库）时才把 wms_location.status 翻「占用」；分配只写明细不翻状态；换库位发生在上架前（旧位从未占用，无需释放）；库位删除守卫追加「有库存记录不可删」blocker；既有的人工「释放」接口加校验（有库存不许释放）。本期无出库，占用回空闲只能人工 | Claude 定 |
| 9 | **库位不足判定**：匹配类型的空闲库位数 < 未分配明细行数 → 告警 + 未分配行列表 | Claude 定 |
| 10 | **分类类型取值**：取物料挂靠分类的 category_type，不做向上继承；为空按「其他」→不自动分配提示人工 | Claude 定 |
| 11 | **短装**：允许明细实收=0 → 该行跳过不入库存，前端二次确认；putaway 校验从「>0」放宽为「≥0 且整单至少一行 >0」 | Claude 定 |
| 12 | **非半成品/辅料类型**：收料时行级拒绝，提示「该物料类型不支持采购入库」（蓝图即原料/外购料） | Claude 定 |
| 13 | **同一采购订单分批到货**：允许多张收料单不拦截；防扫码枪误触由前端提交后清空 + 按钮 loading | Claude 定 |

## 5. 状态机（wms_inbound_order.inbound_status）

```
待检验 ──包装损坏(收料时勾选)──► 已拒收（终态，提示联系采购退货）
  │ 合格
  ▼
待分配 ──自动/手动分配成功──► 待上架
  │                           │
  │ 库位不足──► 告警+可人工分配 │ 确认上架（实收≥0，=0 行跳过；校验实收数量）
  ▼                           ▼
（待分配可继续分配）        已入库（未审核）──自动审核──► 已审核
                              │ 触发 ERP 同步                │ 上架时库位翻「占用」
检验不合格 ◄──检验环节          ▼                              ▼
（终态，提示转 RK-005）    同步成功 / 同步失败（重试 Job 兜底，超限告警）

待检验 / 待分配 / 待上架 ──作废(填原因)──► 作废（终态，未产生库存可安全作废）
```

**库位占用口径**（对应口径 8）：`wms_location.status` 仅在 putaway 落库存时翻「占用」；自动/手动分配不翻状态；库位删除守卫追加「有库存记录不可删」；人工释放接口加「有库存不许释放」校验。

- 状态常量全部进 `WmsConstants`，中文值与现有风格一致（如「待检验」「已拒收」）
- 同步字段：`sync_status`（待同步/同步中/同步成功/同步失败，复用现有常量）、`retry_count`、`last_sync_error`、`sync_time`

## 6. 数据模型（4 张新表，DDL 细节 builder 按现有实体风格定）

1. **wms_inbound_order**：id、order_no（入库/收料单号，唯一，生成规则仿 WmsLocationCodeGenerator 风格）、inbound_type（默认「采购入库」）、purchase_order_no、supplier_id/name/code（冗余，从采购订单带出）、inbound_status、reject_reason、sync_status、retry_count、last_sync_error、sync_time、audit_by、audit_time + JeecgEntity 公共字段 + deleted_at
2. **wms_inbound_detail**：id、inbound_id、material_id + 冗余 material_code/name、quantity（应收）、actual_quantity（实收，上架确认时录入）、location_id（分配后写）、batch_no + deleted_at
3. **wms_inspection_task**：id、task_no、inbound_id（唯一，整单级）、inspect_status（待检验/合格/不合格）、result_remark（不合格原因）、inspector、inspect_time + deleted_at
4. **wms_inventory**：id、material_id、location_id、batch_no、quantity；唯一键 (material_id, location_id, batch_no)，上架时 upsert 聚合；inventory 不做逻辑删除（行随聚合存在）

索引：detail/inventory 按 inbound_id / material_id / location_id 建 idx_；order_no / task_no 唯一。

## 7. 后端接口（`WmsInboundController` + `WmsInspectionController`，全部 Result<T>）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/wms/inbound/receive` | 收料：body `{purchaseOrderNo, packageDamaged, details:[{materialId, quantity, batchNo}]}`。校验：采购订单存在且 type=采购订单（否则 Result.error「订单不存在」）、物料存在、数量>0、批次非空、物料分类类型 ∈ {半成品, 辅料}（否则行级报错「该物料类型不支持采购入库」）。packageDamaged=true → 单状态=已拒收+reject_reason，提示联系采购退货，不建检验任务。正常 → 状态=待检验 + 生成检验任务。同一订单允许多张收料单（分批到货） |
| GET | `/wms/inbound/list` | 分页，支持状态/单号/供应商筛选（QueryGenerator） |
| GET | `/wms/inbound/{id}` | 单据详情含明细、检验任务、同步信息 |
| POST | `/wms/inbound/{id}/auto-allocate` | 自动分配：按明细物料的 category_type→warehouse_type（半成品→立库、辅料→线边柜；成品/其他→不自动分配，提示人工）找空闲库位顺序占用写 detail.location_id。库位不足 → 返回体带 warnings + 未分配明细列表（前端弹告警），已分配部分生效 |
| GET | `/wms/inbound/free-locations` | 参数 materialId / warehouseType，弹窗数据源（空闲库位列表） |
| POST | `/wms/inbound/{id}/putaway` | body `{details:[{detailId, actualQuantity, locationId}]}`。校验：状态=待分配/待上架、库位空闲且类型匹配（自动分配结果允许换）、实收数量≥0 且整单至少一行>0；实收与应收不符允许但前端必须二次确认（后端照实录入）；实收=0 的行跳过（不入库存、不占用库位）。动作（同事务）：写 actual_quantity/location → 库存 upsert → 所涉库位翻「占用」→ 状态=已入库（未审核）→ 自动审核（audit_by=system）→ 触发 ERP 同步（同步结果落库，失败不回滚审核） |
| POST | `/wms/inbound/{id}/void` | 作废：body `{reason}`。仅 待检验/待分配/待上架 可作废（未产生库存，安全）；终态其余不可。作废后单据只读保留 |
| POST | `/wms/inbound/{id}/sync` | 手动重试 ERP 同步 |
| GET | `/wms/inspection/list` | 检验任务列表（含单据信息联查） |
| POST | `/wms/inspection/{id}/complete` | body `{result: 合格\|不合格, remark}`。合格→单状态=待分配；不合格→单状态=检验不合格 + 提示转 RK-005 |

权限码（迁移脚本种子）：`wms:inbound:receive` / `wms:inbound:allocate` / `wms:inbound:putaway` / `wms:inbound:void` / `wms:inbound:sync` / `wms:inspection:do`。

ERP 网关：`IWmsInboundErpSyncGateway.syncInbound(order, details) → WmsSyncResult`；实现 `HttpWmsInboundErpSyncGateway`（URL 读配置 `jeecg.wms.erp.inboundUrl`，**未配置 → 模拟成功并在 result 标注 mock**；配置了不可达 URL → 失败走重试）。补偿：`WmsInboundSyncRetryJob`（照抄 WmsOrderSyncRetryJob：DisallowConcurrentExecution、包级 scanAndResyncForTest 便于单测、候选/认领/落库走 service）+ JobRegistrar + sys_quartz_job 种子行。重试上限/间隔常量进 WmsConstants，超限置「同步失败」终态并告警日志。

## 8. 前端（jeecgboot-vue3）

- `src/views/wms/inbound/index.vue` —— 采购入库页，上下两块：
  - 收料台：采购订单号输入框（回车即扫码枪输入，聚焦常驻）+「外包装损坏」按钮 + 明细行编辑表格（物料选择/扫码、数量、批次）→ 提交收料。数量不符、订单不存在等后端文案原样弹出
  - 单据列表：状态 tag、详情抽屉（明细、检验结果、同步状态、重试按钮）；状态=待分配/待上架时详情内「库位分配」按钮；状态 ∈ {待检验, 待分配, 待上架} 时「作废」按钮（填原因确认框）
- `src/views/wms/inbound/LocationAllocateModal.vue` —— 分配弹窗：「自动分配」按钮（回显结果+不足告警）+ 每行手动选空闲库位（下拉，按物料类型过滤）+ 实收数量列 → 确认上架
- `src/views/wms/inbound/InspectionList.vue` —— 检验任务列表：待检验行操作「合格 / 不合格（必填原因）」
- `src/views/wms/inbound/inbound.api.ts` —— 接口封装
- 菜单/权限 SQL 见迁移脚本；页面遵循现有 wms 页面风格（a-card + BasicTable 或纯 antd 表格，参照 wms/order）

## 9. 迁移脚本（V3.9.5_11__wms_inbound.sql，幂等）

1. 4 张建表 DDL（IF NOT EXISTS 风格与既有脚本一致）
2. `UPDATE sys_permission SET name='基础数据' WHERE id='1910000000000000001' AND name='基础管理';`
3. 新目录「入库管理」（menu_type=0? 参照既有目录行写法，注意 redirect 指向第一个子菜单防 404——V3.9.5_7 的教训）+ 2 个菜单 + 按钮权限 + admin 授权（INSERT IGNORE，沿用 ID 段）
4. sys_quartz_job 种子行（WmsInboundSyncRetryJob，cron 错开订单补偿任务）
5. **种子数据（2026-10-19 已查运行库确认）**：立库（半成品立库，144 库位全部空闲）已存在；**「线边柜」库区不存在，必须种子**——线边柜库区 + 若干空闲库位（否则辅料腿的分配走不通，运行库 6 个原材料物料 category_type=辅料）。物料分类实际取值即「半成品/辅料」两种（未加工电路板×4=半成品、原材料×6=辅料），自动分配规则按此映射：半成品→立库、辅料→线边柜

## 10. 验证方案

1. 后端单测（Mockito，照 WmsOrderServiceImplTest 风格）：状态机各转移、receive 校验分支（订单不存在/物料不存在/包装损坏）、auto-allocate 规则与不足告警、putaway 事务性（库存 upsert + 状态 + 审核）、ERP 网关 mock 成功/失败、RetryJob 认领与超限
2. Flyway：重启后 grep「flyway执行sql脚本失败」= 0；flyway_schema_history 对应行 success=1
3. API 端到端脚本（HTTP 直调，走 3100 代理）：主流程 8 步 + 4 个异常分支全部断言
4. 浏览器实测（chromium + playwright-core，登录走 token 注入+表单兜底）：三页面渲染、收料→检验→分配→上架→审核→同步状态截图人工复核
5. 收尾一次性：`install jeecg-boot-module-wms` + 重启后端 JVM
