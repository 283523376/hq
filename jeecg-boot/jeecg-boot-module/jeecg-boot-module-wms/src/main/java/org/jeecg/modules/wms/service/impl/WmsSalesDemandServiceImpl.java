package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsSalesDemandService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsKingdeeStockMappingResolver;
import org.jeecg.modules.wms.sync.WmsMaterialCodeResolver;
import org.jeecg.modules.wms.vo.WmsDemandResultVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 销售出库待办自动生成（金蝶发货通知单 → WMS 出库单）。
 *
 * <p>口径（用户 2026-10-28 确认）：已审核（C）+ 未出库数量 &gt; 0；同一行允许多次生成（分批发货），
 * 本次数量 = 未出库数量 − WMS 在途已申请量（在途 = 未完成/未关闭、source_bill_no 相同的销售出库单）。
 *
 * <p><b>仓库映射是预留接口</b>：金蝶 101 组织的仓库主数据尚未建立，
 * 映射未命中即跳过该行并记录原因（不猜、不退回其它仓库）。
 *
 * <p>不依赖 WMS 销售订单快照：客户与数量直接取发货通知单（源销售订单未必已同步）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsSalesDemandServiceImpl implements IWmsSalesDemandService {

    private static final String FORM_ID = "SAL_DELIVERYNOTICE";
    /** 只处理已审核的发货通知单（用户 2026-10-28 确认：等 C 再生成）。 */
    private static final String DOC_STATUS_AUDITED = "C";
    private static final String FIELD_KEYS = "FBillNo,FCustomerID.FNumber,FCustomerID.FName,FDeliveryDate,"
            + "FMaterialID.FNumber,FQty,FRemainOutQty,FLot.FNumber,FStockID.FNumber,FSrcBillNo";
    private static final List<String> CLOSED_STATUSES = List.of(
            WmsConstants.OUTBOUND_STATUS_COMPLETED, WmsConstants.OUTBOUND_STATUS_CLOSED);

    private final WmsKingdeeProperties properties;
    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeStockMappingResolver stockMappingResolver;
    private final WmsMaterialCodeResolver materialResolver;
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsOutboundDetailMapper outboundDetailMapper;
    private final IWmsOutboundService outboundService;

    /** 可选依赖：既有单测按旧构造器 new 服务，注入不到时为 null。 */
    @Autowired(required = false)
    private IWmsAuditLogService auditLogService;

    public WmsSalesDemandServiceImpl(WmsKingdeeProperties properties,
                                     WmsKingdeeFormClient formClient,
                                     WmsKingdeeStockMappingResolver stockMappingResolver,
                                     WmsMaterialCodeResolver materialResolver,
                                     WmsOutboundOrderMapper outboundMapper,
                                     WmsOutboundDetailMapper outboundDetailMapper,
                                     IWmsOutboundService outboundService) {
        this.properties = properties;
        this.formClient = formClient;
        this.stockMappingResolver = stockMappingResolver;
        this.materialResolver = materialResolver;
        this.outboundMapper = outboundMapper;
        this.outboundDetailMapper = outboundDetailMapper;
        this.outboundService = outboundService;
    }

    @Override
    public WmsDemandResultVO generatePendingSalesOutbounds() {
        WmsDemandResultVO vo = new WmsDemandResultVO();
        if (!properties.isEnabled()) {
            vo.setMessage("金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
            return vo;
        }
        String orgNumber = oConvertUtils.isEmpty(properties.getSalesDelivery().getOrgNumber())
                ? properties.getDefaultOrgNumber() : properties.getSalesDelivery().getOrgNumber();
        if (oConvertUtils.isEmpty(orgNumber)) {
            vo.setMessage("未配置销售出库待办的销售组织（sales-delivery.org-number / default-org-number），跳过");
            return vo;
        }
        String filter = String.format("FSaleOrgId.FNumber='%s' and FDocumentStatus='%s' and FRemainOutQty>0",
                orgNumber.replace("'", "''"), DOC_STATUS_AUDITED);
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, FIELD_KEYS, filter,
                Math.max(1, properties.getSalesDelivery().getBatchSize()));
        if (!result.isSuccess()) {
            vo.setMessage("金蝶发货通知单查询失败：" + result.getMessage());
            return vo;
        }

        // 按发货通知单分组（FBillNo,客户编码,客户名称,要货日期,源销售订单 | 明细行）
        Map<String, List<List<Object>>> byBill = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            String billNo = str(row, 0);
            if (billNo.isEmpty()) {
                continue;
            }
            byBill.computeIfAbsent(billNo, k -> new ArrayList<>()).add(row);
        }
        vo.setScanned(byBill.size());

        for (Map.Entry<String, List<List<Object>>> entry : byBill.entrySet()) {
            String billNo = entry.getKey();
            List<List<Object>> rows = entry.getValue();
            try {
                List<WmsOutboundDetailDTO> details = buildGapDetails(billNo, rows, vo);
                if (details.isEmpty()) {
                    continue;
                }
                List<Object> head = rows.get(0);
                WmsOutboundOrder created = outboundService.createSalesFromNotice(billNo,
                        str(head, 1), str(head, 2), parseDate(str(head, 3)), null, details);
                vo.getCreatedOrders().add(created.getOutboundNo());
                vo.setCreated(vo.getCreated() + 1);
                // 「错通知单生成了错出库单」是最典型的错数据链路，必须能按通知单号反查
                WmsAuditRecorder.record(auditLogService, new WmsAuditEvent()
                        .setBizType(WmsConstants.AUDIT_BIZ_OUTBOUND)
                        .setBizNo(created.getOutboundNo())
                        .setBizRef(billNo)
                        .setAction(WmsConstants.AUDIT_ACTION_CREATE)
                        .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_SYNC)
                        .setAfterValue(WmsAuditRecorder.snapshot(auditLogService, created,
                                "outboundNo", "outboundType", "status", "customerCode", "salesOrderNo"))
                        .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                        .setResult(WmsConstants.AUDIT_RESULT_SUCCESS));
            } catch (Exception e) {
                log.warn("发货通知单[{}]生成销售出库待办失败：{}", billNo, e.getMessage());
                skip(vo, billNo + "：" + e.getMessage());
            }
        }
        vo.setMessage(String.format("扫描发货通知单 %d，生成出库单 %d，跳过 %d",
                vo.getScanned(), vo.getCreated(), vo.getSkipped()));
        return vo;
    }

    /** 逐行算缺口（未出库 − 在途 − ERP 已关联）；仓库映射未命中的行跳过并记原因。 */
    private List<WmsOutboundDetailDTO> buildGapDetails(String billNo, List<List<Object>> rows,
                                                       WmsDemandResultVO vo) {
        // 在途量与 ERP 已关联量都是**物料级**汇总：同物料多行时必须逐行递减消耗，
        // 否则每一行都按全额扣一次，缺口被扣穿（真实缺口 70 会算成 40）
        Map<String, BigDecimal> inFlightLeft = new LinkedHashMap<>(inFlightQtyByMaterial(billNo));
        ErpLinkedSnapshot erp = erpLinkedOf(billNo);
        Map<String, BigDecimal> erpLinkedLeft = new LinkedHashMap<>(erp.byMaterial);
        // 有取消的关联单但数量读不出（手工单只填数量、实发为空）：当占用未知，宁可少生成也不重复开单
        Set<String> erpLinkedUnknown = erp.unknownQtyMaterials;
        List<WmsOutboundDetailDTO> details = new ArrayList<>();
        for (List<Object> row : rows) {
            String kingdeeMaterial = str(row, 4);
            String kingdeeStock = str(row, 8);
            String lot = str(row, 7);
            BigDecimal remain = decimal(row, 6);
            if (remain == null || remain.signum() <= 0) {
                continue;
            }
            String wmsWarehouse = stockMappingResolver.resolveWmsWarehouse(kingdeeStock);
            if (wmsWarehouse == null) {
                skip(vo, billNo + "：金蝶出货仓库[" + kingdeeStock + "]未配置 WMS 库区映射"
                        + "（sales-delivery.stock-map；金蝶仓库主数据尚未建立，属预期内跳过）");
                continue;
            }
            String wmsMaterialCode = materialResolver.reverseKingdeeCode(kingdeeMaterial);
            if (oConvertUtils.isEmpty(wmsMaterialCode)) {
                wmsMaterialCode = kingdeeMaterial;
            }
            if (erpLinkedUnknown.contains(kingdeeMaterial)) {
                skip(vo, billNo + "：ERP 已有关联出库单但数量未知（物料 " + kingdeeMaterial
                        + "），请先在金蝶补齐实发数量或删除该单");
                continue;
            }
            BigDecimal alreadyWms = inFlightLeft.getOrDefault(wmsMaterialCode, BigDecimal.ZERO);
            BigDecimal alreadyErp = erpLinkedLeft.getOrDefault(kingdeeMaterial, BigDecimal.ZERO);
            BigDecimal gap = remain.subtract(alreadyWms).subtract(alreadyErp);
            if (gap.signum() <= 0) {
                // 本行已被占满：占用量只消耗到本行的剩余量为上限，剩余部分留给后续行
                consume(inFlightLeft, wmsMaterialCode, remain);
                consume(erpLinkedLeft, kingdeeMaterial, remain);
                if (alreadyErp.signum() > 0 && alreadyWms.signum() <= 0) {
                    skip(vo, billNo + "：ERP 已有关联出库单（物料 " + kingdeeMaterial + " 已占用 "
                            + strip(alreadyErp) + "），本通知单无需再生成待办");
                }
                continue;
            }
            // 本行消耗掉的占用量：先扣在途、再扣 ERP 已关联
            BigDecimal usedWms = alreadyWms.min(remain);
            BigDecimal usedErp = alreadyErp.min(remain.subtract(usedWms));
            consume(inFlightLeft, wmsMaterialCode, usedWms.add(gap));
            consume(erpLinkedLeft, kingdeeMaterial, usedErp);
            WmsOutboundDetailDTO detail = new WmsOutboundDetailDTO();
            detail.setMaterialCode(wmsMaterialCode);
            detail.setBatchNo(oConvertUtils.isEmpty(lot) ? null : lot);
            detail.setRequiredQty(gap);
            // 明细来源标识：批次号 + 金蝶物料 + 仓库，便于回溯（分录内码未在可查询字段中）
            detail.setSourceEntryId(billNo + "#" + kingdeeMaterial + "@" + kingdeeStock);
            details.add(detail);
        }
        return details;
    }

    /** 金蝶侧已关联出库量的快照：按物料汇总的数量 + 有单但数量读不出来的物料。 */
    private static final class ErpLinkedSnapshot {
        private final Map<String, BigDecimal> byMaterial = new LinkedHashMap<>();
        private final Set<String> unknownQtyMaterials = new LinkedHashSet<>();
    }

    /**
     * 查该发货通知单在**金蝶侧**已关联的销售出库量（未审核 A/B/D 等，已审核 C 不掺和）。
     *
     * <p>为什么必须算进来：通知单的 {@code FRemainOutQty} 只在出库单**审核**时核销，
     * 草稿/已提交未审期间不减（2026-10-30 真账套实测）。只扣 WMS 自己的在途单，
     * 会把「金蝶已开、WMS 还没开」的量算成缺口 → 每轮生成都重复开单。
     *
     * <p>统计**非已审核**（≠C）：C 已审核时 remain 已经核销过，再扣一次会少生成；
     * 提交被业务闸门拦下时金蝶会停在 A/B/D 等中间态，这些都要算占用才不重复开单。
     *
     * <p>查询失败返回空快照、不阻断生成（ERP 抖动不该让整批待办都生成不了）；
     * 真正的兜底在回写侧——开单前会查同通知单是否已有未审核出库单并拦住。
     */
    private ErpLinkedSnapshot erpLinkedOf(String noticeNo) {
        ErpLinkedSnapshot snapshot = new ErpLinkedSnapshot();
        String filter = "FSrcBillNo='" + noticeNo.trim().replace("'", "''")
                + "' and FDocumentStatus <> 'C'";
        try {
            for (List<Object> row : queryAll("SAL_OUTSTOCK",
                    "FSrcBillNo,FMaterialID.FNumber,FRealQty,FDocumentStatus", filter)) {
                if (row == null || row.size() < 3) {
                    continue;
                }
                String material = str(row, 1).trim();
                if (material.isEmpty()) {
                    continue;
                }
                BigDecimal qty = decimal(row, 2);
                if (qty == null || qty.signum() <= 0) {
                    // 手工单只填数量、实发为空：当占用未知，宁可少生成也不重复开单
                    snapshot.unknownQtyMaterials.add(material);
                } else {
                    snapshot.byMaterial.merge(material, qty, BigDecimal::add);
                }
            }
        } catch (Exception e) {
            log.warn("发货通知单[{}]关联出库单查询失败，本次不扣减 ERP 已关联量：{}", noticeNo, e.getMessage());
        }
        return snapshot;
    }

    /** 按页拉全量，避免超过单页上限的部分被静默截断。 */
    private List<List<Object>> queryAll(String formId, String fields, String filter) {
        List<List<Object>> all = new ArrayList<>();
        int pageSize = 2000;
        int start = 0;
        while (start < 20000) {
            WmsKingdeeResult result = formClient.executeBillQuery(formId, fields, filter, pageSize, start,
                    "FBillNo ASC");
            if (result == null || !result.isSuccess()) {
                throw new IllegalStateException(result == null ? "查询无响应" : result.getMessage());
            }
            List<List<Object>> rows = result.getRows();
            all.addAll(rows);
            if (rows.size() < pageSize) {
                break;
            }
            start += pageSize;
        }
        return all;
    }


    /** 从物料级占用池里扣掉本行消耗的量，最少扣到 0（不能让占用负数影响后续行）。 */
    private void consume(Map<String, BigDecimal> pool, String key, BigDecimal used) {
        if (used == null || used.signum() <= 0) {
            return;
        }
        BigDecimal left = pool.getOrDefault(key, BigDecimal.ZERO).subtract(used);
        pool.put(key, left.signum() > 0 ? left : BigDecimal.ZERO);
    }

    private String strip(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 该发货通知单在途销售出库单（未完成/未关闭）按物料汇总的已申请量。 */
    private Map<String, BigDecimal> inFlightQtyByMaterial(String noticeNo) {
        List<WmsOutboundOrder> openOrders = outboundMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getSourceBillNo, noticeNo)
                .eq(WmsOutboundOrder::getOutboundType, WmsConstants.OUTBOUND_TYPE_SALES)
                .notIn(WmsOutboundOrder::getStatus, CLOSED_STATUSES));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (WmsOutboundOrder order : openOrders) {
            List<WmsOutboundDetail> lines = outboundDetailMapper.selectList(
                    new LambdaQueryWrapper<WmsOutboundDetail>()
                            .eq(WmsOutboundDetail::getOutboundId, order.getId()));
            for (WmsOutboundDetail line : lines) {
                BigDecimal qty = line.getActualQty() != null && line.getActualQty().signum() > 0
                        ? line.getActualQty() : line.getRequiredQty();
                if (qty == null || oConvertUtils.isEmpty(line.getMaterialCode())) {
                    continue;
                }
                result.merge(line.getMaterialCode(), qty, BigDecimal::add);
            }
        }
        return result;
    }

    private void skip(WmsDemandResultVO vo, String reason) {
        vo.setSkipped(vo.getSkipped() + 1);
        if (vo.getSkippedReasons().size() < 20) {
            vo.getSkippedReasons().add(reason);
        }
    }

    private String str(List<Object> row, int index) {
        return row != null && row.size() > index && row.get(index) != null
                ? String.valueOf(row.get(index)).trim() : "";
    }

    private BigDecimal decimal(List<Object> row, int index) {
        String raw = str(row, index);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Date parseDate(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            String datePart = raw.length() > 10 ? raw.substring(0, 10) : raw;
            return new SimpleDateFormat("yyyy-MM-dd").parse(datePart);
        } catch (Exception e) {
            return null;
        }
    }
}
