package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecg.common.system.base.service.impl.JeecgServiceImpl;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSyncReconcileService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeInventoryReader;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsSyncAggregateVO;
import org.jeecg.modules.wms.vo.WmsSyncFlowRowVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @Description: SB-001 上位系统对账执行服务（spec 2026-11-03 §2.2-§2.7）。
 *
 * <p>一次对账 = 一个 {@code wms_sync_run} 批次，流程顺序固定：
 * <ol>
 *   <li>汇总 WMS 库存（按范围定窗口）→ 逐行落 {@code wms_sync_snapshot}（先落库，后续查询失败也保留证据）；</li>
 *   <li>汇总 4 类流水 → 落 {@code wms_sync_flow}（仅展示，不参与比对）；</li>
 *   <li>查金蝶即时库存（首次 + 最多 3 次重试，退避 1s/2s/4s）；</li>
 *   <li>比对（TODAY 口径不产生「仅ERP」）；</li>
 *   <li>差异行落 {@code wms_sync_diff}；</li>
 *   <li>回填批次计数与状态；任何异常置批次失败 + 同步日志 + 站内告警。</li>
 * </ol>
 *
 * <p><b>异步线程没有 Shiro 上下文</b>：所有落库行的 {@code create_by/update_by} 一律显式使用
 * {@code run.operator}，绝不调用 {@code WmsUtils.currentUsername()}（异步线程里它恒为 null）。
 *
 * <p><b>开关与重试都在本服务</b>：{@link IWmsKingdeeInventoryReader} 内部不判
 * {@code wms.sync.erp.kingdee.enabled}、不重试，二者由本类负责。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Service
