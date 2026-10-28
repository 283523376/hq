package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsMaterialCodeResolver;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 金蝶 PRD_MO → WMS 工单同步（BD-002 V2.2：ERP 为唯一业务来源）。
 *
 * <p>审核门槛（评审 B1）：C(已审核)→已下达、A/B(未审核)→待生产、D 及未知整单跳过。
 * 同一 FBillNo 多分录按数量求和；多物料或多批次整单跳过（WMS 工单是单物料单批次模型）。
 * 每单实际 upsert 写一条同步日志；整单跳过属永久性原因，不写失败日志、不进重试队列。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Service
public class WmsKingdeeWorkOrderSyncServiceImpl implements IWmsKingdeeWorkOrderSyncService {

    private static final String FORM_ID = "PRD_MO";
    /** FDate 追加在末尾（索引 7），避免打乱既有下标。 */
    private static final String FIELD_KEYS =
            "FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty,FDate";
    private static final int DEFAULT_TOP = 50;
    private static final int MAX_TOP = 200;
    /** 手工同步未给窗口时的默认回看天数。 */
    private static final int DEFAULT_WINDOW_DAYS = 7;
    /** 工单类型字段在查询结果中的下标（FIELD_KEYS 共 8 列，追加项固定排在第 8 位）。 */
    private static final int ORDER_TYPE_INDEX = 8;

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsWorkOrderService workOrderService;
    private final WmsMaterialMapper materialMapper;
    private final IWmsSyncLogService syncLogService;

    private final WmsMaterialCodeResolver materialResolver;

    public WmsKingdeeWorkOrderSyncServiceImpl(WmsKingdeeFormClient formClient,
                                              WmsKingdeeProperties properties,
                                              IWmsWorkOrderService workOrderService,
                                              WmsMaterialMapper materialMapper,
                                              IWmsSyncLogService syncLogService,
                                              WmsMaterialCodeResolver materialResolver) {
        this.formClient = formClient;
        this.properties = properties;
        this.workOrderService = workOrderService;
        this.materialMapper = materialMapper;
        this.syncLogService = syncLogService;
        this.materialResolver = materialResolver;
    }

    /**
     * 金蝶 FDocumentStatus → WMS 工单状态门槛（评审 B1）。
     *
     * @return 已下达/待生产；D 及未知返回 null（整单跳过，不默认已完成）
     */
    static String gateStatus(String documentStatus) {
        if ("C".equals(documentStatus)) {
            return WmsConstants.WORK_ORDER_STATUS_RELEASED;
        }
        if ("A".equals(documentStatus) || "B".equals(documentStatus)) {
            return WmsConstants.WORK_ORDER_STATUS_PENDING;
        }
        return null;
    }

