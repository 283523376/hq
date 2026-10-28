package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsPickDemandService;
import org.jeecg.modules.wms.sync.IWmsKingdeePpbomReader;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;
import org.jeecg.modules.wms.vo.WmsPickDemandResultVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 领料待办自动生成（出库单反向生成）。
 *
 * <p>口径（2026-10-28 自动化优先 P1-3）：领料单不再由用户手工新建——按已同步工单的
 * 金蝶用料清单（PRD_PPBOM）自动生成待分配出库单，用户只做扫码执行与确认。
 *
 * <p>缺口计算：{@code 缺口 = 用料清单需求(已扣金蝶已领) - WMS 在途领料单已申请量}；
 * 在途 = 状态不属于（已完成/已关闭）的领料出库单，避免重复生成。
 *
 * <p>失败不阻断：单张工单用料清单读不到（金蝶不可用/无 PPBOM）只跳过并记原因，不影响其它工单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsPickDemandServiceImpl implements IWmsPickDemandService {

    /** 视为「已结束、不再占用需求」的出库单状态。 */
    private static final List<String> CLOSED_STATUSES = List.of(
            WmsConstants.OUTBOUND_STATUS_COMPLETED, WmsConstants.OUTBOUND_STATUS_CLOSED);

    private final WmsKingdeeProperties properties;
    private final IWmsKingdeePpbomReader ppbomReader;
    private final WmsWorkOrderMapper workOrderMapper;
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsOutboundDetailMapper outboundDetailMapper;
    private final IWmsOutboundService outboundService;

    public WmsPickDemandServiceImpl(WmsKingdeeProperties properties,
                                    IWmsKingdeePpbomReader ppbomReader,
                                    WmsWorkOrderMapper workOrderMapper,
                                    WmsOutboundOrderMapper outboundMapper,
                                    WmsOutboundDetailMapper outboundDetailMapper,
                                    IWmsOutboundService outboundService) {
        this.properties = properties;
        this.ppbomReader = ppbomReader;
        this.workOrderMapper = workOrderMapper;
        this.outboundMapper = outboundMapper;
        this.outboundDetailMapper = outboundDetailMapper;
        this.outboundService = outboundService;
    }

    @Override
    public WmsPickDemandResultVO generatePendingPicks() {
        WmsPickDemandResultVO vo = new WmsPickDemandResultVO();
        if (!properties.isEnabled()) {
            vo.setMessage("金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
            return vo;
        }
        List<WmsWorkOrder> workOrders = workOrderMapper.selectList(new LambdaQueryWrapper<WmsWorkOrder>()
                .in(WmsWorkOrder::getWorkOrderStatus,
                        WmsConstants.WORK_ORDER_STATUS_RELEASED, WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS)
                .last("LIMIT 200"));
        vo.setScanned(workOrders.size());

        for (WmsWorkOrder workOrder : workOrders) {
            try {
                List<WmsOutboundDetailDTO> details = buildGapDetails(workOrder);
                if (details.isEmpty()) {
                    skip(vo, workOrder.getWorkOrderNo(), "无领料缺口（已全部申请）");
                    continue;
                }
                WmsOutboundPickCreateDTO dto = new WmsOutboundPickCreateDTO();
                dto.setWorkOrderNo(workOrder.getWorkOrderNo());
                dto.setRequirementSource(WmsConstants.REQUIREMENT_SOURCE_PPBOM);
                dto.setRemark("系统按金蝶用料清单自动生成");
                dto.setDetails(details);
                WmsOutboundOrder created = outboundService.createPick(dto);
                vo.getCreatedOrders().add(created.getOutboundNo());
                vo.setCreated(vo.getCreated() + 1);
                log.info("领料待办自动生成：工单={}，出库单={}，明细行={}",
                        workOrder.getWorkOrderNo(), created.getOutboundNo(), details.size());
            } catch (Exception e) {
                log.warn("工单[{}]领料待办生成失败：{}", workOrder.getWorkOrderNo(), e.getMessage());
                skip(vo, workOrder.getWorkOrderNo(), e.getMessage());
            }
        }
        vo.setMessage(String.format("扫描工单 %d，生成领料单 %d，跳过 %d",
                vo.getScanned(), vo.getCreated(), vo.getSkipped()));
        return vo;
    }

    /** 用工料清单需求减去在途领料单已申请量，得到本次要生成的明细。 */
    private List<WmsOutboundDetailDTO> buildGapDetails(WmsWorkOrder workOrder) {
        List<WmsOutboundRequirementVO> requirements = ppbomReader.read(workOrder.getWorkOrderNo());
        if (requirements == null || requirements.isEmpty()) {
            throw new RuntimeException("金蝶用料清单无需求或读取失败");
        }
        Map<String, BigDecimal> inFlight = inFlightQtyByMaterial(workOrder.getWorkOrderNo());
        List<WmsOutboundDetailDTO> details = new ArrayList<>();
        for (WmsOutboundRequirementVO requirement : requirements) {
            if (requirement.getRequiredQty() == null || requirement.getRequiredQty().signum() <= 0) {
                continue;
            }
            BigDecimal already = inFlight.getOrDefault(requirement.getMaterialCode(), BigDecimal.ZERO);
            BigDecimal gap = requirement.getRequiredQty().subtract(already);
            if (gap.signum() <= 0) {
                continue;
            }
            WmsOutboundDetailDTO detail = new WmsOutboundDetailDTO();
            detail.setMaterialCode(requirement.getMaterialCode());
            detail.setBatchNo(requirement.getBatchNo());
            detail.setRequiredQty(gap);
            detail.setUnit(requirement.getUnit());
            detail.setSourceEntryId(requirement.getSourceEntryId());
            details.add(detail);
        }
        return details;
    }

    /** 该工单在途领料单（未完成/未关闭）按物料汇总的已申请量。 */
    private Map<String, BigDecimal> inFlightQtyByMaterial(String workOrderNo) {
        List<WmsOutboundOrder> openOrders = outboundMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getWorkOrderNo, workOrderNo)
                .eq(WmsOutboundOrder::getOutboundType, WmsConstants.OUTBOUND_TYPE_PICK)
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

    private void skip(WmsPickDemandResultVO vo, String workOrderNo, String reason) {
        vo.setSkipped(vo.getSkipped() + 1);
        if (vo.getSkippedReasons().size() < 20) {
            vo.getSkippedReasons().add(workOrderNo + "：" + reason);
        }
    }
}
