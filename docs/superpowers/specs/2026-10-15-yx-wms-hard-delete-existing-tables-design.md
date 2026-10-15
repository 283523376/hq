# 既有 4 张业务表「逻辑删除 × 唯一索引」缺陷修复 — 立项说明

- 日期：2026-10-15
- 类型：既有缺陷修复（非新功能）
- 触发：BD-003 端到端验收修复了**同样缺陷**在 3 张新表上的实例后，回查发现既有 4 张表是同一个坑
- 状态：**已实施**（方案经需求方改定，见下方「方案变更」）

---

## 0. 方案变更（本立项最初写的是「物理删除」，已按需求方要求改为「删除时间戳」）

本文件最初提出的修法是**物理删除**（去掉 `@TableLogic`）。需求方随后指定了统一方案：

| 方案 | 做法 | 效果 |
| --- | --- | --- |
| **删除时间戳** | `deleted_at` 正常为 0，删除时写当前时间戳 | 每次删除时间戳不同，天然不冲突 |

**该方案优于物理删除**，采纳之：

- 物理删除**丢失**了逻辑删除的可恢复/可审计特性；
- 我最初把「索引带上删标记」列为不可行，理由是「固定值 0/1 会让同一编码只能删一次」。
  **时间戳正好补掉了这个缺点** —— 每次删除的值都不同，于是「删除 → 重建 → 再删除」可无限次进行。
  也就是说，我列的那个反对理由只对「固定值」成立，对「时间戳」不成立。

**实施范围随之扩大到全部 7 张表**（4 张既有 + BD-003 的 3 张）：这三张表原先按物理删除解决，
若不一并改造，同一缺陷会在同一模块里留下两套删除语义。故：

| 脚本 | 范围 |
| --- | --- |
| `V3.9.5_8__wms_deleted_at_scheme_existing.sql` | 4 张既有表 |
| `V3.9.5_9__wms_deleted_at_scheme_bd003.sql` | BD-003 的 3 张表（由物理删除并入） |

**与 §1 描述的问题、§3 的验收标准的关系**：问题描述与验收标准不变；只有 §2「修法」被替换。为保留决策痕迹，下文 §2 直接改写为最终方案，并保留对物理删除方案的对比与取舍分析。

---

## 1. 问题

BD-003 期间发现并修复了一类缺陷：**唯一索引只建在业务编码列上、不含 `del_flag`，而实体带 `@TableLogic`**。
两者语义不可兼得，表现为三条静默故障。修复后回查既有表，**同一个坑在这 4 张表上原样存在**：

| 表 | 唯一索引（真库已核） | 实体 |
| --- | --- | --- |
| `wms_material` | `uk_wms_material_code (material_code)` | `WmsMaterial` |
| `wms_material_category` | `uk_wms_mat_cat_code (category_code)` | `WmsMaterialCategory` |
| `wms_supplier` | `uk_wms_supplier_code (supplier_code)` | `WmsSupplier` |
| `wms_order` | `uk_wms_order_no (order_no)` | `WmsOrder` |

（四者均带 `@TableLogic`，`@TableLogic` 与索引定义均已用真库 + 源码核实，非推测。）

### 1.1 三条静默故障

以「删除供应商 `SUP001` 后想重新建一个 `SUP001`」为例：

1. **查重与保存的结论互相矛盾**：`checkCodeExists` 经 MyBatis-Plus 自动加 `del_flag=0`，**看不见**被逻辑删除的那行 → 回答「编码可用」；随后 `INSERT` 撞唯一索引 →抛 `Duplicate entry` 的**原始 SQL 异常**，而不是「编码已存在，请重新输入」。
2. **被删除的编码永久不可复用**，且没有任何显式原因提示。
3. **对 `wms_material_category` 额外多一层不一致**：删除守卫是「该分类下存在物料或子分类则拒绝」，而这两个计数都用 `this.count(...)`（走 MyBatis-Plus，会被自动加 `del_flag=0`）。因此守卫**看不见已逻辑删除的行** —— 它的判断依据与数据库里的实际占用不一致。
   > **准确表述**：这**不是**「绕过守卫导致误删」级别的问题 —— 被逻辑删除的子分类本就已不可见，删掉它已删的父分类不算误删。它的实际危害仍落在第 1、2 条（已删行的编码继续占用、查重与保存结论矛盾）。之所以单列，是因为它说明**同一个根因还污染了业务判断的依据**，不止污染报错文案。

### 1.2 影响面（按表）

| 表 | 用户可感知的后果 | 严重度 |
| --- | --- | --- |
| `wms_material_category` | 除第 1、2 条外，守卫计数忽略已删行 → 判断依据与库内实际不一致（见 1.1-3） | 中高 |
| `wms_material` / `wms_supplier` | 编码永久不可复用 + 报错信息是 SQL 原文而非业务提示 | 中 |
| `wms_order` | 同上；订单号是业务主键，重号再建是常见操作 | 中 |

> 说明：这些是**既有缺陷**，不是 BD-003 引入的。BD-003 只改了它自己新增的 3 张表。

## 2. 修法（最终采用：删除时间戳）

