# 永鑫WMS 权限/租户/角色现状分析与优化设计

- 日期：2026-11-10
- 状态：评审中（本文档只做分析与设计，不含实施；实施需另立 plan）
- 范围：`jeecg-module-system`（平台机制，不改代码）+ `jeecg-boot-module-wms`（权限点消费方）+ 数据治理（SQL 脚本）
- 结论先行：**保留 JeecgBoot 原生机制，落地「业务角色 + 按钮权限」这一缺失层，收敛租户脏数据；不引入自研权限框架，不改平台代码。**

---

## 1. 现状分析

### 1.1 平台机制（JeecgBoot 3.9.5，代码事实）

**认证与租户上下文**
- Shiro + JWT；`JwtFilter.preHandle`（`jeecg-boot-base-core/.../shiro/filters/JwtFilter.java:114`）对每个请求从 `X-Tenant-Id` 请求头取租户写入 `TenantContext`（ThreadLocal）。
- 前端 `src/utils/http/axios/index.ts:163-190`：每个请求都带 `X-Tenant-Id`（取当前登录租户，取不到发 `0`）。
- 用户与租户是多对多：`sys_user` **没有** tenant_id 列，租户归属存 `sys_user_tenant`，当前登录租户存 `sys_user.login_tenant_id`。

**功能权限（菜单/按钮）解析 —— 双通道并集**
`SysPermissionServiceImpl.getPermissionByUser`（:239-244）：存在租户上下文时走 `queryByUserWithTenantId`，其 SQL（`SysPermissionMapper.xml:229`）是：

```
可见权限 = 角色通道 ∪ 套餐通道
角色通道：sys_user_role → sys_role → sys_role_permission → sys_permission
          （注意：不校验 sys_role.tenant_id，跨租户角色照样生效）
套餐通道：sys_tenant_pack_perms ∩ sys_tenant_pack_user(status=1)
          ∩ sys_tenant(status=1, del_flag=0, id=当前租户)
```

即**本版本的租户权限机制 = 「角色」与「租户套餐直挂用户」两条独立通道取并集**：
- 支持租户内角色权限控制吗？**结构上支持**（`sys_role.tenant_id` 列存在），但实际 SQL 角色通道不过滤租户，租户隔离靠「租户管理员建角色时只能从套餐权限里选」这一层应用约束（`zuhuadmin` 角色即为此设计，26 个权限点）。
- 「权限挂套餐、套餐直挂用户」机制确认存在（`sys_tenant_pack_user`），是**角色之外的第二授权通道**——同一用户可同时经角色和套餐获得权限，排查权限问题时必须两处都查。

**行级数据隔离（SQL 改写）**
- `MybatisPlusSaasConfig.mybatisPlusInterceptor`（:94-127）：`TenantLineInnerInterceptor` 是**白名单机制**——只有 `TENANT_TABLE` 里列出的约 20 张平台表（`sys_depart`、`jimu_report`、`airag_*` 等）会被追加 `tenant_id = ?`。
- **`wms_*` 全部业务表不在名单内**：WMS 没有任何 SQL 级租户/数据隔离，一切数据全库可见（单工厂下这是正确的，也意味着隔离只能在应用层做）。

**数据规则（`sys_permission_data_rule`）**
- 库里 27 行全是上游演示配置；WMS 菜单/权限点 0 条数据规则，未使用。

### 1.2 数据现状（yongxin_wms 库，2026-11-10 实查）

| 对象 | 现状 | 问题 |
| --- | --- | --- |
| 租户 `sys_tenant` | 仅 1000（北京国炬）、1001（敲敲云）两个**上游演示租户** | 没有永鑫自己的租户；演示租户残留 |
| 套餐 `sys_tenant_pack` | 4 个全是演示套餐（默认 7 条 / 升级企业 12 条权限，全为平台演示菜单） | **0 条 WMS 权限在套餐里** |
| 套餐-用户 `sys_tenant_pack_user` | 4 行 | 1 行孤儿（pack_id `1633795213938606082` 不存在、tenant_id=1） |
| 用户 `sys_user` | 4 个全是上游演示用户：admin/jeecg/zhangsan/ceshi | **没有一个业务用户**；admin 的 `login_tenant_id=1000`（指向演示租户「国炬」） |
| 用户-租户 `sys_user_tenant` | admin→1000，ceshi/jeecg→1001 | 全是演示关联 |
| 角色 `sys_role` | 8 个全是**租户 0 的平台种子角色**（admin/vue3/aiadmin/test/chat2bi/dbadeveloper/lowdeveloper/zuhuadmin） | **0 个 WMS 业务角色**；wms 权限点只挂在 admin 一个角色上（105 个） |
| 权限点 `sys_permission` | 723 行（30 目录/285 菜单/408 按钮），其中 `wms:%` **108 个**，22 个权限组 | WMS 菜单与上游演示菜单（主页/AI应用/组件示例/导航示例…）混在同一导航 |
| 角色授权缺口 | admin 角色挂 105/108 个 wms 权限点 | **缺 `wms:device:add`、`wms:device:edit`**——设备管理的新增/编辑按钮 admin 也看不到（Flyway 插菜单时漏授） |
| `@RequiresPermissions` | wms 模块 168 处 | 后端接口级权限完备，瓶颈不在接口层 |

