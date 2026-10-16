# 永鑫WMS 引用完整性与删除守卫 — 立项设计（阶段一）

- 日期：2026-10-16
- 类型：既有缺陷修复 + 防护体系补全（非新功能）
- 触发：TC-BD002-006 实测——删除有关联订单的供应商未被拦截，订单中供应商列显示为空；
  与《已知未修复问题清单》（`docs/superpowers/plans/2026-10-16-wms-已知未修复问题.md`）A 节对齐后立项
- 状态：**待实施**（设计已经需求方确认，2026-10-16）
- 范围决策（需求方已选）：**一个立项、两阶段**——本期实施 A1 全套 + 顺带 M2/M4；
  B 节 6 条（外部依赖类）只写处置计划，待依赖到位逐个实施

---

## 0. 已确认的关键决策（留痕，勿再重开）

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 引用完整性落地手段 | **应用层守卫 + 唯一约束/索引，不建物理外键** | ① 项目遵循《阿里巴巴Java开发手册》（「不得使用外键与级联，一切外键概念必须在应用层解决」）；② 删除语义已统一为 `deleted_at` 时间戳（逻辑删除，行不物理移除），物理 FK 只在物理 DELETE 时触发，**挡不住「引用一个已逻辑删除的父行」**，ON DELETE CASCADE 更与留痕诉求相反 |
| 前端交互形态 | **前端预检 + 后端兜底** | 预检让用户删除前就看到禁止原因（而不是删完才报错）；后端守卫仍兜底——**收窄（非消除）**预检与删除之间的并发窗口：守卫是事务内非锁定快照读，窗口从「预检→确认」的秒级降到单次请求的毫秒级；彻底闭合需物理外键或应用层串行化，已明确不做（见上一条） |
| 设备删除语义 | **新增守卫：绑定中禁止删除，提示先解绑** | 设备虽无子表引用，但绑定是有效业务状态；静默解绑易困惑。与库区「有设备不许删」口径对称 |
| B 节 6 条 | 只立项不实施 | 多数被外部依赖（WCS/RMS、入库模块）卡死，强行做会产出空壳或返工 |

## 1. 问题

### 1.1 实测缺陷（TC-BD002-006）

删除有关联采购订单的供应商 `SUP-2026-001`：系统未拦截，删除成功，订单列表中供应商列显示为空。

**机制已查明（源码核实，非推测）**：订单行的 `supplier_id` **并没有被清掉**——
供应商走 `deleted_at` 逻辑删除后，前端回填供应商名称时查不到该行（`deleted_at > 0`），于是显示为空。
订单持有的是**悬挂引用**。这正是缺少删除守卫的直接后果。

### 1.2 守卫现状矩阵（2026-10-16 全量核实）

| 资源 | 单条删除 | 批量删除 | 作为父表被引用 |
| --- | --- | --- | --- |
| 物料分类 | ✅ 有物料/子分类拒绝 | 无批量端点（树形单删） | 被 `物料.category_id` + 自引用 |
| 物料 | ❌ 裸 `removeById` | ❌ 裸 `removeByIds` | 无（尚无库存/明细表） |
| 供应商 | ❌ 裸 `removeById` | ❌ 裸 `removeByIds` | 被 `订单.supplier_id` 引用 |
| 订单 | ✅ 仅待审核/已驳回可删 | ✅ 循环复用单条 | 无 |
| 库区 | ✅ 有库位/绑定设备拒绝 | ⚠️ 复用单条守卫，但**循环在 controller、无事务** | 被 `库位.warehouse_id`、`设备.warehouse_id` 引用 |
| 库位 | ✅ 非空闲拒绝 | ⚠️ 同上 | 无（未来库存会引用） |
| 设备 | ❌ 裸 `removeById` | ⚠️ 同上 | 无 |

### 1.3 缺口归纳

