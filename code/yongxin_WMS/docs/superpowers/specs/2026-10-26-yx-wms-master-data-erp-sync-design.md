# 永鑫WMS 主数据 ERP 同步骨架（物料分类 / 工单类型 / 仓库档案）——设计

- 日期：2026-10-26
- 状态：已批准（2026-10-26 需求方批复；builder check 6 项问题已全部消化）
- 需求变更记录：2026-10-26 用户定性为「需求变更，全量落地」，随后收口为本期只做主数据骨架
- 关联：`2026-10-19-yx-wms-scope-reduction-line-side-design.md`（上一轮收窄，本期部分回调）、
  `2026-10-21-yx-wms-bd-002-production-work-order-design.md`（BD-002 工单同步基础）、
  `2026-10-20-yx-wms-kingdee-business-sync-design.md`（金蝶同步框架）、
  `2026-10-26-yx-wms-outbound-subcontract-sample-stocktake-design.md`（下一期事务流程首批，
  其中外协/样品/盘点的金蝶回写仓映射走**各自独立配置**，不依赖本文 E4 的仓→金蝶拍板）

## 1. 背景与动机

2026-10-26 客户口径更新：物料分类 5 类、生产订单 4 类型、仓库 6+1 个。经对齐后收口：

- **本期只立主数据骨架**（分类、工单类型、仓库档案），全部由金蝶 ERP 同步而来；
  事务流程（采购入库/委外入库/采购退货报废出库、供应商/采购订单管理）**下一期**。
- 需求方明确暂缓、后续可能补充开发：**原材料分类、五金配件仓、包材仓**（已记入项目记忆
  `yx-wms-deferred-raw-material-scope.md`；注意 `Y.65 包材仓` 在金蝶 101 组织下真实存在，下期可直接挂）。
- `XB01 线边柜一组` 继续停用；`FPC01 废品仓` 保留启用。

## 2. 目标 / 非目标 / 反目标

### 目标

1. **仓库档案本地立户 + 同步链路就绪**：`LB→半成品仓`、`MJ→产成品仓` 改名，新建
   `CJ 初级产成品仓`、`TC 涂层产成品仓`（均为本地档案，`erp_stock_code` 置空）；
   BD_STOCK→`wms_warehouse` 的白名单 upsert 链路本期建成，客户确认映射并配置白名单后即生效。
2. **物料分类建档 + 同步链路就绪**：Flyway 种子建 4 个分类（`HALF 半成品`、`CJ 初级产成品`、
   `TC 涂层产成品`、`CP 产成品`）；BD_MATERIAL→分类的映射同步链路本期建成，金蝶侧来源字段
   （外部依赖 E2）就位后配置即生效。
3. **工单类型字段与枚举**：`wms_work_order` 新增 `order_type`（客户订单/备库订单/委外订单/受托订单），
   PRD_MO 同步预留类型字段映射；委外/受托**枚举先立、同步下一期**（已确认）。
4. **配置与使用文档**：新增 `docs/wms/主数据同步配置与使用.md`，覆盖配置项、开关、验证方法。

### 非目标（下一期）

- 委外订单（金蝶 SUB 单据）、受托订单（受托加工单据）的同步实现；
- 事务流程（RK-001 / RK-003 / CK-003、供应商与采购订单管理）的启用与开发；
- 原材料分类、五金配件仓、包材仓；
- 蓝图流水线（wms-blueprint）的 V2.3 修订——与事务流程一起做，避免蓝图改两次。

### 反目标（宁可不做也不能做错）

- **不得**把金蝶 101 组织下其余约 190 个仓库（受托仓、呆滞仓、研发仓、按部细分仓等）整表灌入
  WMS——只按白名单取，白名单未定前**一行不拉**；
- **不得**物理删除或改写 WMS 既有分类/仓库历史数据（受《引用完整性与删除守卫》约束）；
- **不得**把金蝶 appSecret 打进日志、前端或仓库内配置（沿用 `WmsKingdeeAuthServiceImpl` 安全约束）。