1. **加列** `deleted_at BIGINT NOT NULL DEFAULT 0`（epoch 毫秒），**0 = 未删除**。
2. **唯一索引把 `deleted_at` 一起纳入**：
   - `uk_wms_material_code (material_code, deleted_at)`
   - `uk_wms_mat_cat_code (category_code, deleted_at)`
   - `uk_wms_supplier_code (supplier_code, deleted_at)`
   - `uk_wms_order_no (order_no, deleted_at)`
3. **删除 = 写当前时间戳**。由 MyBatis-Plus 的
   `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")` 承担 ——
   `removeById`/`removeByIds` 生成的 SQL 会写成 `SET deleted_at = UNIX_TIMESTAMP(NOW(3)) * 1000`。
   **已实测验证 MP 会把 `delval` 按原文拼进 SQL**（真库上删一次，`deleted_at` 得到真实毫秒时间戳，
   而非被当成字符串或常量）。
4. **查询过滤**由 `@TableLogic` 自动追加 `deleted_at = 0`，**无需逐个查询改条件** ——
   这是保留 `@TableLogic`（而非手写过滤）的关键收益。
5. **回填**：已存在的 `del_flag = 1` 行**保留**，回填为迁移时刻的时间戳（该方案下它们本就该留存）。
6. **去掉旧列 `del_flag`**，避免两套删除标记并存。
7. **回归防护**：`WmsEntityMappingTest.deleteScheme_mustBeTimestampBased` 断言 7 张表都满足
   四点（字段名 `deletedAt` / 有 `@TableLogic` / `value="0"` / **`delval` 是时间戳表达式**）。
   最后一条最关键 —— 退化成常量就重陷「只能删一次」。

### 2.1 为什么「时间戳」优于「物理删除」，也优于「索引带固定删标记」

| 方案 | 结论 |
| --- | --- |
| 索引改为 `(code, del_flag)`，删标记固定 0/1 | ❌ 同一编码**只能删一次**（第二次删除的行与第一次 `del_flag` 相同，仍撞索引） |
| **物理删除**（本立项最初的选择） | ⚠️ 能用，但**丢失**逻辑删除的可恢复/可审计特性；且它逼我把「删除」当成不可逆操作 |
| **删除时间戳（最终采用）** | ✅ 既**释放编码**（时间戳各异，删除可无限次重来），又**保留逻辑删除**的可恢复/可审计；且不必改任何查询条件 |
| `deleted_at` 用 DATETIME + `NOW()` | ❌ 秒级精度下有同秒冲突风险；BIGINT 毫秒更直接，且与 Java 端 `currentTimeMillis` 口径一致 |

> 我在最初版本里把「索引带删标记」判为不可行，理由是「只能删一次」。**那个理由只对固定值成立** ——
> 时间戳把它化解了。这是本立项中被需求方纠正的一处判断。

### 2.2 明确不改的东西

| 不改 | 原因 |
| --- | --- |
| `WmsOrderMapper.xml` 里两处**显式**的删除条件 | 属 BD-002 的**认领/扫描 SQL**（最敏感的部分）。但它们**必须**从 `del_flag = 0` 改为 `deleted_at = 0`，否则会把已删订单也捞出来 —— 已改，并由订单相关测试 + 真库端到端覆盖 |
| `wms_material` / `wms_supplier` 的删除守卫 | 本次不新增守卫 —— 那是**功能范围扩张**，不是缺陷修复。若需要应另行立项 |
| `wms_order` 与供应商的关联方式 | `wms_order.supplier_id` 无外键；供应商被删后订单查不到供应商名 —— 两种删除语义下都是这个行为，本次不产生变化 |

### 2.3 BD-003 三张表一并改造（Phase B）

BD-003 的三张表原按物理删除解决，与本次一并并入删除时间戳方案
（`V3.9.5_9`），否则同一缺陷在同一模块里会留下两套删除语义。

`wms_location` 的收益尤其明显：`uk_wms_location_grid` 也纳入了 `deleted_at` 之后，
「删掉几个库位 → 重跑批量生成」会**新建**行，而不是被 `INSERT IGNORE` 静默跳过（计入 skipped 却不恢复）—— 删除语义与批量生成的幂等续做语义不再互相干扰。

## 3. 验收标准

| # | 标准 | 验证方式 |
| --- | --- | --- |
| ① | 4 张表同一编码**删除后可复用** | 真库端到端：建 → 删 → 用同编码再建，必须成功 |
| ② | 删后重号时，**查重接口与保存结论一致** | 查重返回「可用」，保存成功（而不是一个说可用、一个抛 SQL 异常） |
| ③ | `wms_material_category` 的守卫计数与库内实际一致 | 真库端到端：子分类被删除后，父分类的「有子分类」守卫判断与库内实际行数一致（过滤条件与库内占用口径统一，均由 `@TableLogic` 追加 `deleted_at = 0`） |
| ④ | 既有 4 个功能**无回归** | 全模块测试（315 个）保持全绿 |
| ⑤ | 无人再把 `@TableLogic` 加回来 | `WmsEntityMappingTest` 断言覆盖 7 张表 |
| ⑥ | 迁移**保留**已删数据、且执行后可核对 | 脚本用 `UPDATE ... SET deleted_at = ... WHERE del_flag = 1`（**不是 DELETE**）；执行后核对 `flyway_schema_history.success = 1` 与结构；真库已核对：2 行被删数据被保留并回填时间戳 |
| ⑦ | **同一编码可被删除无限次** | 真库端到端对 4 个功能**各做两轮**「删 → 同编码重建」，两轮均成功（固定值删标记只能过一轮） |
| ⑧ | `wms_location` 的删除与批量生成幂等续做不互相干扰 | BD-003 端到端回归：「重跑生成把它真的建回来（generated=1）」通过 |

