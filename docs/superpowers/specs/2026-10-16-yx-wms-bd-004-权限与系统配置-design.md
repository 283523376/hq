# 永鑫WMS 权限与系统配置（YX-WMS-BD-004）— 立项设计

- 日期：2026-10-16
- 类型：新功能（新表 + 新接口 + 新页面 + 权限种子）
- 依据：蓝图《永鑫WMS软件蓝图原始文档》§YX-WMS-BD-004（步骤 / 异常处理原文见 §1.1）
- 状态：**待实施**（设计已经需求方确认，2026-10-16）
- 前置：删除守卫专项（同日完成）已把 7 张 wms 表的删除语义统一为 `deleted_at` 时间戳；本设计沿用同一方案

---

## 0. 已确认决策（留痕，勿再重开）

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 冲突判定口径 | **值域自洽 + key 唯一** | 蓝图只给了提示文案、未定义规则。取「最低 ≤ 安全 ≤ 最高 且均 ≥ 0」+「阈值 > 0」+「config_key 不得重复」；不做跨组规则 |
| 回滚机制 | **版本历史表 + 回滚接口** | 蓝图步骤 7 要求「写入数据库，**留痕**」、异常处理要求「回滚至上一版本」——单靠事务回滚满足不了留痕，也做不出跨会话回滚 |
| 元数据位置 | **写进 Java 枚举**，不建元数据表 | 当前仅 4 组 9 项；「运营自助加配置项」的需求尚未出现（YAGNI）。将来要演进时再补元数据表，**值表结构不变** |
| 保存粒度 | **按分组独立保存** | 需求方明确选择（页面形态为「左菜单 + 分组独立保存」）。**注意这是对蓝图措辞的偏离**，见 §1.2 |
| 配置生效范围 | 设备两项阈值**接入**现有扫描任务；其余三组先存取 | 设备离线阈值现为硬编码常量，有真实消费方；库存/不良品暂无表可依据 |
| 不良品默认仓 | **要做**（`defect.warehouse.code`） | 需求方 2026-10-16 确认（蓝图只说「是否入线边柜」，本项为补充） |
| 同步范围形态 | `sync.scope` 多选：库存账 / 出入库记录 / 柜内库存 | 需求方确认 |

### 0.1 与蓝图的两处偏离（已在对话中告知并获同意）

1. **「保存全部配置」→「按组保存」**（§1.2）。
2. 蓝图把「维护账号/角色/菜单权限」列为步骤 1-2；本项目**使用平台自带能力**，不重建，本流程只补 WMS 自己的菜单与按钮权限种子（§7）。

## 1. 需求

### 1.1 蓝图原文（YX-WMS-BD-004）

> 系统管理员维护菜单权限、用户权限、角色权限。配置库存预警阈值、上位系统同步范围、不良品存放规则（是否入边柜）、设备告警阈值等系统参数。**配置冲突或保存失败时提示并回滚。**

| 步骤 | 系统管理员 | WMS系统 | 异常处理 |
| --- | --- | --- | --- |
| 1 | 登录管理端，进入系统管理 | | |
| 2 | 维护账号与角色权限 | 校验权限编码唯一性 | 冲突→提示 |
| 3 | 配置库存预警阈值 | 保存阈值参数 | 阈值非法→提示 |
| 4 | 配置上位系统同步范围 | 保存同步规则 | |
| 5 | 配置不良品存放规则 | 保存规则 | |
| 6 | 配置设备告警阈值 | 保存阈值 | |
| 7 | 保存全部配置 | 写入数据库，**留痕** | 保存失败→回滚并提示 |

> 异常处理：**配置冲突**：提示"配置项存在冲突，请检查"。**保存失败**：回滚至上一版本，并提示失败原因。

### 1.2 偏离说明：「保存全部配置」→「按组保存」

蓝图的步骤 7 是「保存**全部**配置」。需求方选择的前端形态是「左菜单 + 分组独立保存」，因此实际语义变为**按组保存**。

**接受这一偏离的后果**（明示，不隐藏）：可能出现「库存阈值已保存、设备阈值尚未保存」的中间态。
缓解：① 各组表单在离开未保存时会提示；② 每次保存都写历史，任何一组的上一版本都可回退；③ 「配置冲突」的判定**不跨组**（见 §0），因此按组保存不会漏掉任何应检的规则。

### 1.3 需求映射（用户诉求 → 本设计的落点）