public class WmsSyncReconcileServiceImpl extends JeecgServiceImpl<WmsSyncRunMapper, WmsSyncRun>
        implements IWmsSyncReconcileService {

    /** 首次 + 最多 3 次重试 = 共 4 次尝试（spec §2.6 蓝图「自动重试 3 次」）。 */
    static final int MAX_RETRY = 3;

    /**
     * 重试退避（毫秒），下标 = 第几次重试（0 起）。
     *
     * <p>包级可见且**非 final**：单测置 0 以免真等 7 秒。生产代码不要改它。
     */
    static long[] BACKOFF_MS = {1000L, 2000L, 4000L};

    /** 批次号前缀：SB + yyyyMMddHHmmss + 3 位随机。 */
    static final String RUN_NO_PREFIX = "SB";

    static final String RUN_NO_TIME_PATTERN = "yyyyMMddHHmmss";

    /** 分页上限，防手滑传大数。 */
    static final int MAX_PAGE_SIZE = 200;

    /** 定时任务（无登录态）的操作人。 */
    static final String SYSTEM_OPERATOR = "system";

    private final WmsInventoryMapper inventoryMapper;
    private final WmsSyncSnapshotMapper snapshotMapper;
    private final WmsSyncFlowMapper flowMapper;
    private final WmsSyncDiffMapper diffMapper;
    private final IWmsKingdeeInventoryReader inventoryReader;
    private final IWmsSyncLogService syncLogService;
    private final IWmsSystemConfigService configService;
    private final WmsKingdeeProperties kingdeeProperties;
    private final ISysBaseAPI sysBaseAPI;

    /** 对账执行线程池：单线程即可（并发批次在建单时已拒绝），daemon 不阻塞 JVM 退出。 */
    private final ExecutorService reconcileExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wms-sync-reconcile");
        thread.setDaemon(true);
        return thread;
    });

    /** 仅单测替换为同步执行器。 */
    private Executor executor = reconcileExecutor;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsSyncReconcileServiceImpl(WmsInventoryMapper inventoryMapper,
                                       WmsSyncSnapshotMapper snapshotMapper,
                                       WmsSyncFlowMapper flowMapper,
                                       WmsSyncDiffMapper diffMapper,
                                       IWmsKingdeeInventoryReader inventoryReader,
                                       IWmsSyncLogService syncLogService,
                                       IWmsSystemConfigService configService,
                                       WmsKingdeeProperties kingdeeProperties,
                                       ISysBaseAPI sysBaseAPI) {
        this.inventoryMapper = inventoryMapper;
        this.snapshotMapper = snapshotMapper;
        this.flowMapper = flowMapper;
        this.diffMapper = diffMapper;
        this.inventoryReader = inventoryReader;
        this.syncLogService = syncLogService;
        this.configService = configService;
        this.kingdeeProperties = kingdeeProperties;
        this.sysBaseAPI = sysBaseAPI;
    }

    @PreDestroy
    void shutdownExecutor() {
        reconcileExecutor.shutdownNow();
    }

    /** 仅单测：把异步提交替换为同步执行器（如 {@code Runnable::run}）。 */
    void setExecutorForTest(Executor executor) {
        this.executor = executor;
    }

    /** 仅单测：覆盖告警收件人。 */
    void setAlertToUserForTest(String alertToUser) {
        this.alertToUser = alertToUser;
    }

    // ================================================================ 建批次

    @Override
    public String createRun(WmsSyncRunCreateDTO dto, String operator) {
        String scope = dto == null ? null : dto.getScopeType();
        if (!isSupportedScope(scope)) {
            throw new RuntimeException("不支持的对账范围：" + scope);
        }
        String op = oConvertUtils.isEmpty(operator) ? SYSTEM_OPERATOR : operator.trim();

        // 定时任务（system）防御性复核 sync.auto；手工触发不受该开关限制（spec §2.2）
        if (SYSTEM_OPERATOR.equals(op) && configService != null
                && !configService.getBool(WmsConfigKey.SYNC_AUTO.getKey(), true)) {
            throw new RuntimeException("自动同步已关闭（sync.auto=false），请在业务页面手工执行对账");
        }

        List<String> warehouses = normalizeWarehouses(dto.getWarehouseCodes());
        if (WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(scope) && warehouses.isEmpty()) {
            throw new RuntimeException("指定仓库对账必须选择至少一个仓库");
        }

        Long running = baseMapper.selectCount(
                new QueryWrapper<WmsSyncRun>().eq("status", WmsConstants.SYNC_RUN_STATUS_RUNNING));
        if (running != null && running > 0) {
            throw new RuntimeException("已有对账批次在执行中，请稍后再试");
        }

        Date now = new Date();
        Date windowStart;
        Date windowEnd;
        if (dto.getWindowStart() != null) {
            // 定时任务水位线优先：上次成功批次的 window_end → 当前
            windowStart = dto.getWindowStart();
            windowEnd = now;
        } else if (WmsConstants.SYNC_SCOPE_TODAY.equals(scope)) {
            windowStart = startOfToday(now);
            windowEnd = now;
        } else {
            // FULL / WAREHOUSE：当前时刻标记点查（库存取全量，标记点只用于批次留痕）
            windowStart = now;
            windowEnd = now;
        }

        WmsSyncRun run = new WmsSyncRun()
                .setRunNo(newRunNo(now))
                .setScopeType(scope)
                .setWarehouseCodes(WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(scope)
                        ? String.join(",", warehouses) : null)
                .setWindowStart(windowStart)
                .setWindowEnd(windowEnd)
                .setStatus(WmsConstants.SYNC_RUN_STATUS_RUNNING)
                .setSnapshotCount(0)
                .setDiffCount(0)
                .setFlowCount(0)
                .setRetryCount(0)
                .setOperator(op);
        // 显式 ID：异步线程与单测都不依赖数据库回填主键
        run.setId(IdWorker.getIdStr());
        stampCreate(run, op, now);

        try {
            baseMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // uk_run_no 冲突（同一秒建两单）：重取一次，仍冲突则抛出由调用方提示重试
            run.setRunNo(newRunNo(new Date()));
            baseMapper.insert(run);
        }

        String runId = run.getId();
        try {
            executor.execute(() -> executeRun(runId));
        } catch (RuntimeException e) {
            log.error("对账批次[{}]异步提交失败：{}", run.getRunNo(), e.getMessage(), e);
            run.setStatus(WmsConstants.SYNC_RUN_STATUS_FAILED)
                    .setErrorMessage("异步提交失败：" + messageOf(e))
                    .setUpdateBy(op)
                    .setUpdateTime(new Date());
            baseMapper.updateById(run);
        }
        return runId;
    }

    // ================================================================ 执行

    @Override
    public void executeRun(String runId) {
        WmsSyncRun run;
        try {
            run = baseMapper.selectById(runId);
        } catch (Exception e) {
            log.error("读取对账批次[{}]失败：{}", runId, e.getMessage(), e);
            return;
        }
        if (run == null) {
            log.warn("对账批次[{}]不存在，跳过执行", runId);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            doExecute(run, start);
        } catch (Exception e) {
            log.error("对账批次[{}]执行失败：{}", run.getRunNo(), e.getMessage(), e);
            failRun(run, e, start);
        }
    }

    /** 包级可见：单测同步直调。 */
    void executeRunForTest(String runId) {
        executeRun(runId);
    }

    private void doExecute(WmsSyncRun run, long start) {
        String operator = operatorOf(run);
        Date now = new Date();

        // 1. 置 retryCount=0 并留痕（长任务期间前端可见）
        run.setRetryCount(0).setUpdateBy(operator).setUpdateTime(now);
        baseMapper.updateById(run);

        boolean today = WmsConstants.SYNC_SCOPE_TODAY.equals(run.getScopeType());

        // 2. 汇总 WMS 库存 → 快照（先落库，查询失败也保留）
        List<WmsSyncSnapshot> snapshots = buildSnapshots(run, operator, now);

        // 3. 汇总流水 → 落库（仅展示）
        int flowCount = persistFlows(run, operator, now);

        // 4. 查金蝶（带重试）
        Map<String, BigDecimal> erpByKey = queryErp(run, snapshots);

        // 5. 仅ERP 单边行（TODAY 口径不产生）
        List<WmsSyncSnapshot> erpOnlyRows = today
                ? new ArrayList<>()
                : buildErpOnlyRows(run, snapshots, erpByKey, operator, now);

        // 6. 比对：TODAY 把 ERP map 收敛到快照 key 交集；FULL/WAREHOUSE 全量 + 单边行
        Map<String, BigDecimal> effectiveErp = today
                ? intersectWithSnapshotKeys(snapshots, erpByKey) : erpByKey;
        List<WmsSyncSnapshot> allRows = new ArrayList<>(snapshots);
        allRows.addAll(erpOnlyRows);
        // apply 在全部成功后才允许落库：中途抛异常时前面的行已被就地改写
        WmsSyncComparator.apply(allRows, effectiveErp, !today);

        // 7. 落比对结果 + 差异清单
        int diffCount = persistComparisonResult(run, snapshots, erpOnlyRows, operator, now);

        // 8. 回填批次
        int total = allRows.size();
        run.setStatus(WmsConstants.SYNC_RUN_STATUS_SUCCESS)
                .setSnapshotCount(total)
                .setDiffCount(diffCount)
                .setFlowCount(flowCount)
                .setCostMs(costMs(start))
                .setErrorMessage(null)
                .setUpdateBy(operator)
                .setUpdateTime(new Date());
        baseMapper.updateById(run);
        log.info("对账批次[{}]完成：范围={}，快照={}，流水={}，差异={}，重试={}，耗时={}ms",
                run.getRunNo(), run.getScopeType(), total, flowCount, diffCount,
                run.getRetryCount(), run.getCostMs());
    }

    // ---------------------------------------------------------------- 快照

    private List<WmsSyncSnapshot> buildSnapshots(WmsSyncRun run, String operator, Date now) {
        List<WmsSyncAggregateVO> aggregates = inventoryMapper.selectReconcileAggregate(
                resolveInventoryWindowStart(run), aggregateWarehouses(run));
        List<WmsSyncSnapshot> snapshots = new ArrayList<>();
        if (aggregates == null) {
            return snapshots;
        }
        Date snapshotTime = new Date();
        for (WmsSyncAggregateVO vo : aggregates) {
            if (vo == null) {
                continue;
            }
            WmsSyncSnapshot row = new WmsSyncSnapshot()
                    .setRunId(run.getId())
                    .setWarehouseCode(nvl(vo.getErpWarehouseCode()))
                    .setMaterialCode(nvl(vo.getErpMaterialCode()))
                    .setMaterialName(vo.getMaterialName())
                    .setUnit(vo.getUnit())
                    .setBatchNo(nvl(vo.getBatchNo()))
                    .setWmsQty(vo.getQty() == null ? BigDecimal.ZERO : vo.getQty())
                    .setLockedQty(vo.getLockedQty())
                    .setSnapshotTime(snapshotTime);
            stampCreate(row, operator, now);
            snapshotMapper.insert(row);
            snapshots.add(row);
        }
        return snapshots;
    }

    /**
     * 库存聚合窗口：TODAY 用批次窗口起点；FULL/WAREHOUSE 的「标记点查」窗口起点=终点，
     * 表示不加 update_time 过滤（取全量库存）。
     */
    private Date resolveInventoryWindowStart(WmsSyncRun run) {
        if (WmsConstants.SYNC_SCOPE_TODAY.equals(run.getScopeType())) {
            return run.getWindowStart();
        }
        Date start = run.getWindowStart();
        Date end = run.getWindowEnd();
        if (start == null || end == null || start.equals(end)) {
            return null;
        }
        return start;
    }

    private List<String> aggregateWarehouses(WmsSyncRun run) {
        if (!WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(run.getScopeType())) {
            return null;
        }
        return splitWarehouses(run.getWarehouseCodes());
    }

    // ---------------------------------------------------------------- 流水

    private int persistFlows(WmsSyncRun run, String operator, Date now) {
        Date windowStart = run.getWindowStart() == null ? now : run.getWindowStart();
        Date windowEnd = run.getWindowEnd() == null ? now : run.getWindowEnd();
        List<WmsSyncFlowRowVO> rows = new ArrayList<>();
        appendAll(rows, flowMapper.selectProductionInboundRows(windowStart, windowEnd));
        appendAll(rows, flowMapper.selectOutboundRows(windowStart, windowEnd));
        appendAll(rows, flowMapper.selectTransferRows(windowStart, windowEnd));
        appendAll(rows, flowMapper.selectStocktakeRows(windowStart, windowEnd));

        Date created = new Date();
        for (WmsSyncFlowRowVO vo : rows) {
            if (vo == null) {
                continue;
            }
            WmsSyncFlow flow = new WmsSyncFlow()
                    .setRunId(run.getId())
                    .setFlowType(vo.getFlowType())
                    .setDirection(vo.getDirection())
                    .setBillNo(vo.getBillNo())
                    .setWarehouseCode(nvl(vo.getWarehouseCode()))
                    .setMaterialCode(nvl(vo.getMaterialCode()))
                    .setMaterialName(vo.getMaterialName())
                    .setBatchNo(nvl(vo.getBatchNo()))
                    .setQty(vo.getQty())
                    .setBizTime(vo.getBizTime());
            stampCreate(flow, operator, created);
            flowMapper.insert(flow);
        }
        return rows.size();
    }

    // ---------------------------------------------------------------- 金蝶查询

    /**
     * 查金蝶即时库存：首次 + 最多 {@value #MAX_RETRY} 次重试。
     *
     * <p>失败日志口径：只对**后面还会重试**的失败写日志（retryCount=1..3），第 4 次尝试失败直接
     * 抛出，由 {@link #failRun} 统一置批次失败并告警——因此一次「4 次尝试全失败」恰好产生 3 条
     * 失败日志，不会出现终态重复记录。
     */
    private Map<String, BigDecimal> queryErp(WmsSyncRun run, List<WmsSyncSnapshot> snapshots) {
        if (kingdeeProperties == null || !kingdeeProperties.isEnabled()) {
            throw new IllegalStateException("金蝶同步未启用（wms.sync.erp.kingdee.enabled=false），无法执行对账");
        }
        List<String> warehouseCodes = distinctErpWarehouses(run, snapshots);
        Map<String, BigDecimal> result = null;
        String lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            long callStart = System.currentTimeMillis();
            try {
                Map<String, BigDecimal> raw = inventoryReader.queryInventory(warehouseCodes);
                result = raw == null ? new LinkedHashMap<>() : raw;
                run.setRetryCount(attempt);
                syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_WMS_TO_ERP,
                        WmsConstants.SYNC_LOG_TYPE_RECONCILE, run.getRunNo(),
                        WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, attempt,
                        querySummary(run, warehouseCodes),
                        "金蝶即时库存行数=" + result.size(),
                        null, System.currentTimeMillis() - callStart);
                return result;
            } catch (Exception e) {
                lastError = messageOf(e);
                // 列约束 retry_count 0~3：第 4 次尝试（attempt=3）仍是「已重试 3 次」
                int retries = Math.min(attempt + 1, MAX_RETRY);
                run.setRetryCount(retries);
                if (attempt >= MAX_RETRY) {
                    break;
                }
                syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_WMS_TO_ERP,
                        WmsConstants.SYNC_LOG_TYPE_RECONCILE, run.getRunNo(),
                        WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, retries,
                        querySummary(run, warehouseCodes), null,
                        lastError, System.currentTimeMillis() - callStart);
                sleepBeforeRetry(attempt);
            }
        }
        throw new ErpQueryExhaustedException(
                "金蝶即时库存查询失败（已尝试 " + (MAX_RETRY + 1) + " 次）：" + lastError);
    }

    /**
     * 传给 Reader 的仓库列表必须是 **ERP 仓库编码**（{@code erp_stock_code}），不是 WMS warehouse_code。
     * 空列表会拼出 {@code IN ()}，必须转成 null。
     */
    private List<String> distinctErpWarehouses(WmsSyncRun run, List<WmsSyncSnapshot> snapshots) {
        Set<String> codes = new LinkedHashSet<>();
        for (WmsSyncSnapshot row : snapshots) {
            String code = row.getWarehouseCode() == null ? "" : row.getWarehouseCode().trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty() && WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(run.getScopeType())) {
            // 快照为空时退回到用户所选仓库（本期仓库编码与金蝶 1:1）
            codes.addAll(splitWarehouses(run.getWarehouseCodes()));
        }
        return codes.isEmpty() ? null : new ArrayList<>(codes);
    }

    // ---------------------------------------------------------------- 比对

    private List<WmsSyncSnapshot> buildErpOnlyRows(WmsSyncRun run, List<WmsSyncSnapshot> snapshots,
                                                   Map<String, BigDecimal> erpByKey,
                                                   String operator, Date now) {
        List<WmsSyncSnapshot> rows = new ArrayList<>();
        if (erpByKey == null || erpByKey.isEmpty()) {
            return rows;
        }
        Set<String> wmsKeys = new HashSet<>();
        for (WmsSyncSnapshot row : snapshots) {
            if (!isBlank(row.getMaterialCode())) {
                wmsKeys.add(WmsSyncComparator.key(row.getWarehouseCode(), row.getMaterialCode(), row.getBatchNo()));
            }
        }
        Date snapshotTime = new Date();
        for (Map.Entry<String, BigDecimal> entry : erpByKey.entrySet()) {
            String key = entry.getKey();
            if (key == null || wmsKeys.contains(key)) {
                continue;
            }
            String[] parts = splitKey(key);
            if (parts == null) {
                log.warn("对账批次[{}]跳过无法解析的金蝶库存 key：{}", run.getRunNo(), key);
                continue;
            }
            // 金蝶返回 FBaseQty=0 的 key 不构成「仅ERP」差异：WMS 聚合本来就排除了 0 库存行
            if (entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            WmsSyncSnapshot row = new WmsSyncSnapshot()
                    .setRunId(run.getId())
                    .setWarehouseCode(parts[0])
                    .setMaterialCode(parts[1])
                    .setBatchNo(parts[2])
                    .setWmsQty(BigDecimal.ZERO)
                    .setLockedQty(BigDecimal.ZERO)
                    .setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY)
                    .setSnapshotTime(snapshotTime);
            stampCreate(row, operator, now);
            rows.add(row);
        }
        return rows;
    }

    /** TODAY 口径：把 ERP map 收敛到快照 key 集合，避免任何 ERP 多出的 key 变成差异。 */
    private Map<String, BigDecimal> intersectWithSnapshotKeys(List<WmsSyncSnapshot> snapshots,
                                                              Map<String, BigDecimal> erpByKey) {
        Map<String, BigDecimal> effective = new LinkedHashMap<>();
        if (erpByKey == null || erpByKey.isEmpty()) {
            return effective;
        }
        for (WmsSyncSnapshot row : snapshots) {
            if (isBlank(row.getMaterialCode())) {
                continue;
            }
            String key = WmsSyncComparator.key(row.getWarehouseCode(), row.getMaterialCode(), row.getBatchNo());
            BigDecimal qty = erpByKey.get(key);
            if (qty != null) {
                effective.put(key, qty);
            }
        }
        return effective;
    }

    private int persistComparisonResult(WmsSyncRun run, List<WmsSyncSnapshot> wmsRows,
                                        List<WmsSyncSnapshot> erpOnlyRows, String operator, Date now) {
        int diffCount = 0;
        for (WmsSyncSnapshot row : wmsRows) {
            snapshotMapper.updateById(row);
            diffCount += insertDiffIfNeeded(run, row, operator, now);
        }
        for (WmsSyncSnapshot row : erpOnlyRows) {
            snapshotMapper.insert(row);
            diffCount += insertDiffIfNeeded(run, row, operator, now);
        }
        return diffCount;
    }

    private int insertDiffIfNeeded(WmsSyncRun run, WmsSyncSnapshot row, String operator, Date now) {
        String diffType = diffTypeOf(row);
        if (diffType == null) {
            return 0;
        }
        WmsSyncDiff diff = new WmsSyncDiff()
                .setRunId(run.getId())
                .setWarehouseCode(nvl(row.getWarehouseCode()))
                .setMaterialCode(nvl(row.getMaterialCode()))
                .setMaterialName(row.getMaterialName())
                .setBatchNo(nvl(row.getBatchNo()))
                .setWmsQty(row.getWmsQty())
                .setErpQty(row.getErpQty())
                .setDiffQty(row.getDiffQty() == null ? BigDecimal.ZERO : row.getDiffQty())
                .setDiffType(diffType)
                .setStatus(WmsConstants.DIFF_STATUS_PENDING);
        stampCreate(diff, operator, now);
        diffMapper.insert(diff);
        return 1;
    }

    /**
     * 差异类型：数量差异按 diff 符号细分多账/少账；仅WMS/仅ERP 直接复用快照状态。
     *
     * @return null = 一致（不入差异清单）
     */
    private String diffTypeOf(WmsSyncSnapshot row) {
        String status = row.getMatchStatus();
        if (WmsConstants.SNAPSHOT_MATCH_EQUAL.equals(status) || status == null) {
            return null;
        }
        if (WmsConstants.SNAPSHOT_MATCH_QTY_DIFF.equals(status)) {
            BigDecimal diff = row.getDiffQty() == null ? BigDecimal.ZERO : row.getDiffQty();
            return diff.signum() >= 0 ? WmsConstants.DIFF_TYPE_MORE : WmsConstants.DIFF_TYPE_LESS;
        }
        if (WmsConstants.SNAPSHOT_MATCH_WMS_ONLY.equals(status)) {
            return WmsConstants.SNAPSHOT_MATCH_WMS_ONLY;
        }
        if (WmsConstants.SNAPSHOT_MATCH_ERP_ONLY.equals(status)) {
            return WmsConstants.SNAPSHOT_MATCH_ERP_ONLY;
        }
        return null;
    }

    // ---------------------------------------------------------------- 失败处理

    private void failRun(WmsSyncRun run, Exception e, long start) {
        String operator = operatorOf(run);
        String error = truncate(messageOf(e), WmsConstants.SYNC_ERROR_MAX_LENGTH);
        int retries = run.getRetryCount() == null ? 0 : run.getRetryCount();
        run.setStatus(WmsConstants.SYNC_RUN_STATUS_FAILED)
                .setErrorMessage(error)
                .setRetryCount(retries)
                .setCostMs(costMs(start))
                .setUpdateBy(operator)
                .setUpdateTime(new Date());
        try {
            baseMapper.updateById(run);
        } catch (Exception updateError) {
            log.error("对账批次[{}]失败状态落库失败：{}", run.getRunNo(), updateError.getMessage(), updateError);
        }

        // 金蝶查询耗尽重试时，失败日志已在每次重试前写过（3 条），此处不重复记终态
        if (!(e instanceof ErpQueryExhaustedException)) {
            try {
                syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_WMS_TO_ERP,
                        WmsConstants.SYNC_LOG_TYPE_RECONCILE, run.getRunNo(),
                        WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, retries,
                        querySummary(run, null), null, error, costMs(start));
            } catch (Exception logError) {
                log.error("对账批次[{}]失败日志写入异常：{}", run.getRunNo(), logError.getMessage(), logError);
            }
        }
        alert(run, retries, error);
    }

    /** 站内消息告警；告警设施自身异常不得掩盖对账失败。 */
    private void alert(WmsSyncRun run, int retries, String error) {
        try {
            String content = String.format("对账批次号：%s；范围：%s；重试次数：%d；操作人：%s；失败原因：%s",
                    run.getRunNo(), run.getScopeType(), retries, operatorOf(run), error);
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.RECONCILE_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("对账批次[{}]失败告警投递失败：{}", run.getRunNo(), e.getMessage(), e);
        }
    }

    // ================================================================ 查询

    @Override
    public IPage<WmsSyncRun> pageRuns(int pageNo, int pageSize, String status) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        QueryWrapper<WmsSyncRun> query = new QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(status)) {
            query.eq("status", status);
        }
        query.orderByDesc("create_time");
        return baseMapper.selectPage(new Page<>(safePageNo, safePageSize), query);
    }

    @Override
    public WmsSyncRun requireRun(String id) {
        WmsSyncRun run = baseMapper.selectById(id);
        if (run == null) {
            throw new RuntimeException("对账批次不存在：" + id);
        }
        return run;
    }

    // ================================================================ 工具

    private static boolean isSupportedScope(String scope) {
        return WmsConstants.SYNC_SCOPE_FULL.equals(scope)
                || WmsConstants.SYNC_SCOPE_TODAY.equals(scope)
                || WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(scope);
    }

    private static List<String> normalizeWarehouses(List<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes == null) {
            return out;
        }
        for (String code : codes) {
            if (code != null && !code.trim().isEmpty() && !out.contains(code.trim())) {
                out.add(code.trim());
            }
        }
        return out;
    }

    private static List<String> splitWarehouses(String joined) {
        List<String> out = new ArrayList<>();
        if (oConvertUtils.isEmpty(joined)) {
            return out;
        }
        for (String part : joined.split(",")) {
            if (part != null && !part.trim().isEmpty() && !out.contains(part.trim())) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String newRunNo(Date now) {
        String time = new SimpleDateFormat(RUN_NO_TIME_PATTERN).format(now);
        return RUN_NO_PREFIX + time + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    private static Date startOfToday(Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static void stampCreate(JeecgEntity entity, String operator, Date now) {
        entity.setCreateBy(operator);
        entity.setCreateTime(now);
        entity.setUpdateBy(operator);
        entity.setUpdateTime(now);
    }

    private static void appendAll(List<WmsSyncFlowRowVO> target, List<WmsSyncFlowRowVO> rows) {
        if (rows != null && !rows.isEmpty()) {
            target.addAll(rows);
        }
    }

    /** 解析 {@code 仓库|物料|批号}；格式不合法返回 null。批号可能含分隔符，按前两个切。 */
    private static String[] splitKey(String key) {
        int first = key.indexOf(WmsSyncComparator.KEY_SEPARATOR);
        if (first < 0) {
            return null;
        }
        int second = key.indexOf(WmsSyncComparator.KEY_SEPARATOR, first + 1);
        if (second < 0) {
            return null;
        }
        String material = key.substring(first + 1, second);
        if (material.trim().isEmpty()) {
            return null;
        }
        return new String[]{key.substring(0, first), material, key.substring(second + 1)};
    }

    private static String querySummary(WmsSyncRun run, List<String> warehouseCodes) {
        return "范围=" + run.getScopeType()
                + "；时间窗=" + formatTime(run.getWindowStart()) + " ~ " + formatTime(run.getWindowEnd())
                + "；仓库=" + (warehouseCodes == null ? "全部" : String.join(",", warehouseCodes));
    }

    private static String formatTime(Date date) {
        return date == null ? "null" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    private static String operatorOf(WmsSyncRun run) {
        return oConvertUtils.isEmpty(run.getOperator()) ? SYSTEM_OPERATOR : run.getOperator();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int costMs(long start) {
        return (int) Math.min(System.currentTimeMillis() - start, Integer.MAX_VALUE);
    }

    private static String messageOf(Throwable e) {
        if (e == null) {
            return "未知原因";
        }
        String message = e.getMessage();
        return oConvertUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private void sleepBeforeRetry(int attempt) {
        if (attempt >= BACKOFF_MS.length) {
            return;
        }
        long wait = BACKOFF_MS[attempt];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("对账重试退避等待被中断");
        }
    }

    /**
     * 金蝶查询重试耗尽。
     *
     * <p>用独立类型区分「重试日志已经写过」，{@link #failRun} 据此跳过重复的终态失败日志。
     */
    static class ErpQueryExhaustedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ErpQueryExhaustedException(String message) {
            super(message);
        }
    }
}
