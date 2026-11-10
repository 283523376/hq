# 纯不良品自动开单回写金蝶其他入库单 — 验收报告

- 日期：2026-10-28
- spec：`docs/superpowers/specs/2026-10-28-yx-wms-defect-outbound-erp-design.md`
  （文件名保留 outbound 字样，正文已是入库单口径——用户当日更正「其他出库单→其他入库单」，
  仅改实现与正文，不改文件名以断链）

## 验收结论：代码完成，报文结构真机已验证；后端 e2e 待后端重启后联调

## 交付内容

| 项 | 内容 |
|---|---|
| Flyway | `V3.9.5_32__wms_prod_inbound_erp_bill_no.sql`（`wms_production_inbound.erp_bill_no VARCHAR(64) NULL`） |
| 后端 | `IWmsKingdeeDefectWriter` + `WmsKingdeeDefectWriterImpl`（STK_Miscellaneous Save+Submit）；`WmsKingdeeInboundWriterImpl` 良品≤0 委托分支 |
| 配置 | `WmsKingdeeProperties.Defect{billType=QTRKD01_SYS, deptNumber}`；application-dev.yml `defect:` 示例块 |
| 登记表 | `WmsKingdeeSyncScope.DEFECT_INBOUND`；`MATERIAL` 修正为 NOT_IMPLEMENTED（原登记 ENABLED 与实现不符） |
| 前端 | 生产入库详情抽屉显示「金蝶单号」 |
| 单测 | `WmsKingdeeDefectWriterImplTest` 7 例；`WmsKingdeeInboundWriterImplTest` 纯不良品断言改为委托（14 例） |

## 测试与验证

- 模块单测：`Tests run: 889, Failures: 0, Errors: 0` ✅（`m.sh -P dev test -pl jeecg-boot-module-wms`）
- 报文真机验证：2026-10-27 同结构 QTRK10126100001/2/3 Save+Submit 成功（101 测试账套）；
  2026-10-28 复核 Audit 被「已关联工作流实例」拦截 → 口径确认 Save+Submit、审核留金蝶
- m2 install 完成（`jeecg-boot-module-wms-3.9.5.jar`），**后端重启后生效**

## 行为变化

纯不良品入库提交后：原先永远同步失败（「请人工处理」）→ 自动开金蝶其他入库单（提交态）、
单号回存 `erp_bill_no`、生产入库页可见；失败走既有退避重试+告警+手工同步按钮。

## 遗留 / 待办

1. **后端 e2e**：需重启后端（8081）后走一遍「同步工单→纯不良品提交→自动开单」，与本轮其他任务联调批次一起做；
   测试账套 QTRK/QTCK 挂起流程（审批人未配置）解除后才能验证审核态
2. 残留清理（金蝶管理员）：`QTRK10126100001/2/3`、`QTCK10126100001`（后者为本轮 QTCK 探针残留）、
   批号 `TST101-001/002/003`（无 API，界面删）
3. `stock-code.scrap`（F.03）为暂配，生产用仓待顾问确认