## 3. 金蝶测试账套实测结论（2026-10-26，侦察脚本 `.tmp_kingdee_recon/`）

| # | 事实 | 对设计的影响 |
|---|---|---|
| F1 | **组织维度是硬约束**：`02.001 产成品仓`/`02.002 自制半成品仓`/`02.005 废品仓` 属 **100 江西兆鑫**（历史组织）；本项目主体 **101 宜昌永鑫** 下有 195 个仓 | BD_STOCK 查询必须带 `FUseOrgId` 过滤（配置 `org-id`，默认 `101`），否则跨组织同名仓会互相覆盖 |
| F2 | 101 下 `X.2.4 涂层产成品仓` 精确命中；半成品/产成品按部细分（`X.1.1.1 铣刀一部半成品仓`、`X.1.1.2 三部`、`X.2.1.1/2/3 一/二/三部产成品仓`），另有 `Y.04 Y半成品仓`、`Y.05 Y成品仓` 总仓；废品仓有 `X.4.1.1`、`Y.26` 等候选 | 同概念仓多选一，**客户未拍板前白名单留空**（已确认，见 E4） |
| F3 | **「初级产成品仓」整个账套不存在**（两组织都没有） | CJ 为纯本地档案，`erp_stock_code` 待金蝶补建后回填 |
| F4 | PRD_MO **无 FBillTypeID**（元数据不存在）；两个自定义字段 `F_UNW_Text_hp8`/`F_UNW_Base_0ml` 抽样全空；`FProductType` 全为 "1" | 工单类型同步**缺金蝶源字段**，本期落枚举+默认值，字段就位后生效（E1） |
| F5 | FMaterialGroup 是颜色/规格维度（酱红色、刃径刃长、周转盒…），无「初级/涂层/已上套环」节点；物料无相关 F_ 字段 | 分类同步**缺金蝶源字段**，同 F4 处理（E2） |
| F6 | 账套含受托仓体系（02.009、02.015、W.24/W.25、Y.41 等），为下一期受托订单预留 | 仅记录，本期不动 |

## 4. 数据模型变更（Flyway `V3.9.5_24__wms_master_data_erp_sync.sql`）

1. `wms_work_order` 加列 `order_type varchar(20) NULL` COMMENT '工单类型：客户订单/备库订单/委外订单/受托订单（ERP 同步，缺省空）'。
   不加列默认值——历史行保持 NULL，展示层兜底。
2. `wms_warehouse` 加列 `erp_stock_code varchar(50) NULL`、`erp_stock_name varchar(100) NULL`；
   唯一索引按本项目软删惯例做复合：`uk_wms_warehouse_erp(erp_stock_code, deleted_at)`
   （单列唯一键会被软删行占死，`warehouse_code` 实体注释踩过同一坑）。
3. `wms_material_category` 加列 `erp_group_number varchar(100) NULL`、`erp_group_name varchar(200) NULL`
   （仅记录命中该分类的**代表**金蝶分组；多对一映射关系在配置，不在此表展开，见 §5.2）。
4. 种子数据（同脚本，幂等 INSERT）：4 个分类
   `HALF 半成品`、`CJ 初级产成品`、`TC 涂层产成品`、`CP 产成品`，`category_type` 分别取
   `半成品`/`初级产成品`/`涂层产成品`/`产成品`；父级均为根（平级）。
   `category_code`/`warehouse_code` 一经业务引用即进「不改码」守卫——编码已由需求方确认
   （HALF/CJ/TC/CP），后续变更需走数据迁移。
5. 仓库档案种子：**不在 Flyway 里建**——沿用 `V3.9.5_5` 的可重复执行思路改为
   同步服务启动检查 + Controller 手动初始化端点：`LB`/`MJ` 改名、`CJ`/`TC` 新建
   （`warehouse_type=普通仓`，`erp_stock_code` 置空）。原因：改名属于数据订正且只做一次，
   放代码路径可记录操作日志并过删除守卫校验。

## 5. 三条同步链路设计

