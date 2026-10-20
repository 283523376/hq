package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 金蝶 PRD_MO → WMS 工单同步。
 *
 * <p>同一 FBillNo 多条分录按数量求和；多物料工单直接跳过（WMS 工单是单物料模型）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Service
public class WmsKingdeeWorkOrderSyncServiceImpl implements IWmsKingdeeWorkOrderSyncService {

    private static final String FORM_ID = "PRD_MO";
    private static final String FIELD_KEYS =
            "FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsWorkOrderService workOrderService;
    private final WmsMaterialMapper materialMapper;

    public WmsKingdeeWorkOrderSyncServiceImpl(WmsKingdeeFormClient formClient,
                                              WmsKingdeeProperties properties,
                                              IWmsWorkOrderService workOrderService,
                                              WmsMaterialMapper materialMapper) {
        this.formClient = formClient;
        this.properties = properties;
        this.workOrderService = workOrderService;
        this.materialMapper = materialMapper;
    }

    @Override
    public WmsKingdeeWorkOrderSyncResultVO syncFromKingdee(String billNo, int top) {
        String filter = oConvertUtils.isEmpty(billNo)
                ? "" : "FBillNo='" + billNo.trim().replace("'", "''") + "'";
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, FIELD_KEYS, filter, top);
        if (!result.isSuccess()) {
            throw new RuntimeException("金蝶工单查询失败：" + result.getMessage());
        }
        WmsKingdeeWorkOrderSyncResultVO vo = new WmsKingdeeWorkOrderSyncResultVO();
        vo.setQueried(result.getRows().size());

        Map<String, List<List<Object>>> byBill = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            if (row == null || row.size() < 7 || row.get(0) == null) {
                vo.getMessages().add("跳过无法解析的行：" + row);
                vo.setSkipped(vo.getSkipped() + 1);
                continue;
            }
            byBill.computeIfAbsent(str(row.get(0)), k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<List<Object>>> entry : byBill.entrySet()) {
            String workOrderNo = entry.getKey();
            List<List<Object>> rows = entry.getValue();
            try {
                if (!syncOne(workOrderNo, rows, vo)) {
                    vo.setSkipped(vo.getSkipped() + 1);
                }
            } catch (RuntimeException e) {
                vo.setSkipped(vo.getSkipped() + 1);
                vo.getMessages().add(workOrderNo + "：" + e.getMessage());
            }
        }
        log.info("金蝶工单同步：查询{}行，新增{}，更新{}，跳过{}",
                vo.getQueried(), vo.getImported(), vo.getUpdated(), vo.getSkipped());
        return vo;
    }

    /** @return true=已处理，false=跳过（原因已写入 messages） */
    private boolean syncOne(String workOrderNo, List<List<Object>> rows,
                            WmsKingdeeWorkOrderSyncResultVO vo) {
        Set<String> materialNumbers = new LinkedHashSet<>();
        BigDecimal planQty = BigDecimal.ZERO;
        for (List<Object> row : rows) {
            materialNumbers.add(str(row.get(3)));
            planQty = planQty.add(toDecimal(row.get(6)));
        }
        if (materialNumbers.size() != 1) {
            vo.getMessages().add(workOrderNo + "：金蝶工单含多个物料，WMS 单物料模型不支持，已跳过");
            return false;
        }
        String kingdeeMaterial = materialNumbers.iterator().next();
        String wmsMaterialCode = reverseMaterialCode(kingdeeMaterial);
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", wmsMaterialCode).last("LIMIT 1"));
        if (material == null) {
            vo.getMessages().add(workOrderNo + "：物料 " + kingdeeMaterial
                    + " 映射为 " + wmsMaterialCode + "，但 WMS 物料不存在，已跳过");
            return false;
        }
        // 产品类型按物料配置（金蝶 FProductType 是主产品/联产品/副产品，不能区分半成品/成品）
        String productType = properties.getMaterialProductType().get(wmsMaterialCode);
        if (oConvertUtils.isEmpty(productType)) {
            String fallback = properties.getProductTypeMap().get(str(rows.get(0).get(2)));
            if (oConvertUtils.isNotEmpty(fallback)) {
                productType = fallback;
                log.warn("物料[{}]未配置 material-product-type，使用 FProductType={} 兜底为 {}，请补充物料映射",
                        wmsMaterialCode, str(rows.get(0).get(2)), fallback);
            }
        }
        if (oConvertUtils.isEmpty(productType)) {
            vo.getMessages().add(workOrderNo + "：物料 " + wmsMaterialCode
                    + " 未配置产品类型（material-product-type），已跳过");
            return false;
        }
        if (planQty.signum() <= 0) {
            vo.getMessages().add(workOrderNo + "：计划数量为 0，已跳过");
            return false;
        }
        String status = mapStatus(str(rows.get(0).get(1)));
        String lot = str(rows.get(0).get(5));
        String batchNo = (oConvertUtils.isEmpty(lot) || "0".equals(lot)) ? "KD-" + workOrderNo : lot;

        WmsWorkOrder existed = workOrderService.getByWorkOrderNo(workOrderNo);
        WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
        dto.setWorkOrderNo(workOrderNo);
        dto.setMaterialCode(wmsMaterialCode);
        dto.setProductType(productType);
        dto.setPlanQty(planQty);
        dto.setBatchNo(batchNo);
        dto.setWorkOrderStatus(status);
        dto.setMesOrderNo(workOrderNo);
        workOrderService.push(dto);
        if (existed == null) {
            vo.setImported(vo.getImported() + 1);
        } else {
            vo.setUpdated(vo.getUpdated() + 1);
        }
        return true;
    }

    /** 金蝶物料编码 → WMS 物料编码（materialMap 的反向映射）。 */
    private String reverseMaterialCode(String kingdeeCode) {
        for (Map.Entry<String, String> entry : properties.getMaterialMap().entrySet()) {
            if (kingdeeCode.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return kingdeeCode;
    }

    private String mapStatus(String documentStatus) {
        String mapped = properties.getStatusMap().get(documentStatus);
        if (oConvertUtils.isNotEmpty(mapped)) {
            return mapped;
        }
        if ("C".equals(documentStatus)) {
            return "生产中";
        }
        if ("A".equals(documentStatus) || "B".equals(documentStatus)) {
            return "待生产";
        }
        return "已完成";
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
