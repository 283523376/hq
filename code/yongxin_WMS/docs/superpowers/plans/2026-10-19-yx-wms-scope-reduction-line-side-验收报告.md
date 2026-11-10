# 永鑫WMS 项目范围收窄验收报告（移除原材料+焊接线边仓）

日期：2026-10-19
依据：`docs/superpowers/specs/2026-10-19-yx-wms-scope-reduction-line-side-design.md`
口径：保留表、代码与历史数据；收回菜单与入口、标记停用；可回滚、不删除数据。
结论：**验收通过**。

## 1. 变更清单

| 项 | 内容 |
|---|---|
| 迁移 | `V3.9.5_16__wms_scope_reduce_line_side.sql`：19 个菜单/按钮 `del_flag=1`；「入库管理」redirect 改为 `/wms/work-order`；线边柜库区 `XB01` `status=0` |
| 后端停用标记 | `WmsInboundController`、`WmsInspectionController`、`WmsSupplierController`、`WmsOrderController`、`WmsInboundRouting` 加 `@Deprecated` + 中文说明 |
| 前端停用提示 | 采购入库、检验任务、供应商管理、订单管理四个页面顶部加警告条（仅恢复菜单后可见） |
| 文档 | 新增范围收窄设计；根 `CLAUDE.md`、后端 `CLAUDE.md`、`wms-blueprint/WMS蓝图关键信息.md` §1.1 增加范围变更说明 |
| 设计器 | `wms.ts` 与流程图示例 `harvestInbound.ts` 顶部加「方案 A 节点已停用，保留作历史结构」注释；节点内容未改（按需求方口径） |
| 未重生成 | 蓝图 `.docx`、`flows.json`、`mmd.json` 未改动（等仓库外源文档按新范围修订后再跑流水线） |

## 2. 验收标准对照

| 标准 | 结果 | 证据 |
|---|---|---|
| 采购入库/检验/供应商/订单入口收回 | PASS | 登录后菜单树只含 物料与分类、库区与库位、设备管理、系统配置、生产工单、生产入库 |
| 目录不 404 | PASS | `入库管理` redirect=`/wms/work-order`；浏览器访问 `/wms/inbound` 跳转到 `/wms/work-order` |
| 线边柜库区停用 | PASS | `XB01` `status=0`；数据与 8 个库位保留 |
| 历史数据不删除 | PASS | 供应商/订单/采购入库/检验/线边柜表与行数未变 |
| 保留链路可用 | PASS | RK-002 扫码接口正常返回；后端 wms 模块 **515/515** 单测通过 |
| 迁移成功 | PASS | `flyway_schema_history` `3.9.5.16 success=1`；启动日志「flyway执行sql脚本失败」计数 0 |
| 设计器构建 | PASS | `pnpm build` 成功（486.99 kB / gzip 134.93 kB） |
| 前端构建/检查 | PASS | 四个停用页面 ESLint 无新增错误（仅既有 `index.vue` multi-word 规则） |

## 3. 回滚

按设计文档 §4 执行：19 个菜单/按钮 `del_flag=0`、`入库管理` redirect 改回 `/wms/inbound/receive`、
`XB01` `status='1'`。`sys_role_permission` 未删除，恢复后 admin 授权立即可用。

## 4. 遗留与说明

1. 蓝图 `.docx` 与 `flows.json`/`mmd.json` 仍是范围收窄前的全量内容；需先修订仓库外源文档 `D:\资料\永鑫WMS软件蓝图原始文档.md`，再跑流水线。
2. 设计器思维导图与流程图示例仍显示方案 A 节点，仅以文件头注释标注停用；若后续要求图示也只保留方案 B，需要另做一版结构数据与示例。
3. 线边柜 `status=0` 是现有「停用」语义，不改变库区树/空闲库位查询；真正的入口收回依赖 RK-001 菜单移除，后端接口按非目标保留。
4. 采购入库/订单同步补偿定时任务仍在运行，但不再产生新业务数据；未摘除，避免影响共用开关。
