package org.jeecg.modules.wms.exporter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 待推送包导出的取数与 JSON 结构单测。
 *
 * <p>JSON 是将来接 BOS 接收端的回放包，键名不能随手改；空批次不能生成只有表头的 xlsx，
 * 否则会被误读成「这批对账没有数据」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncSnapshotExportServiceTest {

    @TempDir
    Path tempDir;

    private WmsSyncRunMapper runMapper;
    private WmsSyncSnapshotMapper snapshotMapper;
    private WmsSyncFlowMapper flowMapper;
    private WmsExcelWriter excelWriter;
    private WmsSyncSnapshotExportService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(WmsSyncRunMapper.class);
        snapshotMapper = mock(WmsSyncSnapshotMapper.class);
        flowMapper = mock(WmsSyncFlowMapper.class);
        excelWriter = mock(WmsExcelWriter.class);
        service = new WmsSyncSnapshotExportService(runMapper, snapshotMapper, flowMapper, excelWriter);
    }

    /** JSON 包固定键：runNo/scopeType/windowStart/windowEnd/snapshot/flow。 */
    @Test
    void exportJson_containsPackageKeys() throws Exception {
        when(runMapper.selectById("R1")).thenReturn(new WmsSyncRun()
                .setRunNo("SB20261103001").setScopeType("TODAY"));
        when(snapshotMapper.selectList(any())).thenReturn(List.of(new WmsSyncSnapshot()
                .setRunId("R1").setWarehouseCode("D.01").setMaterialCode("M1")
                .setWmsQty(new BigDecimal("10")).setErpQty(new BigDecimal("10"))
                .setDiffQty(BigDecimal.ZERO).setMatchStatus("一致")));
        when(flowMapper.selectList(any())).thenReturn(List.of(new WmsSyncFlow()
                .setRunId("R1").setFlowType("入库").setDirection("IN")
                .setBillNo("SCRK001").setQty(new BigDecimal("5"))));

        File target = tempDir.resolve("package.json").toFile();
        WmsSyncSnapshotExportService.SyncExportFile exported = service.exportJson("R1", target);

        assertEquals("SB20261103001.json", exported.fileName());
        JSONObject json = JSON.parseObject(Files.readString(target.toPath(), StandardCharsets.UTF_8));
        assertEquals("SB20261103001", json.getString("runNo"));
        assertEquals("TODAY", json.getString("scopeType"));
        assertEquals(1, json.getJSONArray("snapshot").size());
        assertEquals("M1", json.getJSONArray("snapshot").getJSONObject(0).getString("materialCode"));
        assertEquals(1, json.getJSONArray("flow").size());
    }

    /** 空批次不生成只有表头的文件。 */
    @Test
    void exportSnapshot_emptyBatch_rejected() {
        when(runMapper.selectById("R1")).thenReturn(new WmsSyncRun().setRunNo("SB1"));
        when(snapshotMapper.selectCount(any())).thenReturn(0L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.exportSnapshot("R1", tempDir.resolve("snap.xlsx").toFile()));
        assertTrue(ex.getMessage().contains("没有库存快照数据"));
    }

    /** 有数据时必须把快照类与目标文件交给 Excel 写入器。 */
    @Test
    void exportSnapshot_writesThroughExcelWriter() throws Exception {
        when(runMapper.selectById("R1")).thenReturn(new WmsSyncRun().setRunNo("SB1"));
        when(snapshotMapper.selectCount(any())).thenReturn(3L);
        File target = tempDir.resolve("snap.xlsx").toFile();

        WmsSyncSnapshotExportService.SyncExportFile exported = service.exportSnapshot("R1", target);

        assertEquals(target, exported.file());
        assertTrue(exported.fileName().startsWith("待推送包_SB1_"));
        verify(excelWriter).write(any(), eq(WmsSyncSnapshot.class), any(), isNull(), eq(target));
    }

    /** 批次不存在时明确报错，不能生成一份空包让人误判。 */
    @Test
    void missingRun_rejected() {
        when(runMapper.selectById("NOPE")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.exportJson("NOPE", tempDir.resolve("x.json").toFile()));
        assertTrue(ex.getMessage().contains("对账批次不存在"));
    }
}