**明确非目标**：为 material / supplier 新增删除守卫；引入「回收站/恢复」能力；改动订单与供应商的关联模型。

## 4. 风险与遗留

1. **迁移不删数据，只加列 + 换索引 + 去旧列**：`V3.9.5_8` 把已存在的 `del_flag = 1` 行**保留**并回填时间戳。
   这比最初物理删除方案安全（本环境实测：2 行被删数据被保留，`deleted_at` 分别为 …889 / …890）。
2. **DDL 非事务性**：MySQL 的 `ALTER` 逐条自动提交，多条 DDL 的脚本**无法整体回滚**。
   执行后必须核对 `flyway_schema_history.success = 1` 与结构（本次均已核对）。
   本环境已踩过一次相关坑：删掉源码里的迁移脚本后，`target/classes` 里的旧副本仍会让 Flyway 报
   `Found more than one migration with version 3.9.5.8` —— 改脚本后需一并刷新 `target`。
3. **`deleted_at` 依赖 MySQL 的 `UNIX_TIMESTAMP(NOW(3))`**：`delval` 是按原文拼进 SQL 的，
   故该表达式是 **MySQL 方言**。本项目 wms 模块已使用 `INSERT IGNORE`、`limit N` 等 MySQL 特性，
   口径一致；但若将来要支持其它数据库，这里是需要抽象的点之一。
4. **`wms_material` / `wms_supplier` 仍无删除守卫**：本次只修删除语义，不扩功能范围。
   这是应当在后续立项中补齐的防护（与本次缺陷无关，但同属这两张表的健壮性）。
5. **`deleted_at` 在同一毫秒内的同编码重复删除会撞索引**：需要「**同一编码的两行在不同时刻被删**
   且时间戳落在**同一毫秒**」。同一行重复删只会覆盖自身；不同编码不冲突。实际不可达，
   但这不是数学上的证明，故记在此。
6. **`WmsOrderServiceImpl` 里一条注释曾提到 `selectBatchIds` 自动带 `del_flag=0`**，已随本次订正
   （改为说明"供应商已逻辑删除时查不到"，并指向 `deletedAt`）。

## 5. 交付物（已实施）

**Phase A — 4 张既有表**（提交 `e84a0a86`）
- 新增 `V3.9.5_8__wms_deleted_at_scheme_existing.sql`
- 修改实体 4 个：`WmsMaterial` / `WmsMaterialCategory` / `WmsSupplier` / `WmsOrder`
  （`delFlag` → `deletedAt` + `@TableLogic(value="0", delval="UNIX_TIMESTAMP(NOW(3)) * 1000")`）
- 修改 `WmsOrderMapper.xml`（两处显式 `del_flag = 0` → `deleted_at = 0`）
- 修改 `WmsMaterialController` / `WmsOrderServiceImpl` / `WmsSupplierServiceImpl`（`setDelFlag(0)` → `setDeletedAt(0L)`）
- 修改 `WmsEntityMappingTest`（`deleteScheme_mustBeTimestampBased`，先按 4 张表）
- 修改 3 个既有测试中的 `delFlag` 断言（它们是"新增必须复位删除标记"的守护，语义保留）

**Phase B — BD-003 三张表并入同一方案**（提交 `ef8b9e27`）
- 新增 `V3.9.5_9__wms_deleted_at_scheme_bd003.sql`
- 修改实体 3 个：`WmsWarehouse` / `WmsLocation` / `WmsDevice`
- 修改 `WmsLocationMapper.xml`（自定义 `INSERT IGNORE` 的列名与取值）
- 修改 `WmsLocationServiceImpl`（`setDelFlag(0)` → `setDeletedAt(0L)`）
- 修改 `WmsEntityMappingTest`（断言扩到 7 张表）

**验证**（详见 §3 与提交信息）
- 全模块测试 **315 / 315**
- 真库端到端：4 张既有表 **37 / 37**（各两轮删→重建 + 时间戳实测）
- BD-003 端到端回归 **30 / 30**

**未纳入**：`wms_material` / `wms_supplier` 的删除守卫（见 §2.2、§4-4，属功能范围扩张）。

## 6. 与 BD-003 的关系

BD-003 修复了**新表**上的这一缺陷并留下回归防护；本立项把同一修正推广到**既有表**。
两者共用同一论证与同一风险口径，故本文档不复述全部推导，只列差异与本次特有影响（§1.2 的守卫绕过路径是本立项**独有**的发现）。