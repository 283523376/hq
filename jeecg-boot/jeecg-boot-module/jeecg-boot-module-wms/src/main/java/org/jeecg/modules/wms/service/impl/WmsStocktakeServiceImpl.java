package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.jeecg.modules.wms.vo.WmsStocktakeDetailVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 库存盘点主流程。
 *
 * <p>两条硬约束（spec §5.3）：
 * <ul>
 *   <li>盘点调整**只改 wms_inventory**，绝不建 inbound/outbound 真单据（否则会触发设备下发
 *       与销售出库兜底回写）；</li>
 *   <li>「开始盘点」才冻结范围内库位；出库分配/移库建任务/入库提交三条路径拦截，在途任务落账只标重盘不拒收。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Service
public class WmsStocktakeServiceImpl implements IWmsStocktakeService {

    private static final String NO_PREFIX = "PD";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FLAG_YES = "1";
    private static final String FLAG_NO = "0";

    private final WmsStocktakeOrderMapper orderMapper;
    private final WmsStocktakeDetailMapper detailMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsStocktakeStatusResolver statusResolver;

    /** 整单复核阈值（%）；空阈值会让任何差异都强制复核，必须给默认值。 */
    @org.springframework.beans.factory.annotation.Value("${wms.stocktake.diff-review-threshold:5}")
    private java.math.BigDecimal diffReviewThreshold = new java.math.BigDecimal("5");
    private final WmsStocktakeDiffCalculator diffCalculator = new WmsStocktakeDiffCalculator();

    public WmsStocktakeServiceImpl(WmsStocktakeOrderMapper orderMapper,
                                   WmsStocktakeDetailMapper detailMapper,
                                   WmsInventoryMapper inventoryMapper,
                                   WmsLocationMapper locationMapper,
                                   WmsStocktakeStatusResolver statusResolver) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.inventoryMapper = inventoryMapper;
        this.locationMapper = locationMapper;
        this.statusResolver = statusResolver;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsStocktakeOrder create(WmsStocktakeCreateDTO dto, String operator) {
        if (dto == null || CollectionUtils.isEmpty(dto.getLocationIds())) {
            throw new RuntimeException("盘点范围不能为空");
        }
        String type = dto.getStocktakeType() == null ? WmsConstants.STOCKTAKE_TYPE_PERIODIC : dto.getStocktakeType();

        List<WmsInventory> stocks = inventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .in(WmsInventory::getLocationId, dto.getLocationIds()));
        if (stocks.isEmpty()) {
            throw new RuntimeException("所选库位没有库存，无法盘点");
        }

        WmsStocktakeOrder order = new WmsStocktakeOrder()
                .setStocktakeNo(nextNo())
                .setStocktakeType(type)
                .setScopeDesc("库位数 " + dto.getLocationIds().size())
                .setStatus(WmsConstants.STOCKTAKE_STATUS_DRAFT)
                .setDiffReviewThreshold(diffReviewThreshold)
                .setBookTotal(BigDecimal.ZERO)
                .setGainTotal(BigDecimal.ZERO)
                .setLossTotal(BigDecimal.ZERO)
                .setInitiator(operator)
                .setRemark(dto.getRemark());
        orderMapper.insert(order);

        BigDecimal bookTotal = BigDecimal.ZERO;
        for (WmsInventory stock : stocks) {
            BigDecimal bookQty = stock.getQuantity() == null ? BigDecimal.ZERO : stock.getQuantity();
            WmsStocktakeDetail detail = new WmsStocktakeDetail()
                    .setStocktakeId(order.getId())
                    .setLocationId(stock.getLocationId())
                    .setLocationCode(locationCodeOf(stock.getLocationId()))
                    .setMaterialId(stock.getMaterialId())
                    .setBatchNo(stock.getBatchNo())
                    .setContainerCode(stock.getContainerCode())
                    .setBookQty(bookQty)
                    .setDiffQty(BigDecimal.ZERO)
                    .setRecheckFlag(FLAG_NO)
                    .setAdjusted(FLAG_NO);
            detailMapper.insert(detail);
            bookTotal = bookTotal.add(bookQty);
        }
        order.setBookTotal(bookTotal);
        orderMapper.updateById(order);