### 1.3 问题清单（按对永鑫WMS交付的影响排序）

| # | 问题 | 影响 | 级别 |
| --- | --- | --- | --- |
| P1 | **无业务角色体系**：能用 WMS 的只有 admin（平台超管）一个角色 | 交付后无法按岗位分权——仓管员、盘点员、查询用户要么给全权、要么不给权；客户上线第一天就会提的需求 | **高** |
| P2 | admin 角色缺 2 个设备权限点（device:add/edit） | 功能缺陷：设备管理新增/编辑入口不可见 | **高**（修复成本极低） |
| P3 | 租户状态脏：admin 登录租户指向演示租户 1000；演示租户/套餐/孤儿关联残留 | 「我的租户」入口可切换到演示租户，切过去后权限集变化难排查；审计困惑 | 中 |
| P4 | 演示菜单未治理：客户登录看到 20+ 个无关菜单（AI 应用平台、组件示例、低代码开发…） | 专业性与可用性差，客户验收观感直接受损 | 中 |
| P5 | 数据权限空白：无 SQL 级隔离、无数据规则 | 当前单工厂没问题；「按仓库/库区隔离操作范围」（如敏捷仓仓管不能动立体仓）没有落点，需要预留 | 低（预留） |
| P6 | 套餐通道的静默坑：若走「真实租户+套餐」路线，每次 Flyway 加菜单必须同步插 `sys_tenant_pack_perms`，漏插则菜单静默不可见 | 与 V3.9.5_52 菜单 id 冲突同类的「不报错、只出错」坑 | 中（规避见 §3.1） |

---

## 2. 设计目标与约束（紧扣永鑫WMS需求）

**业务事实**：单工厂（楠柏莞/永鑫）本地化部署；两仓（半成品立体仓 + 成品敏捷仓）；用户是仓库主管、仓管员、盘点员、查询/财务人员；17 条蓝图流程已交付 16 条，全部写接口已有按钮级权限码（168 处 `@RequiresPermissions` / 108 个权限点）——**权限粒度已经在按钮级，缺的是把它们按岗位组织起来的「角色层」，以及客户可自助维护的用户/角色入口**。

**目标**
1. 岗位分权开箱可用：交付 5 个业务角色（§3.2 矩阵），建好用户勾角色即可干活。
2. 客户视图干净：业务角色登录只看到 WMS 菜单，演示菜单全部不可见。
3. 修掉 P2 缺口与 P3 脏数据，且全部动作可回滚。
4. 数据权限留好扩展点（本期不实施），方案写清落点与触发条件。
5. **不引入新的权限模型**：全部用 JeecgBoot 原生「角色-权限」通道，避免双通道并集带来的排查成本；运营规则固化为 Flyway/脚本惯例。

**约束**
- 不改平台代码（`jeecg-module-system`、`jeecg-boot-base-core` 零修改）——平台升级可跟随。
- 全部治理动作走 Flyway（幂等、可回滚），与既有 `V3.9.5_*` 惯例一致。
- 权限码零变更：108 个 `wms:%` 权限点与 168 处注解原样复用，前端 BACK 权限模式零改动。

---

## 3. 设计方案

### 3.1 租户层：收敛到租户 0，不启用套餐通道（决策 + 依据）

**决策：业务全部落在租户 0（平台默认租户），套餐通道保持空置；演示租户标记停用。**

| | 方案 A（本设计）：收敛租户 0 | 方案 B：建永鑫真实租户 + 套餐 |
| --- | --- | --- |
| 新增菜单的配套动作 | 仅「授角色」一步（既有惯例） | 授角色 **+** 同步插 `sys_tenant_pack_perms`（漏插=静默不可见，P6） |
| 演示菜单隔离 | 靠角色不挂演示菜单（§3.3） | 天然隔离（不在套餐内） |
| 登录体验 | 不出现租户选择，直达 | 多一步租户确认（或域名绑定） |
| 与平台语义冲突 | 租户 0 是「平台租户」，WMS 业主数据混在平台侧——单工厂可接受 | 语义干净，多工厂可扩展 |
| 适用场景 | **单工厂（本项目）** | 集团多法人/多工厂、SaaS 化 |

