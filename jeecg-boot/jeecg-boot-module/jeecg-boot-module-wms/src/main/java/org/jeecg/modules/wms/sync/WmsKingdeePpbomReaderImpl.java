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

    private final WmsMaterialCodeResolver materialResolver;

    public WmsKingdeePpbomReaderImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                     WmsMaterialCodeResolver materialResolver) {
        this.formClient = formClient;
        this.properties = properties;
        this.materialResolver = materialResolver;
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
        vo.setBatchNo(lotToNull(str(row.size() > 6 ? row.get(6) : null)));
        vo.setUnit(str(row.size() > 7 ? row.get(7) : null));
        vo.setSourceEntryId(str(row.size() > 8 ? row.get(8) : null));
        vo.setMatched(wmsCode != null);
        return vo;
    }

    /** 金蝶码 → WMS 码：表列 kingdee_code 优先（恒等映射天然命中），YAML 反向兜底；未命中视为未匹配。 */
    private String reverseMaterialCode(String kingdeeCode) {
        return materialResolver.reverseKingdeeCode(kingdeeCode);
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

    /**
     * 批号归一：金蝶用料清单未指定批号时 FLot 返回的是字符串 "0"（不是 NULL）。
     *
     * <p>必须转成 null 表达「不限批号」——否则领料明细会带批次 "0"，分配时
     * {@code selectAvailable} 按 {@code batch_no = '0'} 过滤，任何真实批次都匹配不上，
     * 表现为「库存明明有货却报库存不足」。与工单同步的批号口径保持一致。
     */
    private String lotToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return (trimmed.isEmpty() || "0".equals(trimmed)) ? null : trimmed;
    }
}
