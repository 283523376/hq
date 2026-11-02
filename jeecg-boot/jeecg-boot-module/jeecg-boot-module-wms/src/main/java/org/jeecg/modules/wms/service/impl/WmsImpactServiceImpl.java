package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsImpactService;
import org.jeecg.modules.wms.vo.WmsImpactVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @Description: 影响面分析（XT-002）。
 *
 * <p>只读服务：全部基于当前库实时查询，不写、不缓存、不存快照。
 * 查不到的必须落进 {@code gaps}——用户拿它逐条纠正错误，静默的空列表比报错更危险。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Service
public class WmsImpactServiceImpl implements IWmsImpactService {

    /** 库存行最多取这么多条：影响面是给人看的清单，不是全量导出。 */
    private static final int INVENTORY_PAGE_SIZE = 200;

    /** 时间线最多取这么多条。 */
    private static final int TIMELINE_LIMIT = 500;

    private final WmsWorkOrderMapper workOrderMapper;
    private final WmsProductionInboundMapper inboundMapper;
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final WmsAuditLogMapper auditLogMapper;

    public WmsImpactServiceImpl(WmsWorkOrderMapper workOrderMapper,
                                WmsProductionInboundMapper inboundMapper,
                                WmsOutboundOrderMapper outboundMapper,
                                WmsOutboundDetailMapper detailMapper,
                                WmsInventoryMapper inventoryMapper,
                                WmsAuditLogMapper auditLogMapper) {
        this.workOrderMapper = workOrderMapper;
        this.inboundMapper = inboundMapper;
        this.outboundMapper = outboundMapper;
        this.detailMapper = detailMapper;
        this.inventoryMapper = inventoryMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public WmsImpactVO analyzeByWorkOrderNo(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo) || workOrderNo.trim().isEmpty()) {
            throw new RuntimeException("工单号不能为空");
        }
        String no = workOrderNo.trim();
        WmsImpactVO vo = new WmsImpactVO();

        vo.setWorkOrder(workOrderMapper.selectOne(new LambdaQueryWrapper<WmsWorkOrder>()
                .eq(WmsWorkOrder::getWorkOrderNo, no).last("LIMIT 1")));
        if (vo.getWorkOrder() == null) {
            vo.getGaps().add("WMS 中没有这张工单（可能从未同步，或已被删除）；以下仅审计日志可见");
        }

        List<WmsProductionInbound> inbounds = safeList(inboundMapper.selectList(
                new LambdaQueryWrapper<WmsProductionInbound>()
                        .eq(WmsProductionInbound::getWorkOrderNo, no)));
        vo.setProductionInbounds(inbounds);

        List<WmsOutboundOrder> outbounds = safeList(outboundMapper.selectList(
                new LambdaQueryWrapper<WmsOutboundOrder>()
                        .eq(WmsOutboundOrder::getWorkOrderNo, no)));
        for (WmsOutboundOrder order : outbounds) {
            List<WmsOutboundDetail> details = safeList(detailMapper.selectList(
                    new LambdaQueryWrapper<WmsOutboundDetail>()
                            .eq(WmsOutboundDetail::getOutboundId, order.getId())));
            vo.getOutbounds().add(new WmsImpactVO.Outbound().setOrder(order).setDetails(details));
        }

        if (inbounds.isEmpty() && outbounds.isEmpty()) {
            vo.getGaps().add("未查到该工单在 WMS 产生的入库单/出库单（已被删除或从未生成），仅审计日志可见");
        }