选 A 的核心理由：**永鑫是单工厂**，多租户只有成本没有收益；而方案 B 的套餐同步是运维期最危险的静默坑（P6）。方案 B 的完整迁移路径保留在 §5，触发条件写明——客户提出多工厂/多法人隔离时再切换，届时只做数据搬迁不改代码。

**治理动作（V3.9.5_54，全部幂等）**
1. `UPDATE sys_user SET login_tenant_id=0 WHERE username='admin'`（修 P3：admin 不再指向演示租户）。
2. 清孤儿：删除 `sys_tenant_pack_user` 中 pack_id 不存在于 `sys_tenant_pack` 的行（当前 1 行）。
3. 演示租户 1000/1001 `status` 置停用（保留数据可回滚）；`sys_user_tenant` 中演示关联保留不动（租户停用后已不可达）。
4. 给 admin 补 `sys_user_tenant(admin, 0)` 关联行（若不存在），保证租户 0 上下文登录路径完整。

### 3.2 角色层：5 个业务角色 + 权限矩阵（核心交付）

角色全部建在租户 0（`sys_role.tenant_id=0`），固定 id 段 `191000000000009001~905`（避开已占用的 10xx 菜单段与上游雪花段）：

| 角色码 | 角色名 | 定位（对应工厂岗位） |
| --- | --- | --- |
| `wms_admin` | WMS系统管理员 | 系统配置、主数据全权、用户/角色维护（交付后由客户 IT 接手） |
| `wms_manager` | 仓库主管 | 全业务流程 + 复核/差异确认/调整类高权限动作 + 主数据维护 |
| `wms_operator` | 仓管员 | 日常执行：入库、出库拣放、移库、改制、不良品处置、生产过站、预警处理、打印 |
| `wms_stocktake` | 盘点员 | 盘点任务接收与实盘录入 + 库存查询 + 打印盘盈亏单 |
| `wms_viewer` | 查询角色 | 只读：库存台账/批次追溯/审计留痕/各类单据列表（给财务、跟单） |

**权限矩阵（22 个权限组 × 5 角色）**——✓=整组授予；括号=组内细拆仅授予子集：

| 权限组（wms:） | admin | manager | operator | stocktake | viewer |
| --- | :-: | :-: | :-: | :-: | :-: |
| audit（审计留痕 list/export） | ✓ | ✓ | | | ✓ |
| conversion（改制） | ✓ | ✓ | ✓ | | |
| customer（客户档案） | ✓ | ✓ | | | |
| defect（不良品 inbound/list/return/scrap） | ✓ | ✓ | ✓ | | |
| device（设备 add/edit/bind/delete） | ✓ | | | | |
| inbound:consigned（受托入库） | ✓ | ✓ | ✓ | | |
| inventory（batch/export/ledger） | ✓ | ✓ | ✓ | ✓ | ✓ |
| location（库位管理） | ✓ | ✓ | | | |
| material（物料） | ✓ | ✓ | | | |
| materialCategory（物料分类） | ✓ | ✓ | | | |
| materialGroupMap（分组映射） | ✓ | ✓ | | | |
| outbound（销售出库等 12 码） | ✓ | ✓(除 add/allocate/confirm/dispatch/scan/pick:cancel) | ✓(仅 add/allocate/confirm/dispatch/list/scan/pick:cancel/close) | | (仅 list) |
| print（打印工作台） | ✓ | ✓ | ✓ | ✓ | |
| production（生产入库/领料 6 码） | ✓ | ✓(除 scan/submit) | ✓(仅 scan/submit) | | |
| stocktake（盘点 7 码） | ✓ | ✓ | | ✓(仅 list/record) | (仅 list) |
| supplier（供应商） | ✓ | ✓ | | | |
| sync（上位系统对账 5 码） | ✓ | ✓ | | | |
| systemConfig（系统配置 save/rollback） | ✓ | | | | |
| transfer（移库 7 码） | ✓ | ✓(除 add/dispatch/execute) | ✓(仅 add/dispatch/execute/list) | | |
| warehouse（仓库档案） | ✓ | | | | |
| warning（预警 6 码） | ✓ | ✓ | ✓(仅 handle/list/scan) | | (仅 list) |
| work-order（工单同步） | ✓ | ✓ | | | |

细拆原则（写死在脚本注释里，防止后续调整时歧义）：
- **执行 vs 复核分离**：`review`/`confirm`（盘点差异确认、出库复核）/`adjust`/`archive`/`reverse`/`close`/`sync` 类「确认性、不可逆、对账」动作只给主管；`scan`/`record`/`submit`/`dispatch`(执行下发)/`execute` 类操作动作给仓管员。
- **主数据 vs 事务分离**：`warehouse`/`device`/`systemConfig` 只给管理员；`material`/`supplier`/`customer`/`location` 主数据维护给主管+管理员。
- **viewer 只挂 list/export/ledger/batch** 类只读码，一个写码都不给——保证财务/跟单账号天然安全。

