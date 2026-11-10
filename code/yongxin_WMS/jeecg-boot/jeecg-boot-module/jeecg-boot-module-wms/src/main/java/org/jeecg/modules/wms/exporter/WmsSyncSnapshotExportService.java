package org.jeecg.modules.wms.exporter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.jeecgframework.poi.handler.inter.IExcelExportServer;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @Description: SB-001 待推送包导出（spec 2026-11-03 §2.10）。
 *
 * <p>单批次数据量有限，**同步导出**，不接 KC-004 的异步任务（偏离已记入计划 task-10）。
 * spec 的「快照 + 流水两个 sheet」拆成两个独立端点/文件：{@link WmsExcelWriter} 是单 sheet 接口，
 * 流水另走 {@code GET /wms/sync/flow/export}。
 *
 * <p>每批都用 `id` 游标取数（雪花 id 单调递增），不用 OFFSET——批次内行数虽有限，
 * 但这是全项目导出的统一写法，避免以后数据量上来再回头改。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Service
public class WmsSyncSnapshotExportService {

    private static final String SHEET_SNAPSHOT = "库存快照";
    private static final String SHEET_FLOW = "同步流水";
    private static final String SHEET_DIFF = "对账差异";
    private static final String SECOND_TITLE = "永鑫WMS";
    private static final String SNAPSHOT_FILE_PREFIX = "待推送包_";
    private static final String FLOW_FILE_PREFIX = "同步流水_";
    private static final String DIFF_FILE_PREFIX = "对账差异_";
    private static final String FILE_NAME_TIME_PATTERN = "yyyyMMddHHmmss";
    private static final String JSON_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final WmsSyncRunMapper runMapper;
    private final WmsSyncSnapshotMapper snapshotMapper;
    private final WmsSyncFlowMapper flowMapper;
    private final WmsSyncDiffMapper diffMapper;
    private final WmsExcelWriter excelWriter;

    public WmsSyncSnapshotExportService(WmsSyncRunMapper runMapper,
                                        WmsSyncSnapshotMapper snapshotMapper,
                                        WmsSyncFlowMapper flowMapper,
                                        WmsSyncDiffMapper diffMapper,
                                        WmsExcelWriter excelWriter) {
        this.runMapper = runMapper;
        this.snapshotMapper = snapshotMapper;
        this.flowMapper = flowMapper;
        this.diffMapper = diffMapper;
        this.excelWriter = excelWriter;
    }

    /** 导出的临时文件与下载文件名。 */
    public record SyncExportFile(File file, String fileName) {
    }

    /**
     * 快照单 sheet xlsx。
     *
     * @param runId  批次 ID
     * @param target 目标文件（调用方负责父目录与临时后缀）
     * @throws RuntimeException 批次不存在 / 该批次没有快照数据
     */
    public SyncExportFile exportSnapshot(String runId, File target) throws IOException {
        WmsSyncRun run = requireRun(runId);
        Long total = snapshotMapper.selectCount(Wrappers.<WmsSyncSnapshot>query().eq("run_id", runId));
        if (total == null || total == 0) {
            // 只有表头的文件会被误当成「这个批次没有差异」，真实原因可能是筛选/批次参数传错
            throw new RuntimeException("该批次没有库存快照数据，未生成文件");
        }
        ExportParams params = new ExportParams(
                "待推送包（批次 " + run.getRunNo() + "）", SECOND_TITLE, SHEET_SNAPSHOT, ExcelType.XSSF);
        params.setMaxNum(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX);
        excelWriter.write(params, WmsSyncSnapshot.class,
                new IdCursorPageSource<>(snapshotMapper,
                        () -> Wrappers.<WmsSyncSnapshot>query().eq("run_id", runId),
                        WmsSyncSnapshot::getId,
                        WmsConstants.AUDIT_EXPORT_BATCH_SIZE,
                        WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS),
                null, target);
        return new SyncExportFile(target, fileName(SNAPSHOT_FILE_PREFIX, run.getRunNo(), "xlsx"));
    }

    /** 流水单 sheet xlsx。 */
    public SyncExportFile exportFlow(String runId, File target) throws IOException {
        WmsSyncRun run = requireRun(runId);
        Long total = flowMapper.selectCount(Wrappers.<WmsSyncFlow>query().eq("run_id", runId));
        if (total == null || total == 0) {
            throw new RuntimeException("该批次没有同步流水数据，未生成文件");
        }
        ExportParams params = new ExportParams(
                "同步流水（批次 " + run.getRunNo() + "）", SECOND_TITLE, SHEET_FLOW, ExcelType.XSSF);
        params.setMaxNum(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX);
        excelWriter.write(params, WmsSyncFlow.class,
                new IdCursorPageSource<>(flowMapper,
                        () -> Wrappers.<WmsSyncFlow>query().eq("run_id", runId),
                        WmsSyncFlow::getId,
                        WmsConstants.AUDIT_EXPORT_BATCH_SIZE,
                        WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS),
                null, target);
        return new SyncExportFile(target, fileName(FLOW_FILE_PREFIX, run.getRunNo(), "xlsx"));
    }