    @Override
    public WmsKingdeeWorkOrderSyncResultVO syncFromKingdee(String billNo, String startDate, String endDate,
                                                           Integer top) {
        int limit = top == null ? DEFAULT_TOP : Math.min(top, MAX_TOP);
        String effectiveStart = startDate;
        String effectiveEnd = endDate;
        int windowDays = 0;
        String filter;
        if (oConvertUtils.isNotEmpty(billNo)) {
            filter = "FBillNo='" + billNo.trim().replace("'", "''") + "'";
        } else {
            validateDate(startDate);
            validateDate(endDate);
            if (oConvertUtils.isNotEmpty(startDate) && oConvertUtils.isNotEmpty(endDate)
                    && startDate.compareTo(endDate) > 0) {
                throw new RuntimeException("开始日期不能晚于结束日期");
            }
            if (oConvertUtils.isEmpty(effectiveStart) && oConvertUtils.isEmpty(effectiveEnd)) {
                effectiveStart = LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS - 1L).toString();
                windowDays = DEFAULT_WINDOW_DAYS;
            }
            StringBuilder sb = new StringBuilder();
            if (oConvertUtils.isNotEmpty(effectiveStart)) {
                sb.append("FDate>='").append(effectiveStart).append("'");
            }
            if (oConvertUtils.isNotEmpty(effectiveEnd)) {
                if (sb.length() > 0) {
                    sb.append(" AND ");
                }
                sb.append("FDate<='").append(effectiveEnd).append("'");
            }
            // 必须限定生产组织：其它组织（如 100 江西兆鑫）的工单物料不在本组织物料集里，
            // 拉进来只会全部「物料不存在」跳过，白占 maxTop 配额
            String orgNumber = properties.getWorkOrderOrgNumber();
            if (oConvertUtils.isNotEmpty(orgNumber)) {
                if (sb.length() > 0) {
                    sb.append(" AND ");
                }
                sb.append("FPrdOrgId.FNumber='").append(orgNumber.trim().replace("'", "''")).append("'");
            }
            filter = sb.toString();
        }
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, resolveFieldKeys(), filter, limit);
        if (!result.isSuccess()) {
            syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS,
                    WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, null,
                    WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, 1, null, null,
                    "金蝶工单查询失败：" + result.getMessage(), 0L);
            throw new RuntimeException("金蝶工单查询失败：" + result.getMessage());
        }
        WmsKingdeeWorkOrderSyncResultVO vo = new WmsKingdeeWorkOrderSyncResultVO();
        vo.setQueried(result.getRows().size());
        vo.setEffectiveStartDate(oConvertUtils.isEmpty(billNo) ? effectiveStart : null);
        vo.setEffectiveEndDate(oConvertUtils.isEmpty(billNo) ? effectiveEnd : null);
        vo.setEffectiveWindowDays(windowDays);

        Map<String, List<List<Object>>> byBill = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            if (row == null || row.size() < 7 || row.get(0) == null) {
                vo.getMessages().add("跳过无法解析的行：" + row);
                vo.setSkipped(vo.getSkipped() + 1);
                continue;
            }
            byBill.computeIfAbsent(str(row.get(0)), k -> new ArrayList<>()).add(row);
        }

        Set<String> unknownOrderTypes = new LinkedHashSet<>();
        for (Map.Entry<String, List<List<Object>>> entry : byBill.entrySet()) {
            String workOrderNo = entry.getKey();
            List<List<Object>> rows = entry.getValue();
            long begin = System.currentTimeMillis();
            try {
                if (syncOne(workOrderNo, rows, vo, unknownOrderTypes)) {
                    syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS,
                            WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, workOrderNo,
                            WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, null, null, null,
                            System.currentTimeMillis() - begin);
                } else {
                    vo.setSkipped(vo.getSkipped() + 1);
                }
                // 整单跳过（多物料/多批次/门槛外状态/映射缺失）属永久性原因：
                // 只进 messages 与 skipped 计数，不写失败日志，避免污染重试队列（评审 I2 的反面——不可重试的不重试）
            } catch (RuntimeException e) {
                vo.setSkipped(vo.getSkipped() + 1);
                vo.getMessages().add(workOrderNo + "：" + e.getMessage());
                workOrderService.markSyncFailed(workOrderNo, e.getMessage());
                syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS,
                        WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, workOrderNo,
                        WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, 1, null, null,
                        e.getMessage(), System.currentTimeMillis() - begin);
            }
        }
        log.info("金蝶工单同步：窗口[{} ~ {}] 查询{}行，新增{}，更新{}，跳过{}",
                vo.getEffectiveStartDate(), vo.getEffectiveEndDate(),
                vo.getQueried(), vo.getImported(), vo.getUpdated(), vo.getSkipped());
        return vo;
    }

    @Override
    public boolean syncSingleFromKingdee(String billNo) {
        if (oConvertUtils.isEmpty(billNo)) {
            return false;
        }
        // 按单号也必须拉全分录：top=1 会只取首行，绕过"多物料/多批次整单跳过"保护（评审 I1）
        WmsKingdeeWorkOrderSyncResultVO vo = syncFromKingdee(billNo, null, null, MAX_TOP);
        return vo.getImported() + vo.getUpdated() > 0;
    }

    @Override
    public WmsWorkOrderInfoVO scanWithErpFallback(String workOrderNo) {
        try {
            return workOrderService.scan(workOrderNo);
        } catch (RuntimeException first) {
            if (oConvertUtils.isEmpty(workOrderNo)) {
                throw first;
            }
            WmsKingdeeWorkOrderSyncResultVO result = syncFromKingdee(workOrderNo, null, null, MAX_TOP);
            if (result.getImported() + result.getUpdated() == 0) {
                log.warn("工单[{}] ERP回源未生成：{}", workOrderNo, result.getMessages());
                throw first;
            }
            return workOrderService.scan(workOrderNo);
        }
    }

    /** @return true=已处理（含 MES/ERP 已有单更新），false=整单跳过（原因已写入 messages） */
    private boolean syncOne(String workOrderNo, List<List<Object>> rows,
                            WmsKingdeeWorkOrderSyncResultVO vo, Set<String> unknownOrderTypes) {
        // 审核门槛最先判：门槛外状态整单跳过（评审 B1，不默认已完成）
        String documentStatus = str(rows.get(0).get(1));
        String gatedStatus = gateStatus(documentStatus);
        if (gatedStatus == null) {
            vo.getMessages().add(workOrderNo + "：金蝶单据状态 " + documentStatus
                    + " 不受支持（未审核或不识别），已跳过");
            return false;
        }
        Set<String> materialNumbers = new LinkedHashSet<>();
        Set<String> lots = new LinkedHashSet<>();
        BigDecimal planQty = BigDecimal.ZERO;
        for (List<Object> row : rows) {
            materialNumbers.add(str(row.get(3)));
            String lot = str(row.get(5));
            if (oConvertUtils.isNotEmpty(lot) && !"0".equals(lot)) {
                lots.add(lot);
            }
            planQty = planQty.add(toDecimal(row.get(6)));
        }
        if (materialNumbers.size() != 1) {
            vo.getMessages().add(workOrderNo + "：金蝶工单含多个物料，WMS 单物料模型不支持，已跳过");
            return false;
        }
        if (lots.size() > 1) {
            vo.getMessages().add(workOrderNo + "：金蝶工单含多个批次 " + lots + "，WMS 单批次模型不支持，已跳过");
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
        // 表列 kingdee_product_type 优先（2026-10-28 映射落库），YAML material-product-type 兜底
        String productType = materialResolver.resolveProductType(wmsMaterialCode);
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
        String lot = str(rows.get(0).get(5));
        String batchNo = (oConvertUtils.isEmpty(lot) || "0".equals(lot)) ? "KD-" + workOrderNo : lot;

        WmsWorkOrder existed = workOrderService.getByWorkOrderNo(workOrderNo);
        WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
        dto.setWorkOrderNo(workOrderNo);
        dto.setMaterialCode(wmsMaterialCode);
        dto.setProductType(productType);
        dto.setOrderType(resolveOrderType(rows.get(0), unknownOrderTypes));
        dto.setPlanQty(planQty);
        dto.setBatchNo(batchNo);
        dto.setWorkOrderStatus(gatedStatus);
        dto.setSource(WmsConstants.WORK_ORDER_SOURCE_ERP);
        dto.setErpDocumentStatus(documentStatus);
        dto.setMesOrderNo(workOrderNo);
        workOrderService.push(dto);
        if (existed == null) {
            vo.setImported(vo.getImported() + 1);
        } else {
            vo.setUpdated(vo.getUpdated() + 1);
        }
        return true;
    }

    /**
     * 查询字段串：配置了工单类型字段（外部依赖 E1）时追加到末尾，避免打乱既有下标。
     */
    private String resolveFieldKeys() {
        String orderTypeField = properties.getMasterData().getOrderTypeField();
        if (oConvertUtils.isEmpty(orderTypeField)) {
            return FIELD_KEYS;
        }
        return FIELD_KEYS + "," + orderTypeField.trim();
    }

    /**
     * 从行里解析工单类型。
     *
     * <p>字段未配置 → 一律 null（不臆测）；配置了但值不在映射表 → null 并告警一次，
     * 避免未定义的取值被当成合法类型落库。
     */
    private String resolveOrderType(List<Object> row, Set<String> unknownOrderTypes) {
        String orderTypeField = properties.getMasterData().getOrderTypeField();
        if (oConvertUtils.isEmpty(orderTypeField) || row.size() <= ORDER_TYPE_INDEX) {
            return null;
        }
        String raw = str(row.get(ORDER_TYPE_INDEX));
        if (oConvertUtils.isEmpty(raw)) {
            return null;
        }
        Map<String, String> valueMap = properties.getMasterData().getOrderTypeValueMap();
        String mapped = valueMap == null ? null : valueMap.get(raw);
        if (oConvertUtils.isEmpty(mapped)) {
            if (unknownOrderTypes.add(raw)) {
                log.warn("工单类型取值[{}]未在 order-type-value-map 中定义，按空值处理", raw);
            }
            return null;
        }
        return mapped;
    }

    /** 金蝶物料编码 → WMS 物料编码：表列 kingdee_code 优先，YAML 反向兜底，都没有回退原样。 */
    private String reverseMaterialCode(String kingdeeCode) {
        String resolved = materialResolver.reverseKingdeeCode(kingdeeCode);
        return oConvertUtils.isEmpty(resolved) ? kingdeeCode : resolved;
    }

    private void validateDate(String date) {
        if (oConvertUtils.isEmpty(date)) {
            return;
        }
        if (date.length() != 10) {
            throw new RuntimeException("日期格式必须为 yyyy-MM-dd");
        }
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new RuntimeException("日期格式必须为 yyyy-MM-dd");
        }
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
