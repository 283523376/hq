package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶生产用料清单 PRD_PPBOM 读取。
 *
 * <p>字段实测（测试环境）：单据头 FMOBillNO；子项实体 FEntity 的
 * FMaterialID2.FNumber/FName、FNeedQty2、FMustQty、FPickedQty、FStockID.FNumber、FLot、FUnitID2.FNumber、FID。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsKingdeePpbomReaderImpl implements IWmsKingdeePpbomReader {

    private static final String FORM_ID = "PRD_PPBOM";
    private static final String FIELDS = "FMaterialID2.FNumber,FMaterialID2.FName,FNeedQty2,FMustQty,"
            + "FPickedQty,FStockID.FNumber,FLot,FUnitID2.FNumber,FID";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeePpbomReaderImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    @Override
    public List<WmsOutboundRequirementVO> read(String workOrderNo) {
        if (!properties.isEnabled() || oConvertUtils.isEmpty(workOrderNo)) {
            return List.of();
        }
        try {
            WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, FIELDS,
                    "FMOBillNO='" + workOrderNo.trim().replace("'", "''") + "'", 100);
            if (!result.isSuccess()) {
                log.warn("金蝶用料清单查询失败（工单 {}）：{}", workOrderNo, result.getMessage());
                return List.of();
            }
            List<WmsOutboundRequirementVO> list = new ArrayList<>();
            for (List<Object> row : result.getRows()) {
                WmsOutboundRequirementVO vo = toRequirement(row);
                if (vo != null) {
                    list.add(vo);
                }
            }
            return list;
        } catch (Exception e) {
            log.warn("金蝶用料清单读取异常（工单 {}）：{}", workOrderNo, e.getMessage());
            return List.of();
        }
    }

    private WmsOutboundRequirementVO toRequirement(List<Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        String kingdeeCode = str(row.get(0));
        if (oConvertUtils.isEmpty(kingdeeCode)) {
            return null;
        }
        BigDecimal need = toDecimal(row.size() > 2 ? row.get(2) : null);
        if (need == null) {
            need = toDecimal(row.size() > 3 ? row.get(3) : null);
        }
        BigDecimal picked = toDecimal(row.size() > 4 ? row.get(4) : null);
        BigDecimal required = nvl(need).subtract(nvl(picked));
        if (required.signum() <= 0) {
            return null;
        }
        String wmsCode = reverseMaterialCode(kingdeeCode);
        WmsOutboundRequirementVO vo = new WmsOutboundRequirementVO();
        vo.setMaterialCode(wmsCode == null ? kingdeeCode : wmsCode);
        vo.setMaterialName(str(row.size() > 1 ? row.get(1) : null));
        vo.setRequiredQty(required);
        vo.setBatchNo(emptyToNull(str(row.size() > 6 ? row.get(6) : null)));
        vo.setUnit(str(row.size() > 7 ? row.get(7) : null));
        vo.setSourceEntryId(str(row.size() > 8 ? row.get(8) : null));
        vo.setMatched(wmsCode != null);
        return vo;
    }

    /** material-map（WMS → 金蝶）反向查找；未配置映射时视为未匹配。 */
    private String reverseMaterialCode(String kingdeeCode) {
        for (Map.Entry<String, String> entry : properties.getMaterialMap().entrySet()) {
            if (kingdeeCode.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null || oConvertUtils.isEmpty(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String emptyToNull(String value) {
        return oConvertUtils.isEmpty(value) ? null : value;
    }
}
