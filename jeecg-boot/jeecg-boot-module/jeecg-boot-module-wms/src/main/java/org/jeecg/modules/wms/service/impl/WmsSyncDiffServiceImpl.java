package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.base.service.impl.JeecgServiceImpl;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.jeecg.modules.wms.service.IWmsSyncDiffService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @Description: SB-001 对账差异清单处理（spec 2026-11-03 §2.8）。
 *
 * <p>两条硬规则：
 * <ul>
 *   <li>状态机只有「待核对 → 已核对/已忽略」，备注必填；</li>
 *   <li>WMS 无账面（仅ERP / WMS=0 / 反查不到库位）拒绝发起盘点，提示走生产入库，
 *       绝不绕过既有流程硬造库存。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Service
public class WmsSyncDiffServiceImpl extends JeecgServiceImpl<WmsSyncDiffMapper, WmsSyncDiff>
        implements IWmsSyncDiffService {

    /** 分页上限，防手滑传大数（与对账批次列表一致）。 */
    static final int MAX_PAGE_SIZE = 200;

    /** 发起盘点时写进盘点单备注的前缀（DTO 没有 scopeDesc，批次号放备注，spec §2.8）。 */
    static final String STOCKTAKE_REMARK_PREFIX = "SB-001 差异发起: ";

    /** WMS 无账面时的统一提示，前端逐字展示，不要改口径。 */
    static final String NO_STOCK_MESSAGE = "WMS 无账面库存，无法盘点；请走生产入库或其他入库修正";

    /** 物料未绑金蝶编码：与「无账面」是两回事，不能混用文案（M-1）。 */
    static final String MATERIAL_UNBOUND_MESSAGE = "物料未绑定金蝶编码，无法发起盘点，请先完成物料主数据同步";

    private final WmsInventoryMapper inventoryMapper;
    private final WmsSyncRunMapper runMapper;
    private final IWmsStocktakeService stocktakeService;
    private final IWmsSyncLogService syncLogService;

    public WmsSyncDiffServiceImpl(WmsInventoryMapper inventoryMapper,
                                  WmsSyncRunMapper runMapper,
                                  IWmsStocktakeService stocktakeService,
                                  IWmsSyncLogService syncLogService) {
        this.inventoryMapper = inventoryMapper;
        this.runMapper = runMapper;
        this.stocktakeService = stocktakeService;
        this.syncLogService = syncLogService;
    }

    // ================================================================ 分页

    @Override
    public IPage<WmsSyncDiff> pageDiffs(int pageNo, int pageSize, String runId, String warehouseCode,
                                        String materialKeyword, String status, String diffType) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        QueryWrapper<WmsSyncDiff> query = new QueryWrapper<>();
        if (!isBlank(runId)) {
            query.eq("run_id", runId.trim());
        }
        if (!isBlank(warehouseCode)) {
            query.eq("warehouse_code", warehouseCode.trim());
        }
        if (!isBlank(status)) {
            query.eq("status", status.trim());
        }
        if (!isBlank(diffType)) {
            query.eq("diff_type", diffType.trim());
        }
        if (!isBlank(materialKeyword)) {
            String keyword = materialKeyword.trim();
            query.and(w -> w.like("material_code", keyword).or().like("material_name", keyword));
        }
        // create_time 同秒并列时用 id 兜底，否则翻页可能重复/漏行
        query.orderByDesc("create_time").orderByDesc("id");
        return baseMapper.selectPage(new Page<>(safePageNo, safePageSize), query);
    }

    // ================================================================ 标记处理

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String id, String targetStatus, String remark, String operator) {
        WmsSyncDiff diff = requireDiff(id);
        if (!WmsConstants.DIFF_STATUS_CHECKED.equals(targetStatus)
                && !WmsConstants.DIFF_STATUS_IGNORED.equals(targetStatus)) {
            throw new RuntimeException("只能标记为「已核对」或「已忽略」，实际：" + targetStatus);
        }
        if (!WmsConstants.DIFF_STATUS_PENDING.equals(diff.getStatus())) {
            throw new RuntimeException("只有「待核对」的差异可以处理，当前状态：" + diff.getStatus());
        }
        String trimmedRemark = remark == null ? "" : remark.trim();
        if (trimmedRemark.isEmpty()) {
            throw new RuntimeException("处理备注必填");
        }

        diff.setStatus(targetStatus)
                .setHandleBy(operator)
                .setHandleTime(new Date())
                .setHandleRemark(trimmedRemark);
        baseMapper.updateById(diff);

        // 日志失败只降级 WARN：日志库抖动不能把已经生效的标记回滚
        safeLogSync(diff, resolveRunNo(diff), "标记为" + targetStatus + "：" + trimmedRemark, true, null);
    }

    // ================================================================ 发起盘点

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsStocktakeOrder createStocktake(String id, String operator) {
        WmsSyncDiff diff = requireDiff(id);
        // 盘点单没有删除/作废入口：重复建单会让两张单都走 adjust+writeback 向金蝶双发 QTRK/QTCK，
        // 且回填会冲掉第一张单的引用。前端双击就能触发，必须在服务端兜住。
        if (!isBlank(diff.getStocktakeId())) {
            throw new RuntimeException(alreadyStocktakeMessage(diff.getStocktakeNo()));
        }
        if (!WmsConstants.DIFF_STATUS_PENDING.equals(diff.getStatus())) {
            throw new RuntimeException("只有「待核对」的差异可以发起盘点，当前状态：" + diff.getStatus());
        }
        // 物料未绑金蝶编码与「无账面」是两回事，不能复用同一句提示（审查 M-1）
        if (isBlank(diff.getMaterialCode())) {
            throw new RuntimeException(MATERIAL_UNBOUND_MESSAGE);
        }
        if (WmsConstants.SNAPSHOT_MATCH_ERP_ONLY.equals(diff.getDiffType())
                || diff.getWmsQty() == null
                || diff.getWmsQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException(NO_STOCK_MESSAGE);
        }

        List<String> locationIds = inventoryMapper.selectLocationIdsForDiff(
                diff.getWarehouseCode(), diff.getMaterialCode(), nvl(diff.getBatchNo()));
        if (locationIds == null || locationIds.isEmpty()) {
            // WMS 有账面但库存行已归零（或全部锁定释放）：同样不能建空盘点单
            throw new RuntimeException(NO_STOCK_MESSAGE);
        }

        String runNo = resolveRunNo(diff);
        WmsStocktakeCreateDTO dto = new WmsStocktakeCreateDTO();
        dto.setStocktakeType(WmsConstants.STOCKTAKE_TYPE_PERIODIC);
        dto.setLocationIds(locationIds);
        dto.setRemark(STOCKTAKE_REMARK_PREFIX + runNo);

        WmsStocktakeOrder order = stocktakeService.create(dto, operator);
        if (order == null) {
            throw new RuntimeException("盘点单创建失败");
        }

        // 条件更新堵住并发窗口：两个请求都通过了上面的存在性检查时，只有一个能写进去；
        // 落败方 affected=0 抛异常，@Transactional 把刚建好的盘点单一起回滚。
        int affected = baseMapper.update(null, new LambdaUpdateWrapper<WmsSyncDiff>()
                .eq(WmsSyncDiff::getId, id)
                .isNull(WmsSyncDiff::getStocktakeId)
                .set(WmsSyncDiff::getStocktakeId, order.getId())
                .set(WmsSyncDiff::getStocktakeNo, order.getStocktakeNo())
                .set(WmsSyncDiff::getHandleBy, operator)
                .set(WmsSyncDiff::getHandleTime, new Date()));
        if (affected == 0) {
            WmsSyncDiff latest = baseMapper.selectById(id);
            String no = latest != null && !isBlank(latest.getStocktakeNo())
                    ? latest.getStocktakeNo() : diff.getStocktakeNo();
            throw new RuntimeException(alreadyStocktakeMessage(no));
        }

        safeLogSync(diff, runNo, "发起盘点：" + order.getStocktakeNo() + "（库位数 " + locationIds.size() + "）",
                true, null);
        return order;
    }

    // ================================================================ 工具

    private WmsSyncDiff requireDiff(String id) {
        if (isBlank(id)) {
            throw new RuntimeException("差异记录不存在：" + id);
        }
        WmsSyncDiff diff = baseMapper.selectById(id);
        if (diff == null) {
            throw new RuntimeException("差异记录不存在：" + id);
        }
        return diff;
    }

    /** 批次号：优先取 {@code wms_sync_run.run_no}，批次缺失时退回 runId，便于排错。 */
    private String resolveRunNo(WmsSyncDiff diff) {
        String runId = diff.getRunId();
        if (isBlank(runId)) {
            return "";
        }
        WmsSyncRun run = runMapper.selectById(runId);
        return run == null || isBlank(run.getRunNo()) ? runId : run.getRunNo();
    }

    /**
     * 对账类日志统一挂在「批次号」上（objectNo=run_no，objectType=INVENTORY_RECONCILE，target=KINGDEE），
     * 与对账执行服务同口径，便于按批次串起整条链路。
     *
     * <p>{@code runNo} 由调用方解析后传入，避免同一方法里重复查批次表（审查 M-4）。
     */
    private void safeLogSync(WmsSyncDiff diff, String runNo, String detail, boolean success,
                             String errorMessage) {
        try {
            syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_WMS_TO_ERP,
                    WmsConstants.SYNC_LOG_TYPE_RECONCILE, runNo,
                    WmsConstants.SYNC_LOG_TARGET_KINGDEE, success, 0,
                    detail + "；差异=" + nvl(diff.getDiffType()) + "/" + nvl(diff.getMaterialCode())
                            + "/" + nvl(diff.getBatchNo()),
                    success ? "已记录" : null, errorMessage, 0L);
        } catch (Exception e) {
            log.warn("差异[{}]同步日志写入失败（不影响业务）：{}", diff.getId(), e.getMessage());
        }
    }

    private static String alreadyStocktakeMessage(String stocktakeNo) {
        return "该差异已发起盘点单" + (isBlank(stocktakeNo) ? "" : " " + stocktakeNo) + "，请勿重复发起";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