1. **供应商/物料/设备单条删除无守卫**（A1 本体 + 设备语义决策）；
2. **供应商/物料批量删除完全绕过一切检查**（裸 `removeByIds`）；
3. **批量删除无事务**：库区/库位/设备的 `deleteBatch` 循环在 controller 里逐条调 service，
   第 N 条撞守卫抛异常时前 N-1 条已提交——**部分成功**，前端却只看到一条报错（错误的状态结论）；
4. **前端体验**：所有删除入口都是 `popConfirm` 气泡确认 → 直接调接口，用户删完才知道行不行。

## 2. 引用完整性模型（全库结论）

### 2.1 引用关系与删除策略（全部 5 条，无物理外键）

| 子表.列 → 父表 | 子行可空 | 父表删除策略 | 现状 |
| --- | --- | --- | --- |
| `wms_material.category_id` → 分类 | 可空 | 守卫：有（未删）物料拒绝 | 已有，保持 |
| `wms_material_category.parent_id` → 自引用 | 默认 '0' | 守卫：有子分类拒绝 | 已有，保持 |
| `wms_order.supplier_id` → 供应商 | 可空 | **守卫：有（未删）订单拒绝（新增）** | ❌ |
| `wms_location.warehouse_id` → 库区 | 非空 | 守卫：有库位拒绝 | 已有，保持 |
| `wms_device.warehouse_id` → 库区 | 可空（未绑定） | 守卫：有设备拒绝 | 已有，保持 |

方向说明：**删除「被引用方」（父表）才需要守卫**；删除引用方（如删订单、删物料）不产生悬挂，
由其自身业务守卫（订单状态）或无守卫（物料）覆盖。

### 2.2 数据库层结论：**零 DDL**

- 唯一约束：`deleted_at` 专项（V3.9.5_8/_9）已把 7 张表统一为 `(编码, deleted_at)` 组合唯一索引；
- 引用列索引：5 条引用关系的子表外键列索引**全部已存在**
  （`idx_wms_material_category` / `idx_wms_mat_cat_parent` / `idx_wms_order_supplier` /
  `idx_wms_location_warehouse` / `idx_wms_device_warehouse`），守卫的 count 查询均有索引支撑；
- 守卫计数经 MyBatis-Plus 自动携带 `deleted_at = 0`，只统计有效引用——语义正确（已删的子行不算占用）。

**本项目不需要新增任何 Flyway 结构脚本。** 缺口全部在应用层。

## 3. 后端设计

### 3.1 统一守卫与预检模式

新增公共出参 `org.jeecg.modules.wms.vo.WmsDeleteCheckVO`：

```java
public class WmsDeleteCheckVO {
    private boolean deletable;
    private List<Blocker> blockers;      // deletable=false 时非空
    public static class Blocker {
        private String message;          // 如「存在关联订单」
        private long count;              // 如 3
        private List<String> samples;    // 前 5 个业务编号，如 ["PO001","PO002"]
    }
}
```

各 Service 的分工（两方法契约，所有资源一致）：

- `checkDelete(String id) → WmsDeleteCheckVO`：**只读**，收集全部 blocker（一次报全）；
- `removeXxx(String id)`：先内部调用守卫检查，`deletable=false` 即抛
  `RuntimeException(<拒绝文案>)`，再 `removeById(id)`。

**拒绝文案精确对齐测试用例预期**（TC-BD002-006 预期结果原文）：

| 资源 | 拒绝文案（单条删除接口返回） |
| --- | --- |
| 供应商 | `该供应商存在关联订单，不允许删除` |
| 设备 | `设备已绑定库区，不允许删除，请先解绑`（解绑入口已存在：`POST /wms/device/unbind`，已核实） |
| 物料分类 | `该分类下存在物料，不允许删除` / `该分类下存在子分类，不允许删除`（保持现状） |
| 库区 | `该库区下存在库位，不允许删除` / `该库区已绑定设备，不允许删除`（保持现状） |
| 库位 | `库位已占用或有故障，不允许删除`（保持现状） |
| 订单 | `仅待审核或已驳回状态的订单可删除`（保持现状） |

已有守卫的分类/库区/库位：**行为不变，仅把判定逻辑提取为 `checkDelete` 供 `removeXxx` 与预检接口复用**。
现有单测（BD001-T07 等）必须原样通过。

