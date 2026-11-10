package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrderDetail;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSalesOrderDetailMapper;
import org.jeecg.modules.wms.mapper.WmsSalesOrderMapper;
import org.jeecg.modules.wms.service.IWmsSalesOrderService;
import org.jeecg.modules.wms.sync.IWmsKingdeeSaleOrderReader;
import org.jeecg.modules.wms.sync.WmsKingdeeSaleOrder;
import org.jeecg.modules.wms.sync.WmsKingdeeSaleOrderLine;
import org.jeecg.modules.wms.sync.WmsKingdeeSaleOrderQueryResult;
import org.jeecg.modules.wms.vo.WmsSalesOrderVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 销售订单快照实现（CK-002 方向反转）。
 *
 * <p>关键口径：
 * <ul>
 *   <li>只有金蝶 `FDocumentStatus=C` 可出库；</li>
 *   <li>快照按 `FBillNo` upsert，分录「先删后插」保证幂等；</li>
 *   <li>`shipped_qty` 由 WMS 已确认出库的销售出库单汇总（按物料），剩余量 = 订单量 - 已出库量。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsSalesOrderServiceImpl extends ServiceImpl<WmsSalesOrderMapper, WmsSalesOrder>
        implements IWmsSalesOrderService {

    private final WmsSalesOrderDetailMapper detailMapper;

    /** 可选依赖：既有单测按旧构造器 new 服务，注入不到时为 null。 */
    @Autowired(required = false)
    private IWmsAuditLogService auditLogService;
    private final WmsMaterialMapper materialMapper;
    private final WmsOutboundOrderMapper outboundOrderMapper;
    private final WmsOutboundDetailMapper outboundDetailMapper;
    private final IWmsKingdeeSaleOrderReader reader;
    private final org.jeecg.modules.wms.sync.WmsKingdeeProperties kingdeeProperties;
    private final ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser;

    public WmsSalesOrderServiceImpl(WmsSalesOrderDetailMapper detailMapper,
                                    WmsMaterialMapper materialMapper,
                                    WmsOutboundOrderMapper outboundOrderMapper,
                                    WmsOutboundDetailMapper outboundDetailMapper,
                                    IWmsKingdeeSaleOrderReader reader,
                                    org.jeecg.modules.wms.sync.WmsKingdeeProperties kingdeeProperties,
                                    ISysBaseAPI sysBaseAPI) {
        this.detailMapper = detailMapper;
        this.materialMapper = materialMapper;
        this.outboundOrderMapper = outboundOrderMapper;
        this.outboundDetailMapper = outboundDetailMapper;
        this.reader = reader;
        this.kingdeeProperties = kingdeeProperties;
        this.sysBaseAPI = sysBaseAPI;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesOrder syncByBillNo(String billNo) {
        WmsKingdeeSaleOrderQueryResult result = reader.queryByBillNo(billNo);
        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }
        if (result.getOrders().isEmpty()) {
            throw new RuntimeException("金蝶未找到销售订单：" + billNo);
        }
        return persist(result.getOrders().get(0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncByWindow(Date begin, Date end) {
        WmsKingdeeSaleOrderQueryResult result = reader.queryApproved(begin, end, WmsConstants.SALES_ORDER_SYNC_LIMIT);
        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }
        int saved = 0;
        for (WmsKingdeeSaleOrder order : result.getOrders()) {
            try {
                persist(order);
                saved++;
            } catch (Exception e) {
                log.warn("销售订单[{}]快照写入失败：{}", order.getSalesOrderNo(), e.getMessage());
                markFetchFailure(order.getSalesOrderNo(), e.getMessage());
            }
        }
        return saved;
    }

    @Override
    public WmsSalesOrderVO preview(String billNo) {
        if (oConvertUtils.isEmpty(billNo)) {
            throw new RuntimeException("销售订单号不能为空");
        }
        WmsSalesOrder order = findByNo(billNo);
        if (order == null) {
            order = syncByBillNo(billNo);
        }
        WmsSalesOrderVO vo = new WmsSalesOrderVO();
        vo.setOrder(order);
        List<WmsSalesOrderVO.Line> lines = new ArrayList<>();
        for (WmsSalesOrderDetail detail : listDetails(order.getId())) {
            lines.add(WmsSalesOrderVO.Line.of(detail));
        }
        vo.setLines(lines);
        return vo;
    }

    @Override
    public List<WmsSalesOrderDetail> requireApprovedDetails(String billNo) {
        WmsSalesOrder order = findByNo(billNo);
        if (order == null) {
            // 快照没有：实时拉一次，仍无则报错
            order = syncByBillNo(billNo);
        }
        String status = order.getErpDocumentStatus();
        if (!WmsConstants.SALES_ORDER_STATUS_APPROVED.equals(status)) {
            throw new RuntimeException("销售订单[" + billNo + "]金蝶状态为 " + (status == null ? "未知" : status)
                    + "，只有已审核(C)的订单可以出库");
        }
        List<WmsSalesOrderDetail> details = listDetails(order.getId());
        if (details.isEmpty()) {
            throw new RuntimeException("销售订单[" + billNo + "]没有分录，无法出库");
        }
        return details;
    }

    @Override
    public void refreshShippedQty(String billNo) {
        WmsSalesOrder order = findByNo(billNo);
        if (order == null) {
            return;
        }
        Map<String, BigDecimal> shipped = shippedByMaterial(billNo);
        for (WmsSalesOrderDetail detail : listDetails(order.getId())) {
            BigDecimal qty = shipped.getOrDefault(detail.getMaterialCode(), BigDecimal.ZERO);
            WmsSalesOrderDetail patch = new WmsSalesOrderDetail();
            patch.setId(detail.getId());
            patch.setShippedQty(qty);
            patch.setUpdateTime(new Date());
            detailMapper.updateById(patch);
        }
    }

    // ==================== 内部 ====================

    /** upsert 表头 + 分录（明细先逻辑删后重建），并刷新已出库数量。 */
    private WmsSalesOrder persist(WmsKingdeeSaleOrder source) {
        WmsSalesOrder existing = findByNo(source.getSalesOrderNo());
        // 快照必须在覆盖之前取：existing 随后会被就地改写
        String beforeJson = WmsAuditRecorder.snapshot(auditLogService, existing,
                "salesOrderNo", "erpDocumentStatus", "customerCode", "deliveryDate");
        WmsSalesOrder order = existing == null ? new WmsSalesOrder() : existing;
        order.setSalesOrderNo(source.getSalesOrderNo())
                .setCustomerCode(source.getCustomerCode())
                .setCustomerName(source.getCustomerName())
                .setSaleOrgNumber(source.getSaleOrgNumber())
                .setOrderDate(source.getOrderDate())
                .setErpDocumentStatus(source.getDocumentStatus())
                .setRemark(truncate(source.getRemark()))
                .setDeliveryDate(earliestDelivery(source.getLines()))
                .setFetchStatus(WmsConstants.SALES_FETCH_SUCCESS)
                .setFetchError(null)
                .setFetchFailCount(0)
                .setFetchTime(new Date());
        if (existing == null) {
            save(order);
        } else {
            updateById(order);
            // 分录整体替换：旧行逻辑删除（@TableLogic），新行重建，保证幂等
            detailMapper.delete(new LambdaQueryWrapper<WmsSalesOrderDetail>()
                    .eq(WmsSalesOrderDetail::getSalesOrderId, order.getId()));
        }

        Map<String, String> materialIdByCode = loadMaterialIds(source.getLines());
        Map<String, BigDecimal> shipped = shippedByMaterial(source.getSalesOrderNo());
        int lineNo = 1;
        for (WmsKingdeeSaleOrderLine line : source.getLines()) {
            WmsSalesOrderDetail detail = new WmsSalesOrderDetail()
                    .setSalesOrderId(order.getId())
                    .setLineNo(lineNo++)
                    .setMaterialId(materialIdByCode.get(line.getMaterialCode()))
                    .setMaterialCode(line.getMaterialCode())
                    .setMaterialName(line.getMaterialName())
                    .setUnit(line.getUnit())
                    .setOrderQty(line.getOrderQty() == null ? BigDecimal.ZERO : line.getOrderQty())
                    .setDeliveryDate(line.getDeliveryDate())
                    .setShippedQty(shipped.getOrDefault(line.getMaterialCode(), BigDecimal.ZERO));
            detailMapper.insert(detail);
        }
        // 全量窗口同步会反复扫到没变的单：无变化不写审计，否则订单一多全是噪音（评审 M3）
        if (existing != null) {
            String afterJson = WmsAuditRecorder.snapshot(auditLogService, order,
                    "salesOrderNo", "erpDocumentStatus", "customerCode", "deliveryDate");
            if (java.util.Objects.equals(beforeJson, afterJson)) {
                return order;
            }
            WmsAuditRecorder.record(auditLogService, new WmsAuditEvent()
                    .setBizType(WmsConstants.AUDIT_BIZ_SALES_ORDER)
                    .setBizNo(source.getSalesOrderNo())
                    .setAction(WmsConstants.AUDIT_ACTION_UPDATE)
                    .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_SYNC)
                    .setBeforeValue(beforeJson)
                    .setAfterValue(afterJson)
                    .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                    .setResult(WmsConstants.AUDIT_RESULT_SUCCESS));
            return order;
        }
        WmsAuditRecorder.record(auditLogService, new WmsAuditEvent()
                .setBizType(WmsConstants.AUDIT_BIZ_SALES_ORDER)
                .setBizNo(source.getSalesOrderNo())
                .setAction(WmsConstants.AUDIT_ACTION_SYNC_IN)
                .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_SYNC)
                .setBeforeValue(beforeJson)
                .setAfterValue(WmsAuditRecorder.snapshot(auditLogService, order,
                        "salesOrderNo", "erpDocumentStatus", "customerCode", "deliveryDate"))
                .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                .setResult(WmsConstants.AUDIT_RESULT_SUCCESS));
        return order;
    }

    private void markFetchFailure(String billNo, String message) {
        WmsSalesOrder existing = findByNo(billNo);
        if (existing == null) {
            return;
        }
        int fail = (existing.getFetchFailCount() == null ? 0 : existing.getFetchFailCount()) + 1;
        WmsSalesOrder patch = new WmsSalesOrder();
        patch.setId(existing.getId());
        patch.setFetchStatus(WmsConstants.SALES_FETCH_FAILED);
        patch.setFetchError(truncate(message));
        patch.setFetchFailCount(fail);
        patch.setFetchTime(new Date());
        updateById(patch);
        if (fail == 3) {
            sendAlert(WmsConstants.SALES_ORDER_SYNC_ALERT_TITLE,
                    "销售订单[" + billNo + "]连续 3 次拉取失败：" + message);
        }
    }

    private WmsSalesOrder findByNo(String billNo) {
        if (oConvertUtils.isEmpty(billNo)) {
            return null;
        }
        List<WmsSalesOrder> rows = list(new LambdaQueryWrapper<WmsSalesOrder>()
                .eq(WmsSalesOrder::getSalesOrderNo, billNo.trim())
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<WmsSalesOrderDetail> listDetails(String salesOrderId) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsSalesOrderDetail>()
                .eq(WmsSalesOrderDetail::getSalesOrderId, salesOrderId)
                .orderByAsc(WmsSalesOrderDetail::getLineNo));
    }

    /** 金蝶物料编码 → WMS 物料 ID（未建档返回 null，出库时会被拒绝并提示）。 */
    private Map<String, String> loadMaterialIds(List<WmsKingdeeSaleOrderLine> lines) {
        // 反向物料映射：WMS 编码 → 金蝶编码（如 FG001 → WA.2205.2200.03000550.B003）
        Map<String, String> wmsCodeByKingdee = new HashMap<>();
        kingdeeProperties.getMaterialMap().forEach((wms, kingdee) -> wmsCodeByKingdee.put(kingdee, wms));

        Map<String, String> map = new HashMap<>();
        for (WmsKingdeeSaleOrderLine line : lines) {
            if (oConvertUtils.isEmpty(line.getMaterialCode()) || map.containsKey(line.getMaterialCode())) {
                continue;
            }
            String wmsCode = wmsCodeByKingdee.getOrDefault(line.getMaterialCode(), line.getMaterialCode());
            List<WmsMaterial> rows = materialMapper.selectList(new LambdaQueryWrapper<WmsMaterial>()
                    .eq(WmsMaterial::getMaterialCode, wmsCode)
                    .last("LIMIT 1"));
            map.put(line.getMaterialCode(), rows.isEmpty() ? null : rows.get(0).getId());
        }
        return map;
    }

    /** 已确认出库的销售出库单按物料汇总实发数量。 */
    private Map<String, BigDecimal> shippedByMaterial(String salesOrderNo) {
        Map<String, BigDecimal> shipped = new LinkedHashMap<>();
        if (oConvertUtils.isEmpty(salesOrderNo)) {
            return shipped;
        }
        List<WmsOutboundOrder> orders = outboundOrderMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundType, WmsConstants.OUTBOUND_TYPE_SALES)
                .eq(WmsOutboundOrder::getSalesOrderNo, salesOrderNo)
                .eq(WmsOutboundOrder::getStatus, WmsConstants.OUTBOUND_STATUS_COMPLETED));
        if (orders.isEmpty()) {
            return shipped;
        }
        List<String> ids = new ArrayList<>();
        orders.forEach(order -> ids.add(order.getId()));
        List<WmsOutboundDetail> details = outboundDetailMapper.selectList(
                new LambdaQueryWrapper<WmsOutboundDetail>().in(WmsOutboundDetail::getOutboundId, ids));
        for (WmsOutboundDetail detail : details) {
            BigDecimal actual = detail.getActualQty() == null ? BigDecimal.ZERO : detail.getActualQty();
            shipped.merge(detail.getMaterialCode(), actual, BigDecimal::add);
        }
        return shipped;
    }

    private Date earliestDelivery(List<WmsKingdeeSaleOrderLine> lines) {
        Date earliest = null;
        for (WmsKingdeeSaleOrderLine line : lines) {
            if (line.getDeliveryDate() == null) {
                continue;
            }
            if (earliest == null || line.getDeliveryDate().before(earliest)) {
                earliest = line.getDeliveryDate();
            }
        }
        return earliest;
    }

    private void sendAlert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    oConvertUtils.isEmpty(alertToUser) ? WmsConstants.ALERT_TO_USER : alertToUser, title, content));
        } catch (Exception e) {
            log.error("销售订单告警投递失败：{}", e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }
}