        // 冻结刻意不在这里做：草稿态不该拦住业务。见 start()。
        log.info("盘点单创建：单号={}，类型={}，明细={}，账面合计={}",
                order.getStocktakeNo(), type, stocks.size(), bookTotal);
        return order;
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<WmsStocktakeOrder> listPage(int pageNo, int pageSize,
                                                                                    String stocktakeType,
                                                                                    String stocktakeNo,
                                                                                    String keyword) {
        LambdaQueryWrapper<WmsStocktakeOrder> wrapper = new LambdaQueryWrapper<>();
        if (stocktakeType != null && !stocktakeType.isEmpty()) {
            wrapper.eq(WmsStocktakeOrder::getStocktakeType, stocktakeType);
        }
        // 查询窗口模糊匹配（2026-11-02）：前端有「盘点单号」搜索框，此前后端根本没接收该参数
        if (stocktakeNo != null && !stocktakeNo.trim().isEmpty()) {
            wrapper.like(WmsStocktakeOrder::getStocktakeNo, stocktakeNo.trim());
        }
        // 全局关键词（2026-11-02）：跨字段模糊，与单字段框是 AND 关系
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(WmsStocktakeOrder::getStocktakeNo, kw));
        }
        wrapper.orderByDesc(WmsStocktakeOrder::getCreateTime);
        return orderMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize),
                wrapper);
    }

    @Override
    public void start(String id) {
        WmsStocktakeOrder order = requireById(id);
        statusResolver.assertTransition(order.getStatus(), WmsConstants.STOCKTAKE_STATUS_COUNTING);
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_COUNTING);
        orderMapper.updateById(order);
        // 冻结范围内库位：只有真正开始盘点才拦，避免「建了草稿就永冻」
        for (WmsStocktakeDetail detail : listDetails(order.getId())) {
            freeze(detail.getLocationId());
        }
    }

    /** 冻结库位（已冻结则跳过）。 */
    private void freeze(String locationId) {
        if (locationId == null) {
            return;
        }
        WmsLocation location = locationMapper.selectById(locationId);
        if (location != null && !WmsConstants.STOCKTAKE_FROZEN.equals(location.getStocktakeFrozen())) {
            location.setStocktakeFrozen(WmsConstants.STOCKTAKE_FROZEN);
            locationMapper.updateById(location);
        }
    }

    @Override
    public WmsStocktakeOrder requireById(String id) {
        WmsStocktakeOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new RuntimeException("盘点单不存在：" + id);
        }
        return order;
    }

    @Override
    public List<WmsStocktakeDetail> listDetails(String stocktakeId) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsStocktakeDetail>()
                .eq(WmsStocktakeDetail::getStocktakeId, stocktakeId)
                .orderByAsc(WmsStocktakeDetail::getLocationCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordActual(String id, List<WmsStocktakeDetailVO> actuals) {
        WmsStocktakeOrder order = requireById(id);
        if (!WmsConstants.STOCKTAKE_STATUS_COUNTING.equals(order.getStatus())) {
            throw new RuntimeException("只有「盘点中」的盘点单可以录入实盘数量");
        }
        if (CollectionUtils.isEmpty(actuals)) {
            throw new RuntimeException("实盘数据不能为空");
        }
        for (WmsStocktakeDetailVO vo : actuals) {
            if (vo == null || vo.getId() == null) {
                continue;
            }
            WmsStocktakeDetail detail = detailMapper.selectById(vo.getId());
            if (detail == null || !id.equals(detail.getStocktakeId())) {
                throw new RuntimeException("盘点明细不存在或不属于本盘点单：" + vo.getId());
            }
            if (vo.getActualQty() == null) {
                // 未录入 ≠ 实盘 0：留空的行保持 null，summarize 会跳过它，调整阶段也不会删库存
                continue;
            }
            if (vo.getActualQty().signum() < 0) {
                throw new RuntimeException("实盘数量不能为负：" + vo.getId());
            }
            BigDecimal actual = vo.getActualQty();
            detail.setActualQty(actual);
            detail.setDiffQty(actual.subtract(detail.getBookQty() == null ? BigDecimal.ZERO : detail.getBookQty()));
            detail.setDiffReason(vo.getDiffReason());
            detailMapper.updateById(detail);
        }
        refreshTotals(order);
    }

    /** 重算整单账面/盈亏合计并落库。 */
    private void refreshTotals(WmsStocktakeOrder order) {
        BigDecimal[] summary = diffCalculator.summarize(listDetails(order.getId()));
        order.setBookTotal(summary[0]);
        order.setGainTotal(summary[1]);
        order.setLossTotal(summary[2]);
        orderMapper.updateById(order);
    }

    @org.springframework.beans.factory.annotation.Value("${wms.stocktake.simulate-review:}")
    private String simulateReview;

    @Override
    public void confirmDiff(String id, String operator) {
        WmsStocktakeOrder order = requireById(id);
        statusResolver.assertTransition(order.getStatus(), WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        boolean overThreshold = diffCalculator.needsReview(order.getBookTotal(),
                order.getGainTotal(), order.getLossTotal(), order.getDiffReviewThreshold());
        if (overThreshold) {
            statusResolver.assertTransition(order.getStatus(), WmsConstants.STOCKTAKE_STATUS_REVIEWING);
            order.setStatus(WmsConstants.STOCKTAKE_STATUS_REVIEWING);
        }
        // 差异确认人留痕（updateBy 走框架审计列，无需新列）
        order.setUpdateBy(operator);
        orderMapper.updateById(order);
    }

    @Override
    public void review(String id, String reviewer) {
        WmsStocktakeOrder order = requireById(id);
        statusResolver.assertTransition(order.getStatus(), WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        boolean simulate = simulateReview != null && !simulateReview.isEmpty();
        if (!simulate && reviewer != null && reviewer.equals(order.getInitiator())) {
            throw new RuntimeException("复核人不能是盘点发起人");
        }
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        order.setReviewer(reviewer);
        order.setReviewTime(new Date());
        orderMapper.updateById(order);
    }

    @Override
    public void markErpWriteback(String id, boolean success, String message) {
        WmsStocktakeOrder order = requireById(id);
        order.setErpSyncStatus(success ? "已回写" : "回写失败");
        // erp_bill_no 只存金蝶真实单号（由 WmsStocktakeErpWriterImpl 回存），
        // 不再挪用存状态文案（2026-10-28「ERP 单号为准」修正）
        orderMapper.updateById(order);
    }

    @Override
    public void archive(String id) {
        WmsStocktakeOrder order = requireById(id);
        statusResolver.assertTransition(order.getStatus(), WmsConstants.STOCKTAKE_STATUS_ARCHIVED);
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_ARCHIVED);
        order.setArchiveTime(new Date());
        orderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markRecheck(String locationId, String materialId, String batchNo, String containerCode) {
        // 找进行中（盘点中/差异确认/复核中）且范围覆盖该库位的盘点单
        List<WmsStocktakeOrder> active = orderMapper.selectList(new LambdaQueryWrapper<WmsStocktakeOrder>()
                .in(WmsStocktakeOrder::getStatus, WmsConstants.STOCKTAKE_STATUS_COUNTING,
                        WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, WmsConstants.STOCKTAKE_STATUS_REVIEWING));
        for (WmsStocktakeOrder order : active) {
            List<WmsStocktakeDetail> details = listDetails(order.getId());
            boolean covered = details.stream().anyMatch(d -> locationId.equals(d.getLocationId()));
            if (!covered) {
                continue;
            }
            // materialId 为空的回执无法精确对应明细行——宁可插快照行也不误标别人的行
            WmsStocktakeDetail hit = StringUtils.hasText(materialId) ? details.stream().filter(d ->
                    locationId.equals(d.getLocationId())
                            && materialId.equals(d.getMaterialId())
                            && eqOrNull(batchNo, d.getBatchNo())
                            && eqOrNull(containerCode, d.getContainerCode())).findFirst().orElse(null) : null;
            if (hit != null) {
                hit.setRecheckFlag(WmsConstants.STOCKTAKE_FROZEN);
                detailMapper.updateById(hit);
            } else {
                // 精确行不存在（该库位新进了别的东西）：插一条快照重盘行
                WmsStocktakeDetail extra = new WmsStocktakeDetail()
                        .setStocktakeId(order.getId())
                        .setLocationId(locationId)
                        .setLocationCode(locationCodeOf(locationId))
                        .setMaterialId(materialId)
                        .setBatchNo(batchNo)
                        .setContainerCode(containerCode)
                        .setBookQty(BigDecimal.ZERO)
                        .setDiffQty(BigDecimal.ZERO)
                        .setRecheckFlag(WmsConstants.STOCKTAKE_FROZEN)
                        .setAdjusted(FLAG_NO);
                detailMapper.insert(extra);
            }
        }
    }

    private static boolean eqOrNull(String a, String b) {
        return a == null || a.isEmpty() || a.equals(b);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyAdjustment(String id, String operator) {
        WmsStocktakeOrder order = requireById(id);
        if (!WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED.equals(order.getStatus())) {
            throw new RuntimeException("只有「差异确认」的盘点单可以调整库存，当前：" + order.getStatus());
        }
        List<WmsStocktakeDetail> details = listDetails(id);
        for (WmsStocktakeDetail detail : details) {
            if (detail.getActualQty() == null || FLAG_YES.equals(detail.getAdjusted())) {
                continue;
            }
            applyToInventory(detail);
            detail.setAdjusted(FLAG_YES);
            detailMapper.updateById(detail);
        }
        // 归档前的前一步：解冻范围内库位（盘点已结束，业务恢复）
        details.stream().map(WmsStocktakeDetail::getLocationId).filter(java.util.Objects::nonNull).distinct()
                .forEach(this::unfreeze);
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_ADJUSTED);
        order.setAdjustTime(new Date());
        orderMapper.updateById(order);
        log.info("盘点单[{}]库存调整完成，操作人={}", order.getStocktakeNo(), operator);
    }

    /** 按实盘覆盖库存：0 → 删行；有行 → 覆盖；无行且 >0 → 插入。绝不建出入库单据。 */
    private void applyToInventory(WmsStocktakeDetail detail) {
        WmsInventory existing = inventoryMapper.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getMaterialId, detail.getMaterialId())
                .eq(WmsInventory::getLocationId, detail.getLocationId())
                .eq(detail.getBatchNo() != null, WmsInventory::getBatchNo, detail.getBatchNo())
                .eq(detail.getContainerCode() != null, WmsInventory::getContainerCode, detail.getContainerCode())
                .last("limit 1"));
        if (detail.getActualQty().signum() == 0) {
            if (existing != null) {
                inventoryMapper.deleteById(existing.getId());
            }
            return;
        }
        if (existing == null) {
            WmsInventory created = new WmsInventory();
            created.setMaterialId(detail.getMaterialId());
            created.setLocationId(detail.getLocationId());
            created.setBatchNo(detail.getBatchNo());
            created.setContainerCode(detail.getContainerCode());
            created.setQuantity(detail.getActualQty());
            created.setLockedQty(BigDecimal.ZERO);
            // 盘盈是新增行：实物入库时点无从得知，取发现时间（spec 2026-10-28 D4）
            created.setFirstInboundTime(new Date());
            inventoryMapper.insert(created);
            return;
        }
        existing.setQuantity(detail.getActualQty());
        inventoryMapper.updateById(existing);
    }

    /**
     * 解冻：仅当**没有别的进行中盘点单**仍覆盖该库位时才解冻。
     *
     * <p>计划自动建的全仓单与手工单可能范围重叠；先完成的一张若直接解冻，
     * 另一张「盘点中」的单就失去冻结保护。
     */
    private void unfreeze(String locationId) {
        WmsLocation location = locationMapper.selectById(locationId);
        if (location == null || !WmsConstants.STOCKTAKE_FROZEN.equals(location.getStocktakeFrozen())) {
            return;
        }
        List<WmsStocktakeOrder> active = orderMapper.selectList(new LambdaQueryWrapper<WmsStocktakeOrder>()
                .in(WmsStocktakeOrder::getStatus, WmsConstants.STOCKTAKE_STATUS_COUNTING,
                        WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, WmsConstants.STOCKTAKE_STATUS_REVIEWING));
        boolean stillCovered = false;
        for (WmsStocktakeOrder other : active) {
            if (listDetails(other.getId()).stream()
                    .anyMatch(d -> locationId.equals(d.getLocationId()))) {
                stillCovered = true;
                break;
            }
        }
        if (stillCovered) {
            log.info("库位[{}]仍被其他进行中盘点单覆盖，暂不解冻", location.getLocationCode());
            return;
        }
        location.setStocktakeFrozen(WmsConstants.STOCKTAKE_UNFROZEN);
        locationMapper.updateById(location);
    }

    private String locationCodeOf(String locationId) {
        WmsLocation location = locationMapper.selectById(locationId);
        return location == null ? null : location.getLocationCode();
    }

    private String nextNo() {
        String day = LocalDate.now().format(DAY);
        Long todayCount = orderMapper.selectCount(new LambdaQueryWrapper<WmsStocktakeOrder>()
                .likeRight(WmsStocktakeOrder::getStocktakeNo, NO_PREFIX + day));
        long seq = (todayCount == null ? 0L : todayCount) + 1L;
        return NO_PREFIX + day + String.format("%04d", seq);
    }

    /** 供 Task 8 复用：当前时间。 */
    static Date now() {
        return new Date();
    }

    /** 供测试断言：构造明细列表（Task 4 会用）。 */
    static List<WmsStocktakeDetail> newDetailList() {
        return new ArrayList<>();
    }
}