物料：当前无子表引用，`checkDelete` 恒返回可删——**预检框架就位**，
未来库存表/订单明细表接入时在该方法里追加一条 blocker 即可（设计里预留的位置，不是空转的抽象）。

### 3.2 预检接口

```
GET /wms/{resource}/checkDelete?id={id}
GET /wms/{resource}/checkDeleteBatch?ids={a,b,c}
```

- `{resource}` ∈ `supplier | material | materialCategory | warehouse | location | device`；
  其中 `materialCategory` 仅提供单条 `checkDelete`（分类是树形单节点删除，从无 `deleteBatch` 端点，
  故也不设批量预检）；`warehouse` 的批量预检暂无前端消费者（库区在树上单删），接口先备以防 API 直调；
- 单条返回 `Result<WmsDeleteCheckVO>`；
- 批量返回 `Result<WmsBatchDeleteCheckVO>`：`{ deletable, blockedItems: [{id, code, reason}] }`，
  供前端列出「以下 N 条无法删除」；
- 鉴权：走现有 JWT 登录态，**不加新按钮权限**（入口由既有 `:delete` 权限控制，
  预检只是删除动作的前置只读查询）；
- 订单页**本期不接入预检**：其守卫已存在且文案清晰，YAGNI；交互统一留待后续。

### 3.3 批量删除事务化（杜绝部分成功）

所有 6 个资源在 **service 层**提供统一签名：

```java
@Transactional(rollbackFor = Exception.class)
void removeBatch(List<String> ids);   // 循环复用单条 removeXxx（含守卫）
```

- 供应商/物料：现 controller 调裸 `removeByIds` → 改调 `removeBatch`；
- 库区/库位/设备：现 controller 里循环逐条调 → 循环**下沉**到 service `removeBatch`；
- 订单：已有循环复用，补 `@Transactional` 注解；
- 效果：中途遇守卫异常**整体回滚**，要么全删、要么全不删。
- 自调用说明：`removeBatch` 内 `this.removeXxx()` 不经过代理，但事务注解在 `removeBatch` 上、
  由 controller 经代理进入，无 Spring 自调用失效问题；`removeXxx` 本身不标 `@Transactional`。

错误路径保持现状模式：`RuntimeException` → controller catch → `Result.error(文案)`。不引入新异常体系。

## 4. 前端设计（统一预检交互）

### 4.1 交互流程

单条删除（替换现有 `popConfirm` 气泡）：

```
点「删除」 → GET checkDelete
  ├─ deletable=true  → createConfirm「确定删除吗?」 → 确认 → DELETE → 成功刷新
  └─ deletable=false → createErrorModal「无法删除」
                        正文 = blockers 列表（「存在关联订单（3 张）：PO001、PO002…」）
```

批量删除：

```
勾选 → 点「批量删除」 → GET checkDeleteBatch
  ├─ 全部可删 → createConfirm → DELETE batch
  └─ 有不可删 → createErrorModal 列出 blockedItems（编码 + 原因），不执行
```

后端兜底：预检通过后、删除执行前若有并发写入导致守卫拒绝，仍走现有
`defHttp` 错误 message 透传——前端无需额外处理。

### 4.2 实现载体与接入点

- 公共 composable：`src/views/wms/common/useWmsDeleteCheck.ts`——
  入参为该资源的 `checkDelete / checkDeleteBatch / delete / deleteBatch` 四个 api 函数，
  出参 `confirmDelete(record)` / `confirmBatchDelete()`，内部用 vben `useMessage` 的
  `createConfirm` / `createErrorModal`；
- 各模块 `xxx.api.ts` 增加 `checkDelete` / `checkDeleteBatch` 两个函数（URL 约定见 §3.2）；
- 接入页面（5 个）：`supplier/index.vue`、`material/index.vue`（物料 + 分类树两处删除入口）、
  `warehouse/index.vue`（库区 + 库位两处）、`device/index.vue`；
  订单页不接入（§3.2 取舍）。