| 用户诉求 | 落点 |
| --- | --- |
| 1. 基于平台权限体系配置 WMS 菜单/按钮权限 | §7（补种子；账号/角色/菜单维护是平台自带） |
| 2. 系统配置表 `wms_system_config`（4 列） | §2.1（4 列 + 项目惯例字段） |
| 3. 四类配置项 | §3 |
| 4. 查询 / 保存 / 回滚接口 | §4、§5 |
| 5. 前端分组展示页 | §8 |
| 约束：冲突提示固定文案 | §5.2 |
| 约束：保存失败回滚至上一版本 | §5.1、§5.3 |
| 验收：保存读取正常 / 冲突有明确提示 | §9 |

## 2. 数据模型

### 2.1 `wms_system_config`（主表）

```sql
CREATE TABLE IF NOT EXISTS `wms_system_config` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `config_key`    varchar(100) NOT NULL COMMENT '配置键',
  `config_value`  varchar(500) DEFAULT NULL COMMENT '配置值（统一按字符串存，读取时按元数据类型解析）',
  `config_desc`   varchar(200) DEFAULT NULL COMMENT '配置说明',
  `deleted_at`    bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_system_config_key` (`config_key`, `deleted_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统配置';
```

**为什么必须带 `deleted_at`，而不是直接用单列唯一索引 `(config_key)`**：
本项目 2026-10-15 刚修掉一类缺陷（V3.9.5_8/_9）——**实体带 `@TableLogic`、唯一索引却只建在业务列上**，
会让「删除后重建同一编码」撞索引、并让查重与实际占用不一致。本表与其他 7 张 wms 表保持同一形态，
实体同样使用 `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")`。
若将来决定本表永不逻辑删除，则应同时去掉 `@TableLogic` 与 `deleted_at`，**不可只去其一**。

值统一按字符串存储（`config_value` 一列），由 §3 的元数据负责类型解析与校验。
这样加配置项不必改表；代价是数据库层无法做类型约束，由应用层校验承担（见 §5.2）。

### 2.2 `wms_system_config_history`（留痕 + 回滚依据）

```sql
CREATE TABLE IF NOT EXISTS `wms_system_config_history` (
  `id`           varchar(32)  NOT NULL COMMENT '主键',
  `config_key`   varchar(100) NOT NULL COMMENT '配置键',
  `config_value` varchar(500) DEFAULT NULL COMMENT '该版本的配置值',
  `version_no`   int          NOT NULL COMMENT '该配置键的版本号，从 1 递增',
  `change_type`  varchar(20)  NOT NULL COMMENT '变更类型：SAVE=保存 ROLLBACK=回滚',
  `create_by`    varchar(50)  DEFAULT NULL COMMENT '操作人',
  `create_time`  datetime     DEFAULT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_cfg_hist_key` (`config_key`, `version_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统配置版本历史';
```

**不建唯一索引**：历史表是只追加的流水，同一 `(config_key, version_no)` 一旦出现重复就说明写历史的并发控制出了问题，
应在应用层用「当前最大 version_no + 1」在事务内保证，而不是靠数据库报错兜底（报错信息对用户无意义）。
若并发确实需要硬保证，后续可加 `UNIQUE (config_key, version_no)`——**但那时要同时处理冲突重试**，故本轮不加。

**本表不做逻辑删除、不参与删除守卫**：它是只追加的审计流水，永不删除。

## 3. 配置项清单（4 组 / 9 项）

元数据由 Java 枚举承载：`org.jeecg.modules.wms.constant.WmsConfigKey`，
每项含 `key / 中文名 / 分组 / 类型 / 默认值 / 校验规则`。

| 组（group） | key | 中文名 | 类型 | 默认值 | 校验 |
| --- | --- | --- | --- | --- | --- |
| `inventory` 库存预警阈值 | `inv.stock.min` | 最低库存 | INT | 0 | ≥0 且 ≤ `safe` |
| | `inv.stock.safe` | 安全库存 | INT | 0 | ≥0 且 ∈ [min, max] |
| | `inv.stock.max` | 最高库存 | INT | 0 | ≥0 且 ≥ `safe` |
| `sync` 上位系统同步范围 | `sync.scope` | 同步范围 | TEXT（多选，逗号分隔） | `库存账,出入库记录` | 非空；取值必须属于 {库存账, 出入库记录, 柜内库存} |
| | `sync.auto` | 自动同步 | BOOL | `true` | 取值 ∈ {true,false} |
| `defect` 不良品存放规则 | `defect.bin.allowed` | 不良品是否入线边柜 | BOOL | `false` | 取值 ∈ {true,false} |
| | `defect.warehouse.code` | 不良品默认仓 | TEXT | 空 | 可空；非空时长度 ≤ 64（与库区编码同宽） |
| `device` 设备告警阈值 | `device.offline.threshold.seconds` | 设备离线判定阈值（秒） | INT | **600** | >0 |
| | `device.alert.throttle.minutes` | 同一设备告警最小间隔（分钟） | INT | **30** | >0 |

（`device` 两项的默认值与现有常量 `WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS` / `DEVICE_ALERT_THROTTLE_MINUTES` 一致，见 §6。）

**库存三项的组内自洽规则**（「值域自洽」的具体落地）：`min ≤ safe ≤ max` 必须同时成立，
违反即判为冲突（例：最低库存 100、最高库存 50 → 冲突）。

**明确不做**（需求方选择的口径所排除）：跨组规则（如「不良品不入线边柜」与线边柜相关配置的互斥）。
若将来要加，加在 `WmsConfigValidator` 里并按组触发全量校验即可。

**前提说明**：库存预警阈值与不良品存放规则**当前无消费方**（尚无库存表、不良品台账）。
本轮它们只做「存储 + 校验 + 版本留痕」，待相应模块落地后接入——这是需求方确认的范围（§0）。

## 4. 接口设计

统一返回 `Result<T>`；路径前缀 `/wms/systemConfig`；鉴权沿用登录态 + 按钮权限（§7）。

| # | 方法 | 路径 | 入参 | 出参 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1 | GET | `/definitions` | — | `Result<List<WmsConfigGroupVO>>` | 分组与项的元数据（组名、key、中文名、类型、当前值、是否可回滚），驱动前端渲染。**一次取全**，前端左侧分组菜单与右侧表单都由它渲染 |
| 2 | GET | `/list` | `group` | `Result<List<WmsConfigItemVO>>` | 取某组的当前值（含每项的 `versionNo`、`canRollback`） |
| 3 | POST | `/save` | `WmsConfigSaveDTO{group, items:[{key,value}]}` | `Result<?>` | 校验 → upsert → 留痕（§5.1） |
| 4 | POST | `/rollback` | `WmsConfigRollbackDTO{group}` | `Result<?>` | 该组回滚到上一版本（§5.3） |
| 5 | GET | `/history` | `key` | `Result<List<WmsSystemConfigHistory>>` | 版本流水（降序）。**让「留痕」可查证**——否则历史表只写不可审 |

`WmsConfigItemVO`：`{key, name, group, type, value, versionNo, canRollback, options?}`（`options` 供多选/布尔渲染）。

**为什么 `/definitions` 与 `/list` 都带值**：职责不同，不重复请求。
`/definitions` 是**页面首次加载的唯一入口**（结构 + 全部组的值，一次拿全，左侧菜单与右侧表单都由它渲染）；
`/list?group=` 用于**单个分组的局部刷新**（保存或回滚后只刷该组），避免为了刷新一组而重取全量。两者字段同构，前端可复用同一渲染函数。

## 5. 保存、校验与回滚语义

### 5.1 保存某组（`POST /save`）

单事务内依次：

1. **校验**：入参组的每个 key 必须属于该组（防越组写入）→ 逐项按 §3 类型与值域校验 → 组内自洽规则（`min ≤ safe ≤ max`）。
   任一项不过 → 抛异常，**整体不落库**。
2. **upsert 主表**：按 `config_key` 查在存行，有则更新、无则插入（`deleted_at = 0`）。
3. **留痕**：为该组每个 key 追加一条历史行，`version_no` = 该 key 当前最大版本号 + 1，`change_type = SAVE`。

**「保存失败则回滚」的实现**：整条路径 `@Transactional(rollbackFor = Exception.class)`。
校验失败发生在任何写之前；若第 3 步写历史时失败，第 2 步的 upsert 一并回滚——
**不会出现「值改了但没留痕」或「部分 key 已改」**。

### 5.2 冲突提示（固定文案 + 结构化明细）

蓝图规定的文案是**逐字**的。为避免「加了明细就破坏了逐字断言」，采用两步表达：

- `Result.success = false`、`Result.message` **逐字等于** `配置项存在冲突，请检查`
  （蓝图异常处理原文，验收按逐字比对）；
- 具体原因放在**同一次响应**的 `Result.result` 里：`{ conflicts: [{ key, name, reason }] }`，
  前端在固定提示下方列出明细（例：「库存预警阈值：最低库存(100) 不能大于 最高库存(50)」）。

这样同时满足两个验收点——「冲突时有明确提示」与蓝图原文的逐字要求。（与删除守卫专项的 `WmsDeleteCheckVO` 同构。）

### 5.3 回滚某组（`POST /rollback`）

单事务内：

1. 取该组每个 key 的**次新**版本（按 `version_no` 降序第 2 条，即「上一版本」）；
2. 写回主表；
3. 为每个被回滚的 key 追加一条 `change_type = ROLLBACK` 的历史行（`version_no` = 当前最大值 + 1）。

**不删除任何历史行**——回滚本身也要留痕，否则审计链断裂。

边界：
- 某 key 只有 1 个版本（从未被改过）→ 该 key **无可回滚版本**，跳过并在响应里说明；若全组都无可回滚版本 → 返回明确提示（如「当前分组没有可回滚的历史版本」），**不算冲突**（文案不同、语义不同）。
- 回滚后的值**仍须通过校验**（历史值理论上已校验过，但若校验规则中途加严，旧值可能不合法）→ 不通过则拒绝并提示，不做「先污染后修复」。

## 6. 配置生效：设备告警阈值接入现有扫描任务

现有硬编码（`WmsConstants`）：

- `DEVICE_OFFLINE_THRESHOLD_SECONDS = 600`（心跳静默多久判离线）
- `DEVICE_ALERT_THROTTLE_MINUTES = 30`（同一设备两次告警的最小间隔）

改造原则——**常量降级为兜底默认值，DB 配置优先**：

- 新增只读方法 `IWmsSystemConfigService.getInt(key, fallback)`：读不到行、值空、解析失败、值 ≤ 0
  → **返回 fallback 并打 WARN 日志，绝不抛异常**。
  理由：配置坏了不能连离线扫描任务一起弄死——那会让「设备离线」这条监控链路静默失效，比阈值不准严重得多。
- 调用点：`WmsDeviceServiceImpl.scanOfflineDevices()` 与 `WmsDeviceOfflineScanJob`（按各自实际使用常量的位置就地替换）。
- **不引入缓存**：离线扫描每 5 分钟跑一次，一次 `SELECT` 的成本可忽略；
  缓存会带来失效与一致性问题（改配置后何时生效变成不可预测），本轮明确不做。

> 库存预警阈值与不良品规则**本轮不接线**（无消费方，§0）。

## 7. 权限种子（需求 1）

### 7.1 WMS 现有权限清单（2026-10-16 从迁移脚本实取，供对照）

菜单（`menu_type=1`，父目录 `1910000000000000001`「基础管理」）：
`/wms/material` 物料与分类 · `/wms/supplier` 供应商管理 · `/wms/order` 订单管理 · `/wms/warehouse` 库区与库位 · `/wms/device` 设备管理

按钮权限（27 个）：
`wms:material:{add,edit,delete,import,export}` · `wms:materialCategory:{add,edit,delete}` ·
`wms:supplier:{add,edit,delete}` · `wms:order:{add,edit,delete,audit,dispatch,resync}` ·
`wms:warehouse:{add,edit,delete}` · `wms:location:{generate,attr,release,delete}` · `wms:device:{add,edit,delete,bind}`

### 7.2 本轮新增（脚本 `V3.9.5_10__wms_system_config.sql`）

| 类型 | 名称 | 路径/编码 | 父节点 | 号段 |
| --- | --- | --- | --- | --- |
| 菜单 `menu_type=1` | 系统配置 | `/wms/systemConfig`，组件 `wms/systemConfig/index` | `1910000000000000001` | `1910000000000000081` |
| 按钮 `menu_type=2` | 配置-保存 | `wms:systemConfig:save` | 上 | `1910000000000000082` |
| 按钮 `menu_type=2` | 配置-回滚 | `wms:systemConfig:rollback` | 上 | `1910000000000000083` |

授权行（`sys_role_permission`，admin 角色 `f6817f48af4fb3af11b9e8bf182f618b`）随同号段 `192000000000000008x` 写入。
沿用既有模式：`INSERT IGNORE`（幂等）、`menu_type`/`perms_type`/`del_flag` 等列与既有脚本逐列对齐。

**不新增**：「查询/读取」按钮权限——与既有模块口径一致（读权限由菜单可见性控制，只有写操作有按钮权限）。

**不做**：账号 / 角色 / 菜单权限的维护界面——平台自带（system 模块），蓝图步骤 1-2 已由平台满足（§0.1）。

## 8. 前端设计

新页面 `jeecgboot-vue3/src/views/wms/systemConfig/index.vue` + `systemConfig.api.ts`。

**形态（需求方已选）**：左菜单 + 分组独立保存。

```
系统配置
─────────┬──────────────────────────────────────────
▸ 库存预警   │  库存预警阈值                    [保存] [回滚上一版本]
  同步范围   │
  不良品     │  最低库存   [ 10        ]
  设备告警   │  安全库存   [ 50        ]
             │  最高库存   [ 200       ]
             │  （底部显示该组最后修改人/时间与当前版本号）
```

- 左侧分组由 `GET /definitions` 返回的分组渲染，点击切换右侧表单（不重新请求元数据，值随 definitions 一次带回）；
- 「保存」调 `POST /save`；**冲突时**弹固定文案 `配置项存在冲突，请检查`，并在同一弹窗内列出 `result.conflicts[]` 明细；
- 「回滚上一版本」需二次确认（写明将回退到哪一版本、当前值会变为什么），调 `POST /rollback`；
  无可回滚版本时该按钮禁用并给出原因（`canRollback=false`）；
- 离开未保存的组时提示（缓解 §1.2 的中间态）；
- 权限：页面入口由菜单控制，两个按钮分别用 `wms:systemConfig:save` / `wms:systemConfig:rollback` 做 `v-auth`。

## 9. 测试与验收

### 9.1 后端单测（不启 Spring、不连库，沿用 `WmsTestSupport.inject`）

- **校验器**（`WmsConfigValidator`，纯函数，可穷举）：`min ≤ safe ≤ max` 的通过与三种违反；`≤0`/非数字；bool 非 true/false；多选取值越界；组内 key 越组。
- **冲突文案**：冲突时 message **逐字**等于 `配置项存在冲突，请检查`，且 `result.conflicts` 含定位信息。
- **保存**：成功路径写主表 + 写历史（`version_no` 递增）；校验失败**不调用任何写方法**。
- **回滚**：取次新版本写回；历史追加 `change_type=ROLLBACK`；只有 1 个版本时跳过并提示；无可回滚版本时的提示文案。
- **设备阈值接入**：`getInt` 在读不到 / 空值 / 非法 / ≤0 时返回 fallback 且不抛；扫描任务使用 DB 值。
- **Controller**：5 个端点的返回结构与文案透出。

### 9.2 端到端（真实浏览器 + 接口）

1. 保存库存组（10/50/200）→ 重新进入页面值一致（**验收：配置能正常保存和读取**）；
2. 制造冲突（最低 100 / 最高 50）→ 提示**逐字** `配置项存在冲突，请检查` + 明细（**验收：冲突时有明确提示**）；
3. 确认冲突时**库内值未变**（整体未落库）；
4. 改值为 20/60/300 保存 → 回滚上一版本 → 值回到 10/50/200，且 `/history` 能查到 SAVE→SAVE→ROLLBACK 三条流水；
5. `/history` 查证留痕含操作人与时间；
6. 设备阈值：把离线阈值改为 60 秒 → 触发一次扫描任务 → 日志/行为体现新阈值；再删掉该行 → 回落 600 秒兜底且不报错；
7. 权限：用非 admin 且未授权的角色登录 → 菜单不可见 / 按钮不可用（种子授权生效）。

### 9.3 构建门禁

- 后端：`bash /d/tools/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms -am test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false`
- 前端：`pnpm build`
  **注意**：本检出的 `pnpm build` **不含 `vue-tsc`**（不是类型门禁，且 `vue-tsc@1.8.27` 与 TS 5.9.3 不兼容会崩，已实测），
  前端类型问题只能靠浏览器 e2e + 人工核对发现——这一点在上一专项的验收报告里已记录。

## 10. 工作量（单人粗估）

| 部分 | 估算 |
| --- | --- |
| 建表脚本 + 权限种子（Flyway 1 个脚本） | 0.5 天 |
| 后端（枚举元数据 / 校验器 / service / 4+1 接口 / 设备阈值接入） | 1.5 天 |
| 后端单测 | 1 天 |
| 前端（页面 + 分组菜单 + 冲突与回滚交互） | 1 天 |
| 端到端回归 + 文档 | 0.5 天 |
| **合计** | **≈ 4.5 人日** |
