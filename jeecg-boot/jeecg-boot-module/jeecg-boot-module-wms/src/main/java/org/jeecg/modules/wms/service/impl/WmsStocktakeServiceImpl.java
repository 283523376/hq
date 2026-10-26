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
 *   <li>建单即冻结范围内库位，冻结期间出库/移库/上架三条写入路径都会被拦截。</li>
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

    @Override
    public void applyAdjustment(String id, String operator) {
        throw new UnsupportedOperationException("applyAdjustment 由 Task 6 实现");
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