## 5. 顺带项（同立项内实施）

| 项 | 修法 |
| --- | --- |
| **M4** 手工补录库位缺编码长度预校验 | `addLocation` 与 `generate` 提取共享的长度校验（同一口径），超长返回友好提示而非 SQL 异常 |
| **M2** 选中库区节点时冗余 `warehouseType` | 后端 `WmsLocationController.queryPageList`：`warehouseId` 非空时跳过 `warehouseType` 分支（后端一处，防御所有调用方） |

## 6. B 节 6 条处置计划（本期不实施，依赖到位后逐个立项/实施）

| 项 | 等什么依赖 | 依赖到位后的动作 |
| --- | --- | --- |
| 下发设备注册信息 | WCS/RMS 接入（蓝图 `YX-WMS-SB-002`） | `bind` 成功后追加下发调用 |
| `/wms/device/reportStatus` 鉴权 | 同上 | 设备级凭证（**禁止** `@IgnoreAuth`，Javadoc 已注明） |
| 库位状态「占用」的来源 | 入库模块实施 | 入库回写占用；`release` 保留人工兜底 |
| 线边柜格子级建模 | 线边柜业务流程立项 | 以「格子」为最小单元另立模型 |
| 前端 UI 回归测试 | 决定长期维护意愿 | `<workspace>/ui-check.js`（21 断言）移植为可移植版接入（可独立小立项） |
| 告警通道 | 钉钉/邮件通道与需求方确认 | 通知 SPI 扩展 |

M3（绑定弹窗硬编码 pageSize=200）/ M5（Quartz 注册器只查 JobDetail）/ M6（generate 并发死锁）
维持清单原口径：MVP 可接受 / 低概率边缘 / 设计已容忍。

## 7. 测试与验收

### 7.1 后端单测（`mvn -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false`）

- 供应商：守卫拒绝（有订单）/放行（无订单）/`checkDelete` VO 结构/`removeBatch` 中途异常**整体回滚**；
- 物料：`checkDelete` 恒可删、`removeBatch` 事务化；
- 设备：绑定中拒绝、解绑后可删、`removeBatch` 事务化；
- 分类/库区/库位：现有守卫用例**原样回归**（行为不变）+ `checkDelete` 结构；
- 订单：`removeBatch` 补事务注解后的行为不变；
- Controller：`checkDelete` / `checkDeleteBatch` 端点返回结构。

### 7.2 端到端（真实浏览器，重测缺陷）

1. **TC-BD002-006 重测**：删除有关联订单的供应商 → 提示「该供应商存在关联订单，不允许删除」
   （逐字命中），记录未删，订单列表供应商列正常显示；
2. 供应商批量删除含被引用者 → 预检弹窗列出，确认不可执行，**无任何行被删**；
3. 直接调 `DELETE /deleteBatch`（绕过预检）→ 后端整体回滚，无部分成功；
4. 分类/库区/库位删除 → 预检弹窗展示原有守卫原因（体验升级，行为不变）；
5. 绑定中的设备删除 → 预检与后端双重拒绝；
6. 物料删除 → 预检可删、删除成功（数据可恢复，`deleted_at` 留痕）；
7. M4：手工补录超长编码 → 友好提示；M2：选中库区节点查询无冗余 `IN` 子句。

### 7.3 构建门禁

- 后端 `bash /d/tools/m.sh -P dev clean package`；
- 前端 `pnpm build`（vue-tsc 类型门禁）。

**验收即缺陷闭环**：清单 A 节中 A1 / M2 / M4 三项关闭并回写《已知未修复问题清单》状态；
M3 / M5 / M6 与 B 节维持原状。

## 8. 工作量（单人粗估）

| 部分 | 估算 |
| --- | --- |
| 后端（守卫 + 预检接口 + 批量事务 + 单测） | 1 ~ 1.5 天 |
| 前端（composable + 5 页接入） | 1 天 |
| M2 / M4 | 0.5 天 |
| 端到端回归 + 文档回写 | 0.5 天 |
| **合计** | **≈ 3.5 人日** |
