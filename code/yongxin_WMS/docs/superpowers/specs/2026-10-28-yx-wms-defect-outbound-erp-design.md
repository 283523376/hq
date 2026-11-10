# 永鑫WMS — 纯不良品自动开单回写金蝶其他入库单 设计文档

- 日期：2026-10-28
- 状态：已批准（用户 2026-10-28 指示「纯不良品入库进金蝶，由WMS自动开单，同步到金蝶的其他入库单」；
  初稿误写「其他出库单」，当日按用户更正改为其他入库单）
- 关联：`2026-10-27-yx-wms-production-inbound-push-design.md`（生产入库下推）、
  记忆 `wms-automation-first-principle`（自动化优先）、`erp-source-of-truth-bill-number`（ERP 单号为准）

## 1. 问题

生产入库提交时若**良品=0、不良>0**（纯不良品），记录直接落不良品仓库存、`syncStatus=待同步`，
但回写器 `WmsKingdeeInboundWriterImpl.write()` 直接失败：

> 金蝶生产入库单不支持纯不良品回写（PRD_INSTOCK 无不良数量字段），请人工处理

结果：纯不良品记录**永远同步失败**，金蝶侧无任何单据，违背「自动化优先」原则
（人工介入只应留给异常通道，而不是正常业务流程）。

## 2. 方案概述

纯不良品入库记录在同步时，**自动**生成金蝶「其他入库单」（`STK_Miscellaneous`）：
保存 + 提交，审核留给金蝶工作流。全程无人工输入。

```
生产入库提交（良品=0）
  └─> wms_production_inbound（现状不变，状态 INBOUND、待同步）
        └─> WmsKingdeeInboundWriterImpl.write()  ← 分支点（良品<=0 时委托）
              └─> WmsKingdeeDefectWriterImpl.write()   新增
                    ├─ 幂等：erp_bill_no 已有值 → 按单号查金蝶状态（B/C 视为完成，否则 Submit）
                    ├─ 查 PRD_MO 取生产组织 + 车间（车间即入库单部门）
                    ├─ Save STK_Miscellaneous（不传 FBillNo，金蝶按编码规则生成 QTRK…）
                    ├─ Submit
                    └─ 回存 erp_bill_no 到 wms_production_inbound
```

分支放在 inbound writer 内部（而不是 gateway），重试编排 / 退避 / 告警 / 手工同步按钮 /
`sync.auto` 闸门全部原样复用。

## 3. 金蝶报文（真机验证过的结构）

分录/头字段与 2026-10-27 真机脚本一致（QTRK10126100001/2/3 即用此结构 Save+Submit 成功）：

```
Save STK_Miscellaneous:
  FBillTypeID   {FNumber: defect.bill-type，默认 QTRKD01_SYS 标准其他入库单}
  FStockOrgId   {FNumber: 工单生产组织}
  FDate         当天
  FOwnerTypeIdHead  BD_OwnerOrg
  FOwnerIdHead      {FNumber: 组织}
  FDEPTID       {FNumber: 车间}   ← 部门/客户二选一必填；车间编号可当部门编号用
  FEntity:
    FMATERIALID {FNumber}   FSTOCKID {FNumber: stock-code.scrap}
    FUnitID / FBASEUNITID {FNumber}   FSTOCKSTATUSID {FNumber: KCZT01_SYS}
    FLOT {FNumber: 批号}   FQty = FBASEQTY = 不良数量   FInStockType = "1"
    FOwnertypeid/FOWNERID、FKEEPERTYPEID/FKEEPERID = 组织
```

提交后单号由金蝶生成（如 `QTRK10126100001`）。

**真机行为边界**（2026-10-27/28 实测，与其他库存单据同款）：

| 操作 | 结果 |
|---|---|
| Save / Submit | ✅ |
| Audit / UnAudit | ❌ 「已关联工作流实例，不支持 WebAPI 传统审批」 |
| 删除已提交单 | ❌ 只能删暂存态 |

因此 WMS 只做 Save+Submit；审核在金蝶流程中心完成。测试账套挂起流程（审批人未配置）
待管理员处理；探测残留 QTRK10126100001/2/3 需管理员清理。

## 4. 已知风险（交底）

- **入库仓库指向**：不良品记入金蝶哪个仓由 `stock-code.scrap` 决定（当前暂配 F.03
  非标一部刀具废品仓），生产环境用哪个仓需顾问确认；`stock-code-by-org` 支持按组织覆盖。
- 测试账套其他入库单挂工作流且流程挂起，审核态无法自动化验证，与其他入库单同因，
  等管理员处理流程配置后自然解除。

## 5. 改动清单

| 层 | 改动 |
|---|---|
| Flyway | `V3.9.5_32__wms_prod_inbound_erp_bill_no.sql`：`wms_production_inbound` 加 `erp_bill_no VARCHAR(64) NULL` |
| 实体 | `WmsProductionInbound` 加 `erpBillNo` |
| 配置 | `WmsKingdeeProperties.Defect{billType, deptNumber}`（`wms.sync.erp.kingdee.defect.*`）+ application-dev.yml 示例 |
| sync | 新增 `IWmsKingdeeDefectWriter` + `WmsKingdeeDefectWriterImpl`；`WmsKingdeeInboundWriterImpl` 良品≤0 时委托 |
| 登记表 | `WmsKingdeeSyncScope` 加 `DEFECT_INBOUND`；并把 `MATERIAL` 修正为 NOT_IMPLEMENTED（登记 ENABLED 但实现不存在，属登记失真，物料同步任务落地时再翻转） |
| 单测 | `WmsKingdeeDefectWriterImplTest`（新）；`WmsKingdeeInboundWriterImplTest` 纯不良品断言改为「委托 defect writer」 |
| 前端 | 生产入库详情抽屉显示「金蝶单号」 |

## 6. 验收口径

1. 纯不良品入库提交后（`sync.auto=true`）自动出金蝶其他入库单（提交态），`erp_bill_no` 回存，页面可查；
2. 同步失败时按既有退避重试 + 告警 + 手工同步按钮兜底；
3. 单测全绿；真机 Save+Submit 至少一次全链路成功（报文结构 2026-10-27 已验证）。
