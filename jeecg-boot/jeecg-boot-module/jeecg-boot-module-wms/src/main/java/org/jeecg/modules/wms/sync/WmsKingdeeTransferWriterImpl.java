package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.mapper.WmsTransferOrderMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 库存调拨 → 金蝶 `STK_TransferDirect` 直接调拨单（Save + Submit）。
 *
 * <p>实测口径（`specs/2026-10-22-yx-wms-ck-004-transfer-interface-analysis.md`）：
 * <ul>
 *   <li>表头必填 9 项、明细必填 11 项；`FStockerId` 取 `BD_WAREHOUSEWORKERS`（非员工编码）且须对组织启用；</li>
 *   <li>101 的 D.01/D.02 不启用仓位管理 → <b>不传仓位</b> 也能 Save/Submit；</li>
 *   <li>Submit 后为 B（审核中），`Audit` 会被工作流拒绝 → 本类<b>绝不调用</b> Audit。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsKingdeeTransferWriterImpl implements IWmsKingdeeTransferWriter {

    private static final String FORM_ID = WmsConstants.KINGDEE_TRANSFER_FORM_ID;

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    /**
     * **仅测试环境**：非空时 {@link #queryStatus} 直接返回该状态（A/B/C/D），不发请求。
     *
     * <p>用途：测试账套没有审批人账号时，验证「C 才算完成」的 C 分支。
     * 生产/正式验收**必须留空**，否则会把单据误判为已审核。
     */
    @org.springframework.beans.factory.annotation.Value("${wms.transfer.simulate-erp-status:}")
    private String simulateErpStatus;

    private final WmsTransferOrderMapper transferMapper;
    private final WmsMaterialCodeResolver materialResolver;

    public WmsKingdeeTransferWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                        WmsTransferOrderMapper transferMapper,
                                        WmsMaterialCodeResolver materialResolver) {
        this.formClient = formClient;
        this.properties = properties;
        this.transferMapper = transferMapper;
        this.materialResolver = materialResolver;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public WmsOrderSyncResult saveAndSubmit(WmsTransferOrder order, List<WmsTransferDetail> details) {
        if (!properties.isEnabled()) {
            log.info("金蝶未启用，调拨单[{}]按模拟已提交处理", order.getTransferNo());
            return WmsOrderSyncResult.okMock();
        }
        if (details == null || details.isEmpty()) {
            return WmsOrderSyncResult.fail("调拨单没有明细，无法回写金蝶");
        }
        String billNo = order.getTransferNo();
        String orgNumber = oConvertUtils.isEmpty(order.getErpOrgCode())
                ? properties.getDefaultOrgNumber() : order.getErpOrgCode();
        try {
            // 1) 幂等：单号已存在 → B/C 视为已提交成功；A → 补 Submit
            WmsKingdeeTransferStatus existing = queryStatus(billNo);
            if (!existing.isSuccess()) {
                return WmsOrderSyncResult.fail(existing.getMessage());
            }
            if (oConvertUtils.isNotEmpty(existing.getDocumentStatus())) {
                String status = existing.getDocumentStatus();
                if (WmsConstants.KINGDEE_DOC_STATUS_SUBMITTED.equals(status)
                        || WmsConstants.KINGDEE_DOC_STATUS_APPROVED.equals(status)) {
                    return WmsOrderSyncResult.ok();
                }
                if (WmsConstants.KINGDEE_DOC_STATUS_SAVED.equals(status)) {
                    return submit(billNo);
                }
                return WmsOrderSyncResult.fail("金蝶调拨单[" + billNo + "]当前状态 " + status + " 不可提交，请人工处理");
            }

            // 2) 仓库编码映射（WMS 库区编码 → 金蝶仓库编码）
            String srcStock = resolveStockCode(orgNumber, order.getOutWarehouseCode());
            if (oConvertUtils.isEmpty(srcStock)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code." + order.getOutWarehouseCode()
                        + "，组织 " + orgNumber + "）");
            }
            String destStock = resolveStockCode(orgNumber, order.getInWarehouseCode());
            if (oConvertUtils.isEmpty(destStock)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code." + order.getInWarehouseCode()
                        + "，组织 " + orgNumber + "）");
            }
            String stockerNumber = oConvertUtils.isEmpty(properties.getTransferStockerNumber())
                    ? properties.getStockerId() : properties.getTransferStockerNumber();
            if (oConvertUtils.isEmpty(stockerNumber)) {
                return WmsOrderSyncResult.fail("未配置调拨单仓管员编码（transfer-stocker-number）");
            }

            // 3) 表头（9 项必填）
            // 单号以金蝶为准（2026-10-28 改造）：不传 FBillNo，由金蝶按编码规则生成
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillTypeID", dict(billType()));
            model.put("FStockOrgId", dict(orgNumber));
            model.put("FStockOutOrgId", dict(orgNumber));
            model.put("FOwnerTypeIdHead", properties.getOwnerType());
            model.put("FOwnerIdHead", dict(ownerId(orgNumber)));
            model.put("FOwnerTypeOutIdHead", properties.getOwnerType());
            model.put("FOwnerOutIdHead", dict(ownerId(orgNumber)));
            model.put("FTransferBizType", bizType());
            model.put("FTransferDirect", transferDirect());
            model.put("FStockerId", dict(stockerNumber));
            model.put("FDate", formatDate(new Date()));

            // 4) 明细（11 项必填 + 仓库/批次/数量；不传仓位）
            List<Map<String, Object>> entries = new ArrayList<>();
            for (WmsTransferDetail detail : details) {
                String materialCode = materialResolver.resolveKingdeeCode(detail.getMaterialCode());
                String unit = resolveUnitNumber(materialCode, orgNumber);
                if (oConvertUtils.isEmpty(unit)) {
                    return WmsOrderSyncResult.fail("金蝶未找到物料或基本单位：" + materialCode);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("FMaterialId", dict(materialCode));
                entry.put("FUnitID", dict(unit));
                entry.put("FBaseUnitId", dict(unit));
                entry.put("FOwnerTypeId", properties.getOwnerType());
                entry.put("FOwnerId", dict(ownerId(orgNumber)));
                entry.put("FOwnerTypeOutId", properties.getOwnerType());
                entry.put("FOwnerOutId", dict(ownerId(orgNumber)));
                entry.put("FKeeperTypeId", properties.getKeeperType());
                entry.put("FKeeperId", dict(keeperId(orgNumber)));
                entry.put("FKeeperTypeOutId", properties.getKeeperType());
                entry.put("FKeeperOutId", dict(keeperId(orgNumber)));
                if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
                    entry.put("FLot", dict(detail.getBatchNo()));
                }
                entry.put("FQty", detail.getActualQty() == null
                        ? detail.getTransferQty() : detail.getActualQty());
                entry.put("FSrcStockId", dict(srcStock));
                entry.put("FDestStockId", dict(destStock));
                entries.add(entry);
            }
            model.put("FBillEntry", entries);

            // 5) Save + Submit（不 Audit）
            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶调拨单保存失败：" + saved.getMessage());
            }
            String number = saved.getNumber();
            if (oConvertUtils.isEmpty(number)) {
                return WmsOrderSyncResult.fail("金蝶调拨单保存后未返回单号（WMS单号 " + billNo + "）");
            }
            WmsOrderSyncResult result = submit(number);
            if (result.isSuccess()) {
                // 回存失败必须抛出：没有 erp_bill_no，重试会重复开单
                WmsTransferOrder patch = new WmsTransferOrder();
                patch.setId(order.getId());
                patch.setErpBillNo(number);
                transferMapper.updateById(patch);
            }
            return result;
        } catch (Exception e) {
            log.warn("调拨单[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    @Override
    public WmsKingdeeTransferStatus queryStatus(String billNo) {
        if (oConvertUtils.isNotEmpty(simulateErpStatus)) {
            log.warn("【模拟】调拨单[{}]金蝶状态被模拟为 {}（wms.transfer.simulate-erp-status），仅限测试",
                    billNo, simulateErpStatus);
            return WmsKingdeeTransferStatus.ok(simulateErpStatus.trim());
        }
        if (!properties.isEnabled()) {
            return WmsKingdeeTransferStatus.mock();
        }
        if (oConvertUtils.isEmpty(billNo)) {
            return WmsKingdeeTransferStatus.fail("金蝶单号为空");
        }
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID,
                "FBillNo,FDocumentStatus", "FBillNo='" + billNo.replace("'", "''") + "'", 1);
        if (!result.isSuccess()) {
            return WmsKingdeeTransferStatus.fail("金蝶调拨单查询失败：" + result.getMessage());
        }
        if (result.getRows().isEmpty()) {
            return WmsKingdeeTransferStatus.ok(null);
        }
        List<Object> row = result.getRows().get(0);
        return WmsKingdeeTransferStatus.ok(row.size() > 1 ? str(row.get(1)) : null);
    }

    /** Submit（工作流单据 Submit 后停在 B，审批由金蝶工作流完成）。 */
    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶调拨单提交失败：" + submitted.getMessage());
        }
        log.info("调拨单回写金蝶并提交成功：ERP单号={}（等待金蝶审批到 C）", number);
        return WmsOrderSyncResult.ok();
    }

    /**
     * 解析物料的基本单位编码：BD_MATERIAL.FBaseUnitId → BD_UNIT.FNumber。
     *
     * <p>调拨单分录的「单位」是必填基础资料字段，不能直接传 WMS 的 PCS 字符串（实测报「单位是必填项」）。
     */
    private String resolveUnitNumber(String materialCode, String orgNumber) {
        WmsKingdeeResult materialResult = formClient.executeBillQuery("BD_MATERIAL",
                "FMATERIALID,FNumber,FBaseUnitId,FUseOrgId",
                "FNumber='" + materialCode.replace("'", "''") + "'", 10);
        if (!materialResult.isSuccess() || materialResult.getRows().isEmpty()) {
            log.warn("金蝶未找到物料[{}]：{}", materialCode, materialResult.getMessage());
            return null;
        }
        List<Object> material = materialResult.getRows().get(0);
        Object unitId = material.size() > 2 ? material.get(2) : null;
        if (unitId == null) {
            return null;
        }
        WmsKingdeeResult unitResult = formClient.executeBillQuery("BD_UNIT",
                "FUnitId,FNumber", "FUnitId=" + unitId, 1);
        if (!unitResult.isSuccess() || unitResult.getRows().isEmpty()) {
            log.warn("金蝶未找到单位编码（FUnitId={}）", unitId);
            return null;
        }
        return str(unitResult.getRows().get(0).get(1));
    }

    private String resolveStockCode(String orgNumber, String wmsWarehouseCode) {        if (oConvertUtils.isEmpty(wmsWarehouseCode)) {
            return null;
        }
        Map<String, String> byOrg = properties.getStockCodeByOrg().get(orgNumber);
        String code = byOrg == null ? null : byOrg.get(wmsWarehouseCode);
        if (oConvertUtils.isEmpty(code)) {
            code = properties.getStockCode().get(wmsWarehouseCode);
        }
        return code;
    }

    private String billType() {
        return oConvertUtils.isEmpty(properties.getTransferBillType())
                ? WmsConstants.KINGDEE_TRANSFER_BILL_TYPE : properties.getTransferBillType();
    }

    private String bizType() {
        return oConvertUtils.isEmpty(properties.getTransferBizType())
                ? WmsConstants.KINGDEE_TRANSFER_BIZ_TYPE : properties.getTransferBizType();
    }

    private String transferDirect() {
        return oConvertUtils.isEmpty(properties.getTransferDirect())
                ? WmsConstants.KINGDEE_TRANSFER_DIRECT : properties.getTransferDirect();
    }

    /** 货主：与生产入库保持一致，传组织编码（传组织内码会被判「调入货主是必填项」）。 */
    private String ownerId(String orgNumber) {
        return orgNumber;
    }

    /** 保管者：同货主口径，传组织编码。 */
    private String keeperId(String orgNumber) {
        return orgNumber;
    }

    private Map<String, Object> dict(String number) {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("FNumber", number);
        return dict;
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unused")
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