**菜单可见性**：随权限点自动解决——前端 BACK 模式按权限树渲染菜单，角色只挂 WMS 菜单子树（`191%` id 段）+ 演示菜单一个不挂，P4 即消。admin 角色保持现状（开发运维用，演示菜单仍可见）。

### 3.3 防坑机制（把惯例固化成脚本）

1. **补授权脚本**（V3.9.5_54 内完成）：admin 角色补 `wms:device:add/edit` 两个缺口（P2）。
2. **授权惯例升级**：既有惯例是「Flyway 插菜单同时授 admin 角色」，本设计后升级为「同时授 admin + wms_admin + wms_manager」（新权限点至少保证主管可用），写入根 `CLAUDE.md` 后端约定段。
3. **三方对齐检查脚本** `scripts/wms-perms-check.py`（新增，与既有 `wms-status-options-check.py` 同风格）：
   - 代码 `@RequiresPermissions` 枚举 ↔ `sys_permission` 权限点 ↔ 各业务角色授权，三方比对；
   - 退出码 1 = 有缺口（防 P2 类静默缺口复发，也防未来套餐通道启用后的漏插）。

### 3.4 数据权限：本期不实施，落点与触发条件（预留设计）

**需求场景（客户提出时启动）**：按仓库/库区隔离操作范围——例如敏捷仓仓管员看不到/动不了立体仓的库存与任务。

**落点决策：WMS 自建授权表 + service 层过滤，不用平台的 `sys_permission_data_rule`。**
理由：数据规则依赖 `QueryGenerator` 生成的 Wrapper 查询才被拦截，而 WMS 为满足模糊查询大量手写 `like` SQL（7 个列表页既定模式），数据规则拦不住，会产生「规则配了但不生效」的静默坑——正是本设计要规避的类别。

设计占位（届时实施）：
- 表 `wms_user_warehouse(user_id, warehouse_id)` + 管理页面（角色维度可选挂到 `wms_manager` 等角色默认模板）；
- 查询过滤点集中在各 service 的 `QueryWrapper` 构造处统一追加 `warehouse_id IN (授权集)`；管理员/主管角色授予「全部仓库」不落行；
- 兼容现有 16 条流程的验证清单另立 plan。

**触发条件**：客户明确提出多仓操作隔离，或出现第二法人实体。在此之前不做，避免为不存在的需求预付复杂度。

---

## 4. 实施清单（供 plan 阶段拆任务）

| # | 动作 | 产物 | 回滚 |
| --- | --- | --- | --- |
| 1 | V3.9.5_54 脚本：5 角色建立 + 矩阵授权 + admin 补 2 权限点 + login_tenant_id 修正 + 孤儿清理 + 演示租户停用 | `V3.9.5_54__wms_rbac_roles.sql` | 按固定 id 段 DELETE，脚本内附回滚段（注释形式） |
| 2 | 权限三方对齐脚本 | `scripts/wms-perms-check.py` | 无副作用 |
| 3 | 根 CLAUDE.md 后端约定补「新权限点授三角色」惯例 | CLAUDE.md 一段 | 无 |
| 4 | 验证：5 个测试账号（每角色 1 个）登录核对菜单树与按钮可见性（Playwright 快照比对） | 验收记录入 plan 验收报告 | 删测试账号 |
| 5 | 单测：如涉及后端（预计零改动则无此项） | — | — |

**明确不做（本期）**：数据权限落地、套餐通道启用、平台代码改动、删除演示菜单行（仅从业务角色剥离可见性，不动数据，平台升级零风险）。

## 5. 附：方案 B（真实租户+套餐）迁移路径存档

触发条件成立时：① 建租户 `永鑫` + 套餐「WMS 全量」（`sys_tenant_pack_perms` 由脚本从 admin 角色的 108 点全量导出插入，杜绝手选漏项）；② 业务角色改 `tenant_id=新租户`；③ 用户迁 `sys_user_tenant`；④ 此后新增菜单的授权惯例改为「角色 + 套餐双插」。除 ①④ 外均为数据操作，无代码改动。

---

## Self-Review

- 用户 prompt 三项技能覆盖：§1 现状（机制+数据+验证了租户角色支持与套餐直挂机制）、§3/§5 方案（含备选与迁移路径）、本文档即交付物。
- 用户补充要求「结合永鑫WMS需求」：§2 目标全部锚定单工厂/岗位分权/打印盘点场景；P1-P6 全部标注了对交付的影响。
- 与既有工作一致性：沿用 Flyway 幂等惯例、权限码零变更、scripts 检查脚本风格；不与 16 条已交付流程的任何实现冲突（纯授权层，无代码路径变化）。
