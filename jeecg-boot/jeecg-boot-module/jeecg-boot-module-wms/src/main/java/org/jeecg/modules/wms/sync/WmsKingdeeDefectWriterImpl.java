package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 纯不良品 → 金蝶其他入库单（STK_Miscellaneous）回写实现。
 *
 * <p><b>为什么是「其他入库单」</b>：PRD_INSTOCK 没有不良数量字段（2026-10-28 真机结论），
 * 纯不良品进不了生产入库单；按用户口径（2026-10-28）走其他入库单把不良品记入金蝶账。
 * 报文为 2026-10-27 真机验证过的结构（QTRK10126100001/2/3 即用此结构保存+提交成功，
 * 详见 specs/2026-10-28-yx-wms-defect-outbound-erp-design.md）：
 * <ul>
 *   <li>头：FBillTypeID（defect.bill-type）/ FStockOrgId / FDate / 货主组 / FDEPTID（部门）</li>
 *   <li>分录：物料/仓/单位/库存状态/批号/数量（FQty=FBaseQty）/ 货主保管者组 / FInStockType=1</li>
 *   <li>Audit 会被金蝶工作流挡住（真机已验证），所以只做 Save+Submit，审核留在金蝶</li>
 * </ul>
 *
 * <p><b>单号以金蝶为准</b>（记忆 erp-source-of-truth-bill-number）：不传 FBillNo，
 * 由金蝶按编码规则生成 QTRK…，保存成功后回存 {@code wms_production_inbound.erp_bill_no}；
 * 幂等改为「本地已有 erp_bill_no → 按该单号查金蝶状态」，不再依赖 WMS 单号。
 *
 * <p>入库仓库复用 {@code stock-code.scrap}（含按组织覆盖）——不良品记入金蝶哪个仓
 * 由这一处配置决定（当前 F.03 非标一部刀具废品仓为暂配，待顾问确认）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsKingdeeDefectWriterImpl implements IWmsKingdeeDefectWriter {

    private static final String FORM_ID = "STK_Miscellaneous";
    /** 入库类型（真机验证值 1）。 */
    private static final String IN_STOCK_TYPE = "1";
    /** 仓库配置键：stock-code.scrap / stock-code-by-org.&lt;org&gt;.scrap。 */
    private static final String STOCK_KEY = "scrap";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsKingdeeLookupSupport lookup;
    private final WmsProductionInboundMapper inboundMapper;

    public WmsKingdeeDefectWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                      WmsKingdeeLookupSupport lookup, WmsProductionInboundMapper inboundMapper) {
        this.formClient = formClient;
        this.properties = properties;
        this.lookup = lookup;
        this.inboundMapper = inboundMapper;
    }

    @Override
    public WmsOrderSyncResult write(WmsProductionInbound inbound) {
        if (!properties.isEnabled()) {
            return WmsOrderSyncResult.okMock();
        }
        BigDecimal badQty = inbound.getBadQty() == null ? BigDecimal.ZERO : inbound.getBadQty();
        if (badQty.signum() <= 0) {
            return WmsOrderSyncResult.fail("纯不良品回写要求不良数量大于 0：" + inbound.getInboundNo());
        }
        String wmsBillNo = inbound.getInboundNo();
        try {
            // 1) 幂等：本地已回存金蝶单号 → 直接按该单号跟进状态
            if (oConvertUtils.isNotEmpty(inbound.getErpBillNo())) {
                return followUp(inbound.getErpBillNo());
            }

            // 2) 生产订单：组织 + 车间（部门）。金蝶对部门/客户二选一必填，先给出可读原因
            WmsKingdeeResult moResult = formClient.executeBillQuery("PRD_MO",
                    "FPrdOrgId.FNumber,FWorkShopID.FNumber",
                    "FBillNo='" + inbound.getWorkOrderNo().replace("'", "''") + "'", 1);
            if (!moResult.isSuccess() || moResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到生产订单：" + inbound.getWorkOrderNo());
            }
            List<Object> mo = moResult.getRows().get(0);
            String orgNumber = mo.size() > 0 ? str(mo.get(0)) : "";
            String workshop = mo.size() > 1 ? str(mo.get(1)) : "";
            if (oConvertUtils.isEmpty(orgNumber)) {
                return WmsOrderSyncResult.fail("金蝶生产订单未取到生产组织：" + inbound.getWorkOrderNo());
            }
            if (oConvertUtils.isEmpty(workshop)) {
                return WmsOrderSyncResult.fail("金蝶生产订单未取到车间：" + inbound.getWorkOrderNo()
                        + "（其他入库单部门必填）");
            }

            // 3) 仓库 / 物料 / 单位
            String stockCode = lookup.stockCode(orgNumber, STOCK_KEY);
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsOrderSyncResult.fail("未配置金蝶入库仓库（stock-code.scrap，组织 " + orgNumber + "）");
            }
            String kingdeeCode = properties.getMaterialMap()
                    .getOrDefault(inbound.getMaterialCode(), inbound.getMaterialCode());
            List<Object> material = lookup.material(kingdeeCode, orgNumber);
            if (material == null || material.isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到物料编码：" + kingdeeCode);
            }
            Object unitId = material.size() > 3 ? material.get(3) : null;
            String unitNumber = lookup.unitNumber(unitId);
            if (oConvertUtils.isEmpty(unitNumber)) {
                return WmsOrderSyncResult.fail("金蝶未找到单位编码（FUnitId=" + unitId + "）");
            }

            // 4) 组装报文（字段名大小写与 2026-10-27 真机验证脚本一致），不传 FBillNo
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("FMATERIALID", dict(kingdeeCode));
            entry.put("FSTOCKID", dict(stockCode));
            entry.put("FUnitID", dict(unitNumber));
            entry.put("FBASEUNITID", dict(unitNumber));
            entry.put("FSTOCKSTATUSID", dict(properties.getStockStatusId()));
            if (oConvertUtils.isNotEmpty(inbound.getBatchNo())) {
                entry.put("FLOT", dict(inbound.getBatchNo()));
            }
            entry.put("FOwnertypeid", properties.getOwnerType());
            entry.put("FOWNERID", dict(orgNumber));
            entry.put("FKEEPERTYPEID", properties.getKeeperType());
            entry.put("FKEEPERID", dict(orgNumber));
            entry.put("FQty", badQty);
            entry.put("FBASEQTY", badQty);
            entry.put("FInStockType", IN_STOCK_TYPE);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillTypeID", dict(properties.getDefect().getBillType()));
            model.put("FStockOrgId", dict(orgNumber));
            model.put("FDate", formatDate());
            model.put("FOwnerTypeIdHead", properties.getOwnerType());
            model.put("FOwnerIdHead", dict(orgNumber));
            String deptNumber = oConvertUtils.isEmpty(properties.getDefect().getDeptNumber())
                    ? workshop : properties.getDefect().getDeptNumber();
            model.put("FDEPTID", dict(deptNumber));
            model.put("FNote", "WMS纯不良品入库:" + wmsBillNo
                    + (oConvertUtils.isEmpty(inbound.getDefectRemark()) ? "" : "；" + inbound.getDefectRemark()));
            model.put("FEntity", List.of(entry));

            // 5) 保存 + 提交（审核走金蝶工作流，WebAPI 不做）
            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶其他入库单保存失败：" + saved.getMessage());
            }
            String erpBillNo = saved.getNumber();
            if (oConvertUtils.isEmpty(erpBillNo)) {
                return WmsOrderSyncResult.fail("金蝶其他入库单保存后未返回单号（WMS单号 " + wmsBillNo + "）");
            }
            WmsOrderSyncResult submitted = submit(erpBillNo);
            if (submitted.isSuccess()) {
                // 回存失败必须抛出（外层 catch 记失败、日志带单号）：回存不成功，重试会重复开单
                persistErpBillNo(inbound.getId(), erpBillNo);
                log.info("纯不良品[{}]自动开单金蝶其他入库单成功：{}", wmsBillNo, erpBillNo);
            }
            return submitted;
        } catch (Exception e) {
            log.warn("纯不良品[{}]回写金蝶异常：{}", wmsBillNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    /** 本地已有金蝶单号：按状态跟进——B/C 视为完成，其余（重新审核/暂存）补提交。 */
    private WmsOrderSyncResult followUp(String erpBillNo) {
        WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                "FBillNo='" + erpBillNo.replace("'", "''") + "'", 1);
        if (!existing.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶其他入库单查询失败：" + existing.getMessage());
        }
        if (existing.getRows().isEmpty()) {
            return WmsOrderSyncResult.fail("金蝶其他入库单不存在（本地单号 " + erpBillNo
                    + "，可能被人工删除），请核对后清理 erp_bill_no");
        }
        String status = str(existing.getRows().get(0).get(1));
        if ("C".equals(status) || "B".equals(status)) {
            return WmsOrderSyncResult.ok();
        }
        return submit(erpBillNo);
    }

    /**
     * 回存金蝶单号（只更新该列，不碰其他字段）。
     *
     * <p>失败直接抛异常：没有 erp_bill_no，重试时幂等检查会再次 Save，金蝶侧出现重复单。
     * 抛出后本次同步按失败收口（日志/告警带单号），由人工把单号补进 erp_bill_no 后重试即幂等。
     */
    private void persistErpBillNo(String inboundId, String erpBillNo) {
        // updateById 只更新非空字段：这里只有 id + erpBillNo，不会碰其他列
        //（不用 LambdaUpdateWrapper：它要求 MP 实体缓存，纯单测环境起不来）
        WmsProductionInbound patch = new WmsProductionInbound();
        patch.setId(inboundId);
        patch.setErpBillNo(erpBillNo);
        inboundMapper.updateById(patch);
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶其他入库单提交失败：" + submitted.getMessage());
        }
        return WmsOrderSyncResult.ok();
    }

    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    private String formatDate() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