    /**
     * 差异清单单 sheet xlsx（spec §2.8「支持导出 Excel」）。
     *
     * <p>筛选口径与页面 {@code GET /wms/sync/diff/page} 一致；导出文件是处理留痕的一部分，
     * 列直接取 {@link WmsSyncDiff} 上的 {@code @Excel} 定义。
     *
     * @throws RuntimeException 批次不存在 / 没有符合条件的差异数据
     */
    public SyncExportFile exportDiff(String runId, String warehouseCode, String materialKeyword,
                                     String status, String diffType, File target) throws IOException {
        WmsSyncRun run = requireRun(runId);
        Long total = diffMapper.selectCount(diffQuery(runId, warehouseCode, materialKeyword, status, diffType));
        if (total == null || total == 0) {
            throw new RuntimeException("没有符合条件的差异数据，未生成文件");
        }
        ExportParams params = new ExportParams(
                "对账差异（批次 " + run.getRunNo() + "）", SECOND_TITLE, SHEET_DIFF, ExcelType.XSSF);
        params.setMaxNum(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX);
        excelWriter.write(params, WmsSyncDiff.class,
                new IdCursorPageSource<>(diffMapper,
                        () -> diffQuery(runId, warehouseCode, materialKeyword, status, diffType),
                        WmsSyncDiff::getId,
                        WmsConstants.AUDIT_EXPORT_BATCH_SIZE,
                        WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS),
                null, target);
        return new SyncExportFile(target, fileName(DIFF_FILE_PREFIX, run.getRunNo(), "xlsx"));
    }

    /** 差异筛选条件：与 {@code WmsSyncDiffServiceImpl.pageDiffs} 保持同一口径。 */
    private static QueryWrapper<WmsSyncDiff> diffQuery(String runId, String warehouseCode,
                                                       String materialKeyword, String status,
                                                       String diffType) {
        QueryWrapper<WmsSyncDiff> wrapper = new QueryWrapper<>();
        wrapper.eq("run_id", runId);
        if (!isBlank(warehouseCode)) {
            wrapper.eq("warehouse_code", warehouseCode.trim());
        }
        if (!isBlank(status)) {
            wrapper.eq("status", status.trim());
        }
        if (!isBlank(diffType)) {
            wrapper.eq("diff_type", diffType.trim());
        }
        if (!isBlank(materialKeyword)) {
            String keyword = materialKeyword.trim();
            wrapper.and(w -> w.like("material_code", keyword).or().like("material_name", keyword));
        }
        return wrapper;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 整包 JSON：{@code {runNo, scopeType, windowStart, windowEnd, snapshot:[...], flow:[...]}}。
     *
     * <p>将来接 BOS 接收端时直接回放这份文件；因此空数据也照常生成（空数组是真实结果）。
     */
    public SyncExportFile exportJson(String runId, File target) throws IOException {
        WmsSyncRun run = requireRun(runId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runNo", run.getRunNo());
        payload.put("scopeType", run.getScopeType());
        payload.put("windowStart", run.getWindowStart());
        payload.put("windowEnd", run.getWindowEnd());
        payload.put("snapshot", snapshotMapper.selectList(
                Wrappers.<WmsSyncSnapshot>query().eq("run_id", runId).orderByAsc("id")));
        payload.put("flow", flowMapper.selectList(
                Wrappers.<WmsSyncFlow>query().eq("run_id", runId).orderByAsc("id")));

        String json = JSON.toJSONString(payload, JSON_DATE_PATTERN, JSONWriter.Feature.WriteMapNullValue);
        Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
        log.info("待推送包 JSON 已写出：{}（{} 字节）", target.getAbsolutePath(), target.length());
        return new SyncExportFile(target, run.getRunNo() + ".json");
    }

    private WmsSyncRun requireRun(String runId) {
        WmsSyncRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw new RuntimeException("对账批次不存在：" + runId);
        }
        return run;
    }

    private static String fileName(String prefix, String runNo, String extension) {
        String stamp = new SimpleDateFormat(FILE_NAME_TIME_PATTERN).format(new Date());
        return prefix + (runNo == null ? "" : runNo) + "_" + stamp + "." + extension;
    }

    /**
     * 按主键游标分批取数，给 AutoPoi 的 {@code exportBigExcel} 回调。
     * 回调是单线程顺序调用的，游标状态无需加锁。
     */
    private static final class IdCursorPageSource<T> implements IExcelExportServer {

        private final BaseMapper<T> mapper;
        private final Supplier<QueryWrapper<T>> queryFactory;
        private final Function<T, String> idGetter;
        private final int batchSize;
        private final int maxRows;

        private String lastId;
        private long exported;

        private IdCursorPageSource(BaseMapper<T> mapper, Supplier<QueryWrapper<T>> queryFactory,
                                   Function<T, String> idGetter, int batchSize, int maxRows) {
            this.mapper = mapper;
            this.queryFactory = queryFactory;
            this.idGetter = idGetter;
            this.batchSize = batchSize;
            this.maxRows = maxRows;
        }

        @Override
        public List<Object> selectListForExcelExport(Object queryParams, int page) {
            QueryWrapper<T> wrapper = queryFactory.get();
            if (lastId != null) {
                wrapper.gt("id", lastId);
            }
            wrapper.orderByAsc("id").last("LIMIT " + batchSize);
            List<T> rows = mapper.selectList(wrapper);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            // 行数守卫必须在返回之前：超限时不能先把行塞进工作簿再抛错
            if (exported + rows.size() > maxRows) {
                throw new IllegalStateException("导出行数超过 Excel 单表上限（" + maxRows
                        + " 行），请缩小查询条件后分批导出");
            }
            exported += rows.size();
            lastId = idGetter.apply(rows.get(rows.size() - 1));
            return new ArrayList<Object>(rows);
        }
    }
}