统一约束：全部走既有 `WmsKingdeeFormClient`（ExecuteBillQuery）+ `LoginByAppSecret` 会话；
新增服务放 `org.jeecg.modules.wms.sync` / `service.impl`，Job 放 `job`；
`master-data.enabled` 与既有总开关 `wms.sync.erp.kingdee.enabled`（含 `isConfigured()`）取 **AND**。
`WmsKingdeeFormClient` 现无翻页参数，需加 `StartRow` 重载（BD_MATERIAL 全量扫描用）。

### 5.1 仓库同步（链路就绪，白名单默认空）

- 源：BD_STOCK，FieldKeys `FNumber,FName`，FilterString = `FUseOrgId.FNumber='<org-id>' AND FNumber in (<白名单>)`。
- **`stock-whitelist` 默认空 = 不拉任何行**（反目标 1 的落地方式）；等 E4 客户拍板后配置生效。
- 映射：`FNumber → erp_stock_code`，`FName → warehouse_name`；
  `stock-code-map`（金蝶编码→既有 WMS 仓库码）命中则**只改名不改码**，未命中则按白名单编码 upsert 新建。
- 本期本地档案动作（不依赖金蝶）：`LB` 名改为 `半成品仓`、`MJ` 名改为 `产成品仓`、新建 `CJ 初级产成品仓`、
  `TC 涂层产成品仓`；`XB01` 保持停用；`FPC01 废品仓` 保持启用（其金蝶映射同属 E4 待定）。
- `warehouse_type` 枚举扩一档 `普通仓`（CJ/TC 不属立库/敏捷仓/线边柜；前端 `warehouse.data.ts` 同步加选项）。
- 同步入口：Controller 手动触发端点 + Quartz Job（默认关闭，cron 待上线时启用，与既有 Job 错峰）。

### 5.2 分类同步（链路就绪，金蝶字段就位前自动空转）

- 源：BD_MATERIAL，FieldKeys `FNumber,FName,FMaterialGroup.FNumber,FMaterialGroup.FName`（带翻页）。
- **落点形态（定案）**：4 个 WMS 分类为顶级行；「金蝶分组 → WMS 分类」是**多对一映射，活在配置里**
  （`category-map`）；分类行上的 `erp_group_number/name` 仅记录命中该分类的代表分组，便于人工核对，
  不承担映射职能（避免单列装不下多值）。
- 字段未定义期间行为：查询能跑、映射不命中、**不产生脏数据**（不给物料强塞分类、不新建金蝶侧不存在的分类行）。
- BD-001 物料同步经 builder check 核实**不存在**（无既有 upsert 可复用），本期只同步分类档案映射，
  物料挂靠随下一期事务流程一起做。
- 「原材料」分类（`CAT001`，`category_type=辅料`）**已处于停用状态**（2026-10-26 实查 status=0），
  本期维持现状，无需动作；其下 6 个活跃测试物料不受影响。

### 5.3 工单类型（枚举 + 默认值策略）

- `WmsConstants` 新增四常量，前缀 `WORK_ORDER_TYPE_`（`ORDER_TYPE_` 前缀已被 wms_order 域的
  `ORDER_TYPE_OUTSOURCE` 占用，避让）：`WORK_ORDER_TYPE_CUSTOMER/STOCK/OUTSOURCE/TRUSTED`
  （客户订单/备库订单/委外订单/受托订单）。
- PRD_MO 同步（`WmsKingdeeWorkOrderSyncServiceImpl`）扩展：配置 `order-type-field`（默认空）。
  空 → `order_type` 置 NULL（列表兜底显示「客户订单」）；非空（E1 就位，如 `F_UNW_Text_xxxx`）→
  按值映射表落库，未命中置 NULL 并记一次告警日志。
- 委外/受托：本期不同步、不落库，仅枚举存在（前端下拉可见、后端校验接受）。

## 6. 外部依赖（金蝶/客户侧待落实，全部不阻塞本期上线）

