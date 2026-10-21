package org.jeecg.modules.wms.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶出库回写的基础资料解析（组织/物料/单位/仓库/仓位/客户）。
 *
 * <p>组织一律跟随生产订单或销售组织配置；找不到时明确失败，不静默使用默认值。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Component
public class WmsKingdeeLookupSupport {

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeLookupSupport(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    /**
     * 生产订单的生产组织编码；查不到返回 null，由调用方明确失败。
     *
     * <p>不能静默回退默认组织：跨组织写单据会造成库存与财务错账，且运行时只有一条 warn。
     */
    public String orgNumberForWorkOrder(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            return null;
        }
        WmsKingdeeResult result = formClient.executeBillQuery("PRD_MO",
                "FPrdOrgId,FPrdOrgId.FNumber",
                "FBillNo='" + workOrderNo.trim().replace("'", "''") + "'", 1);
        if (result.isSuccess() && !result.getRows().isEmpty()) {
            List<Object> row = result.getRows().get(0);
            String number = row.size() > 1 ? str(row.get(1)) : "";
            if (oConvertUtils.isNotEmpty(number)) {
                return number;
            }
        }
        log.warn("金蝶生产订单[{}]未取到生产组织编号，按失败处理（不回退默认组织 {}", workOrderNo,
                properties.getDefaultOrgNumber());
        return null;
    }

    /** 物料记录：[FMATERIALID,FNumber,FName,FBaseUnitId,FUseOrgId,FUseOrgId.FNumber]。 */
    public List<Object> material(String kingdeeCode, String orgNumber) {
        WmsKingdeeResult result = formClient.executeBillQuery("BD_MATERIAL",
                "FMATERIALID,FNumber,FName,FBaseUnitId,FUseOrgId,FUseOrgId.FNumber",
                "FNumber='" + kingdeeCode.replace("'", "''") + "'", 10);
        if (!result.isSuccess() || result.getRows().isEmpty()) {
            return null;
        }
        for (List<Object> row : result.getRows()) {
            if (row.size() > 5 && orgNumber != null && orgNumber.equals(str(row.get(5)))) {
                return row;
            }
        }
        log.warn("金蝶物料[{}]未找到组织[{}]的记录，按失败处理（不跨组织取物料）", kingdeeCode, orgNumber);
        return null;
    }

    /** 单位编码（BD_UNIT.FNumber）。 */
    public String unitNumber(Object unitId) {
        WmsKingdeeResult result = formClient.executeBillQuery("BD_UNIT",
                "FUnitId,FNumber", "FUnitId=" + unitId, 1);
        if (!result.isSuccess() || result.getRows().isEmpty()) {
            return null;
        }
        return str(result.getRows().get(0).get(1));
    }

    /** 仓库编码：按组织覆盖优先，其次通用配置。 */
    public String stockCode(String orgNumber, String stockKey) {
        Map<String, String> byOrg = properties.getStockCodeByOrg().get(orgNumber);
        String code = byOrg == null ? null : byOrg.get(stockKey);
        if (oConvertUtils.isEmpty(code)) {
            code = properties.getStockCode().get(stockKey);
        }
        return oConvertUtils.isEmpty(code) ? null : code;
    }

    /** 仓库启用仓位管理时解析仓位分录内码；未配置仓位或未启用返回 null。 */
    @SuppressWarnings("unchecked")
    public Long stockLocId(String stockCode) {
        String locNumber = properties.getStockLoc().get(stockCode);
        if (oConvertUtils.isEmpty(locNumber)) {
            return null;
        }
        try {
            WmsKingdeeResult stockResult = formClient.executeBillQuery("BD_STOCK",
                    "FStockId,FNumber,FFlexId", "FNumber='" + stockCode.replace("'", "''") + "'", 1);
            if (!stockResult.isSuccess() || stockResult.getRows().isEmpty()) {
                return null;
            }
            List<Object> stockRow = stockResult.getRows().get(0);
            Long flexId = stockRow.size() > 2 ? toLong(stockRow.get(2)) : null;
            if (flexId == null || flexId <= 0) {
                return null;
            }
            WmsKingdeeResult setResult = formClient.executeBillQuery("BD_FLEXVALUES",
                    "FID,FNUMBER", "FID=" + flexId, 1);
            if (!setResult.isSuccess() || setResult.getRows().isEmpty()) {
                return null;
            }
            String setNumber = str(setResult.getRows().get(0).get(1));
            WmsKingdeeResult viewResult = formClient.view("BD_FLEXVALUES", setNumber);
            if (!viewResult.isSuccess()) {
                return null;
            }
            Map<String, Object> root = new ObjectMapper().readValue(viewResult.getRaw(),
                    new TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = root.get("Result") instanceof Map
                    ? (Map<String, Object>) root.get("Result") : null;
            Object model = result == null ? null : result.get("Result");
            Object entries = model instanceof Map ? ((Map<?, ?>) model).get("FLEXVALUESENTRY") : null;
            if (!(entries instanceof List)) {
                return null;
            }
            for (Object item : (List<?>) entries) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) item;
                if (locNumber.equals(str(row.get("Number")))) {
                    return toLong(row.get("Id"));
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("解析仓库[{}]仓位失败：{}", stockCode, e.getMessage());
            return null;
        }
    }

    /** 客户内码（BD_Customer）。 */
    public Long customerId(String customerCode, String orgNumber) {
        String filter = "FNumber='" + customerCode.replace("'", "''") + "'";
        if (oConvertUtils.isNotEmpty(orgNumber)) {
            // 多组织账套下同一编码可能有多条客户，必须按使用组织过滤，否则会取到别组织内码
            filter += " AND FUseOrgId.FNumber='" + orgNumber.replace("'", "''") + "'";
        }
        WmsKingdeeResult result = formClient.executeBillQuery("BD_Customer",
                "FCUSTID,FNumber", filter, 10);
        if (!result.isSuccess() || result.getRows().isEmpty()) {
            return null;
        }
        return toLong(result.getRows().get(0).get(0));
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