        fillInventoryRows(vo, inbounds);
        fillErpBills(vo, inbounds, outbounds);
        fillTimeline(vo, no);
        return vo;
    }

    /** 库存行按「物料+批次」反查当前库；库存行无法精确归属到某张单据，这是已接受的近似。 */
    private void fillInventoryRows(WmsImpactVO vo, List<WmsProductionInbound> inbounds) {
        Set<String> pairs = new LinkedHashSet<>();
        for (WmsProductionInbound inbound : inbounds) {
            if (oConvertUtils.isNotEmpty(inbound.getMaterialCode())) {
                pairs.add(inbound.getMaterialCode() + "\u0000" + nvl(inbound.getBatchNo()));
            }
        }
        for (WmsImpactVO.Outbound outbound : vo.getOutbounds()) {
            for (WmsOutboundDetail detail : outbound.getDetails()) {
                if (oConvertUtils.isNotEmpty(detail.getMaterialCode())) {
                    pairs.add(detail.getMaterialCode() + "\u0000" + nvl(detail.getBatchNo()));
                }
            }
        }
        // 台账查询的 materialKeyword 是 LIKE %..%，M001 会连带 M0012（评审 M5）。
        // 影响面是「这张单碰过什么」的证据清单，宁可少不可错：取回后按物料+批次精确过滤。
        boolean queriedAny = false;
        for (String pair : pairs) {
            String[] parts = pair.split("\u0000", -1);
            String materialCode = parts[0];
            String batchNo = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
            WmsInventoryLedgerQueryDTO query = new WmsInventoryLedgerQueryDTO();
            query.setMaterialKeyword(materialCode);
            query.setBatchNo(batchNo);
            query.setOnlyPositive(Boolean.FALSE);
            query.setPageNo(1);
            query.setPageSize(INVENTORY_PAGE_SIZE);
            try {
                IPage<WmsInventoryLedgerVO> page = inventoryMapper.selectLedgerPage(
                        new Page<>(1, INVENTORY_PAGE_SIZE), query);
                queriedAny = true;
                if (page != null && page.getRecords() != null) {
                    for (WmsInventoryLedgerVO row : page.getRecords()) {
                        if (!materialCode.equals(row.getMaterialCode())) {
                            continue;
                        }
                        if (batchNo != null && !batchNo.equals(row.getBatchNo())) {
                            continue;
                        }
                        if (!vo.getInventoryRows().contains(row)) {
                            vo.getInventoryRows().add(row);
                        }
                    }
                    if (page.getTotal() > page.getRecords().size()) {
                        vo.getGaps().add("物料 " + materialCode + " 的库存行超过 "
                                + INVENTORY_PAGE_SIZE + " 条，仅显示前 " + page.getRecords().size() + " 条");
                    }
                }
            } catch (Exception e) {
                log.warn("影响面查询库存行失败：material={}, batch={}", materialCode, batchNo, e);
                vo.getGaps().add("库存行查询失败（物料 " + materialCode + "）：" + e.getMessage());
            }
        }
        // 有入库/出库却查不到任何当前库存行：如实说明，不静默空白
        if (queriedAny && vo.getInventoryRows().isEmpty()) {
            vo.getGaps().add("未查到当前库存行（可能已全部出库、被调整，或库存行已删除）；历史动作仅审计日志可见");
        }
    }

    private void fillErpBills(WmsImpactVO vo, List<WmsProductionInbound> inbounds,
                              List<WmsOutboundOrder> outbounds) {
        for (WmsProductionInbound inbound : inbounds) {
            if (oConvertUtils.isNotEmpty(inbound.getErpBillNo())) {
                vo.getErpBills().add(new WmsImpactVO.ErpBill()
                        .setBillNo(inbound.getErpBillNo())
                        .setType("生产入库")
                        .setTime(inbound.getCreateTime())
                        .setSourceNo(inbound.getInboundNo()));
            }
        }
        for (WmsImpactVO.Outbound outbound : vo.getOutbounds()) {
            WmsOutboundOrder order = outbound.getOrder();
            if (oConvertUtils.isNotEmpty(order.getErpBillNo())) {
                vo.getErpBills().add(new WmsImpactVO.ErpBill()
                        .setBillNo(order.getErpBillNo())
                        .setType(typeLabel(order.getOutboundType()))
                        .setTime(order.getCreateTime())
                        .setSourceNo(order.getOutboundNo()));
            }
        }
    }

    /** 审计时间线：工单号既可能是 biz_no（工单本身），也可能是 biz_ref（由它产生的单据）。 */
    private void fillTimeline(WmsImpactVO vo, String workOrderNo) {
        try {
            List<WmsAuditLog> logs = safeList(auditLogMapper.selectList(new LambdaQueryWrapper<WmsAuditLog>()
                    .and(w -> w.eq(WmsAuditLog::getBizNo, workOrderNo)
                            .or().eq(WmsAuditLog::getBizRef, workOrderNo))
                    .orderByAsc(WmsAuditLog::getCreateTime)
                    .last("LIMIT " + TIMELINE_LIMIT)));
            vo.setTimeline(logs);
        } catch (Exception e) {
            log.warn("影响面查询审计时间线失败：workOrderNo={}", workOrderNo, e);
            vo.getGaps().add("审计时间线查询失败：" + e.getMessage());
        }
    }

    private static String typeLabel(String outboundType) {
        if (WmsConstants.OUTBOUND_TYPE_PICK.equals(outboundType)) {
            return "生产领料";
        }
        if (WmsConstants.OUTBOUND_TYPE_SALES.equals(outboundType)) {
            return "销售出库";
        }
        return "出库";
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