| # | 待办 | 影响链路 | 就位后动作 |
|---|---|---|---|
| E1 | PRD_MO 增加工单类型字段（或启用辅助资料） | 工单类型同步 | 填 `order-type-field` 配置即生效 |
| E2 | 定义物料分类的来源（新分组树或自定义字段） | 分类同步 | 填 `category-map` 配置即生效 |
| E3 | 金蝶建「初级产成品仓」并告知编码 | CJ 仓回填 | `erp_stock_code` 回填即挂接 |
| E4 | **客户拍板仓→金蝶映射**（101 组织下：半成品仓/产成品仓/废品仓各选哪个金蝶仓，含一部/三部与总仓之争） | 仓库同步白名单 | 配 `stock-whitelist` + `stock-code-map` 即生效 |

## 7. 配置项汇总（yml，全部可缺省）

```yaml
wms:
  sync:
    erp:
      kingdee:
        master-data:
          enabled: false            # 主数据同步总开关（默认关，与 kingdee.enabled 取 AND）
          org-id: "101"             # BD_STOCK 组织过滤（101 宜昌永鑫，100 为历史组织不取）
          stock-whitelist: ""       # 默认空=不拉任何仓；E4 拍板后配，如 "X.1.1.1,X.2.1.1,X.2.4,X.4.1.1"
          stock-code-map: ""        # 金蝶编码→既有 WMS 仓库码（只改名不改码），如 "X.2.4:TC"
          category-map: ""          # 金蝶分组规则=分类，E2 就位后配置（多对一）
          order-type-field: ""      # E1 就位后配置，如 F_UNW_Text_xxxx
          order-type-value-map: ""  # 金蝶字段值=类型，如 "客户订单:客户订单,备库:备库订单"
```

## 8. 验收口径

1. **单测**：仓库 upsert（白名单外忽略/改名不改码/新建带普通仓类型）、组织过滤 FilterString 拼装、
   分类映射命中/未命中空转、`WORK_ORDER_TYPE_*` 四枚举与 NULL 兜底策略、守卫回归全绿。
2. **本地档案验收**：手动触发初始化后 `wms_warehouse` 中 `LB=半成品仓`、`MJ=产成品仓`、
   `CJ/TC` 存在且 `warehouse_type=普通仓`、`erp_stock_code` 全空；`wms_material_category` 出现
   HALF/CJ/TC/CP 四行；`XB01` 仍停用。
3. **联调验收（金蝶测试账套）**：白名单为空触发同步 → 0 行拉取、无报错；配一条试验白名单
   （如 `X.2.4`）→ 拉到 1 行、`erp_stock_name=涂层产成品仓`、`erp_stock_code=X.2.4`；分类/工单类型
   同步空转不报错。
4. **文档**：`docs/wms/主数据同步配置与使用.md` 能让运维照着配置并验证同步（含 E1–E4 就位后的启用步骤）。

## 9. 已确认口径与裁量点

**需求方已确认**：全量落地定性；FPC01 保留；XB01 停用；原材料/五金配件/包材暂缓；
分类/工单/仓库由 ERP 同步；LB/MJ 只改名不改码；委外/受托枚举先立；
**仓→金蝶映射挂起待客户确认（本期白名单空）**；编码采用 HALF/CJ/TC/CP（分类）与 CJ/TC（仓库）。

**Claude 裁量点（评审时可否决）**：

- D1 ~~白名单映射初值~~ **已作废**：实测发现 02.x 属历史组织 100，映射整体挂起为 E4。
- D2 「原材料」分类停用——经实查已是既成事实，本期为 no-op，仅记录。
- D3 CJ/TC 新仓走「同步服务初始化端点 + 操作日志」而非 Flyway 种子（改名属数据订正，需过守卫）。
- D4 历史工单 `order_type=NULL` 兜底显示「客户订单」——若客户认为不妥，改为显示「—」。
- D5 蓝图 V2.3 与事务流程一起做，本期不改蓝图。
- D6 主数据同步 Job 默认关闭，用 Controller 手动触发做验收，避免测试账套数据被定时刷。
- D7 分类落点采用「配置多对一映射 + 分类行仅记代表来源」（builder check Q4 的定案）。
- D8 `uk_wms_warehouse_erp` 做成 `(erp_stock_code, deleted_at)` 复合（软删惯例）。
