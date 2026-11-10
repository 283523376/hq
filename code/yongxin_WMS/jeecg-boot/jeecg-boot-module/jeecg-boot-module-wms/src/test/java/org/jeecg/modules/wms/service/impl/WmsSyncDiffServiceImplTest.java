package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 差异清单服务单测（分页筛选 / 状态机 / 发起盘点）。
 *
 * <p>全部依赖 mock：差异 mapper、库存 mapper、批次 mapper、盘点服务、同步日志。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncDiffServiceImplTest {

    private static final String DIFF_ID = "DIFF1";
    private static final String RUN_ID = "RUN1";
    private static final String RUN_NO = "SB20261103120000001";
    private static final String OPERATOR = "zhangsan";

    /** 与 brief 逐字一致，测试断言错误文案，避免实现悄悄改成别的口径。 */
    private static final String NO_STOCK_MESSAGE = "WMS 无账面库存，无法盘点；请走生产入库或其他入库修正";

    private WmsSyncDiffMapper diffMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsSyncRunMapper runMapper;
    private IWmsStocktakeService stocktakeService;
    private IWmsSyncLogService syncLogService;
    private WmsSyncDiffServiceImpl service;

    /** LambdaUpdateWrapper 解析列名要靠 TableInfo，纯单测环境里手动注册一次。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WmsSyncDiff.class);
    }

    @BeforeEach
    void setUp() {
        diffMapper = mock(WmsSyncDiffMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        runMapper = mock(WmsSyncRunMapper.class);
        stocktakeService = mock(IWmsStocktakeService.class);
        syncLogService = mock(IWmsSyncLogService.class);

        service = new WmsSyncDiffServiceImpl(inventoryMapper, runMapper, stocktakeService, syncLogService);
        WmsTestSupport.inject(service, "baseMapper", diffMapper);
    }

    // ================================================================ handle

    @Test
    void handle_pendingToChecked_backfillsAndLogs() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));
        when(runMapper.selectById(RUN_ID)).thenReturn(run());

        service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_CHECKED, "  已核实金蝶多账  ", OPERATOR);

        ArgumentCaptor<WmsSyncDiff> cap = ArgumentCaptor.forClass(WmsSyncDiff.class);
        verify(diffMapper).updateById(cap.capture());
        WmsSyncDiff updated = cap.getValue();
        assertEquals(WmsConstants.DIFF_STATUS_CHECKED, updated.getStatus());
        assertEquals(OPERATOR, updated.getHandleBy());
        assertNotNull(updated.getHandleTime());
        assertEquals("已核实金蝶多账", updated.getHandleRemark(), "备注必须 trim 后落库");

        verify(syncLogService, times(1)).logSync(
                anyString(), eq(WmsConstants.SYNC_LOG_TYPE_RECONCILE), eq(RUN_NO),
                eq(WmsConstants.SYNC_LOG_TARGET_KINGDEE), eq(true), anyInt(),
                any(), any(), any(), anyLong());
    }

    @Test
    void handle_pendingToIgnored_allowed() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));
        when(runMapper.selectById(RUN_ID)).thenReturn(run());

        service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_IGNORED, "忽略", OPERATOR);

        ArgumentCaptor<WmsSyncDiff> cap = ArgumentCaptor.forClass(WmsSyncDiff.class);
        verify(diffMapper).updateById(cap.capture());
        assertEquals(WmsConstants.DIFF_STATUS_IGNORED, cap.getValue().getStatus());
    }

    @Test
    void handle_rejectsIllegalTransition() {
        // 已处理过的行不能再处理（状态机只有「待核对」是起点）
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_CHECKED));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_IGNORED, "再改一次", OPERATOR));

        assertTrue(e.getMessage().contains("待核对"), "实际：" + e.getMessage());
        verify(diffMapper, never()).updateById(any(WmsSyncDiff.class));
        verify(syncLogService, never()).logSync(anyString(), anyString(), anyString(), anyString(),
                any(Boolean.class), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    void handle_rejectsTargetStatusOutsideWhitelist() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));

        assertThrows(RuntimeException.class,
                () -> service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_PENDING, "回到待核对", OPERATOR));

        verify(diffMapper, never()).updateById(any(WmsSyncDiff.class));
    }

    @Test
    void handle_requiresRemark() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_CHECKED, "   ", OPERATOR));

        assertEquals("处理备注必填", e.getMessage());
        verify(diffMapper, never()).updateById(any(WmsSyncDiff.class));
    }

    @Test
    void handle_missingDiff_rejected() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> service.handle(DIFF_ID, WmsConstants.DIFF_STATUS_CHECKED, "备注", OPERATOR));
    }

    // ================================================================ 发起盘点

    @Test
    void createStocktake_success_passesLocationIdsAndRemark() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));
        when(runMapper.selectById(RUN_ID)).thenReturn(run());
        when(inventoryMapper.selectLocationIdsForDiff("D.02", "M1", "L1"))
                .thenReturn(List.of("LOC2", "LOC1"));
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("ST1");
        order.setStocktakeNo("PD20261103001");
        when(stocktakeService.create(any(WmsStocktakeCreateDTO.class), eq(OPERATOR))).thenReturn(order);
        when(diffMapper.update(any(), any())).thenReturn(1);

        WmsStocktakeOrder result = service.createStocktake(DIFF_ID, OPERATOR);

        assertSame(order, result);

        ArgumentCaptor<WmsStocktakeCreateDTO> dtoCap = ArgumentCaptor.forClass(WmsStocktakeCreateDTO.class);
        verify(stocktakeService).create(dtoCap.capture(), eq(OPERATOR));
        WmsStocktakeCreateDTO dto = dtoCap.getValue();
        assertEquals(WmsConstants.STOCKTAKE_TYPE_PERIODIC, dto.getStocktakeType());
        assertEquals(List.of("LOC2", "LOC1"), dto.getLocationIds());
        assertEquals("SB-001 差异发起: " + RUN_NO, dto.getRemark(), "批次号必须写进备注（DTO 没有 scopeDesc）");

        // 回填必须是「条件更新」（WHERE stocktake_id IS NULL），不能用 updateById 裸写
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WmsSyncDiff>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(diffMapper).update(any(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("stocktake_id IS NULL"), "缺少并发守卫条件，实际：" + sql);
        String set = cap.getValue().getSqlSet();
        assertTrue(set.contains("stocktake_id") && set.contains("stocktake_no")
                && set.contains("handle_by") && set.contains("handle_time"), "实际：" + set);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("ST1"),
                "盘点单ID未进入更新参数：" + cap.getValue().getParamNameValuePairs());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("PD20261103001"),
                "盘点单号未进入更新参数：" + cap.getValue().getParamNameValuePairs());

        verify(syncLogService, times(1)).logSync(
                anyString(), eq(WmsConstants.SYNC_LOG_TYPE_RECONCILE), eq(RUN_NO),
                eq(WmsConstants.SYNC_LOG_TARGET_KINGDEE), eq(true), anyInt(),
                any(), any(), any(), anyLong());
    }

    @Test
    void createStocktake_rejectsErpOnlyDiff() {
        WmsSyncDiff erpOnly = diff(WmsConstants.DIFF_STATUS_PENDING);
        erpOnly.setDiffType(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY).setWmsQty(null);
        when(diffMapper.selectById(DIFF_ID)).thenReturn(erpOnly);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertEquals(NO_STOCK_MESSAGE, e.getMessage());
        verify(stocktakeService, never()).create(any(), anyString());
        verify(inventoryMapper, never()).selectLocationIdsForDiff(anyString(), anyString(), anyString());
    }

    @Test
    void createStocktake_rejectsZeroWmsQty() {
        WmsSyncDiff zero = diff(WmsConstants.DIFF_STATUS_PENDING);
        zero.setDiffType(WmsConstants.DIFF_TYPE_LESS).setWmsQty(BigDecimal.ZERO);
        when(diffMapper.selectById(DIFF_ID)).thenReturn(zero);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertEquals(NO_STOCK_MESSAGE, e.getMessage());
        verify(stocktakeService, never()).create(any(), anyString());
    }

    @Test
    void createStocktake_rejectsWhenNoLocationsResolved() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));
        when(inventoryMapper.selectLocationIdsForDiff("D.02", "M1", "L1")).thenReturn(List.of());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertEquals(NO_STOCK_MESSAGE, e.getMessage(),
                "WMS 有账但库存行已归零时必须同样拒绝，不能建空盘点单");
        verify(stocktakeService, never()).create(any(), anyString());
        verify(diffMapper, never()).update(any(), any());
    }

    @Test
    void createStocktake_rejectsAlreadyInitiated() {
        WmsSyncDiff handled = diff(WmsConstants.DIFF_STATUS_PENDING);
        handled.setStocktakeId("ST0").setStocktakeNo("PD20261102009");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(handled);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertEquals("该差异已发起盘点单 PD20261102009，请勿重复发起", e.getMessage());
        verify(stocktakeService, never()).create(any(), anyString());
        verify(diffMapper, never()).update(any(), any());
        verify(inventoryMapper, never()).selectLocationIdsForDiff(anyString(), anyString(), anyString());
    }

    @Test
    void createStocktake_rejectsNonPendingStatus() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_CHECKED));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertTrue(e.getMessage().contains("待核对"), "实际：" + e.getMessage());
        verify(stocktakeService, never()).create(any(), anyString());
    }

    @Test
    void createStocktake_rejectsMaterialWithoutKingdeeCode() {
        WmsSyncDiff diff = diff(WmsConstants.DIFF_STATUS_PENDING);
        diff.setMaterialCode("");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertEquals("物料未绑定金蝶编码，无法发起盘点，请先完成物料主数据同步", e.getMessage(),
                "物料未绑金蝶编码不能复用「WMS 无账面」文案");
        verify(stocktakeService, never()).create(any(), anyString());
        verify(inventoryMapper, never()).selectLocationIdsForDiff(anyString(), anyString(), anyString());
    }

    @Test
    void createStocktake_conditionalUpdateAffectedZero_rejected() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff(WmsConstants.DIFF_STATUS_PENDING));
        when(runMapper.selectById(RUN_ID)).thenReturn(run());
        when(inventoryMapper.selectLocationIdsForDiff("D.02", "M1", "L1")).thenReturn(List.of("LOC1"));
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("ST1");
        order.setStocktakeNo("PD20261103001");
        when(stocktakeService.create(any(WmsStocktakeCreateDTO.class), eq(OPERATOR))).thenReturn(order);
        // 并发窗口：另一个请求先写入了 stocktake_id，本请求的条件更新影响 0 行
        when(diffMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createStocktake(DIFF_ID, OPERATOR));

        assertTrue(e.getMessage().contains("请勿重复发起"), "实际：" + e.getMessage());
        verify(stocktakeService, times(1)).create(any(WmsStocktakeCreateDTO.class), eq(OPERATOR));
    }

    @Test
    void createStocktake_missingDiff_rejected() {
        when(diffMapper.selectById(DIFF_ID)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> service.createStocktake(DIFF_ID, OPERATOR));
        verify(stocktakeService, never()).create(any(), anyString());
    }

    // ================================================================ 分页

    @Test
    void pageDiffs_appliesFiltersAndClampsPageSize() {
        when(diffMapper.selectPage(any(), any())).thenReturn(new Page<WmsSyncDiff>());

        service.pageDiffs(0, 100000, RUN_ID, "D.02", "M1", WmsConstants.DIFF_STATUS_PENDING,
                WmsConstants.DIFF_TYPE_MORE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IPage<WmsSyncDiff>> pageCap = ArgumentCaptor.forClass(IPage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsSyncDiff>> wrapperCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(diffMapper).selectPage(pageCap.capture(), wrapperCap.capture());

        assertEquals(1, pageCap.getValue().getCurrent(), "页码至少为 1");
        assertEquals(200, pageCap.getValue().getSize(), "分页上限 200");

        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql.contains("run_id"), "实际：" + sql);
        assertTrue(sql.contains("warehouse_code"), "实际：" + sql);
        assertTrue(sql.contains("status"), "实际：" + sql);
        assertTrue(sql.contains("diff_type"), "实际：" + sql);
        assertTrue(sql.contains("LIKE"), "物料关键词必须模糊匹配，实际：" + sql);
        assertTrue(wrapperCap.getValue().getParamNameValuePairs().containsValue("%M1%")
                        || wrapperCap.getValue().getParamNameValuePairs().containsValue("M1"),
                "物料关键词未进入参数：" + wrapperCap.getValue().getParamNameValuePairs());
    }

    @Test
    void pageDiffs_omitsBlankFilters() {
        when(diffMapper.selectPage(any(), any())).thenReturn(new Page<WmsSyncDiff>());

        service.pageDiffs(1, 10, null, "  ", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsSyncDiff>> wrapperCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(diffMapper).selectPage(any(), wrapperCap.capture());
        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql == null || !sql.toUpperCase().contains("WHERE"),
                "空筛选不应产生 where 条件，实际：" + sql);
    }

    // ================================================================ 辅助

    private static WmsSyncDiff diff(String status) {
        WmsSyncDiff diff = new WmsSyncDiff();
        diff.setId(DIFF_ID);
        diff.setRunId(RUN_ID);
        diff.setWarehouseCode("D.02");
        diff.setMaterialCode("M1");
        diff.setMaterialName("铜带");
        diff.setBatchNo("L1");
        diff.setWmsQty(new BigDecimal("10"));
        diff.setErpQty(new BigDecimal("8"));
        diff.setDiffQty(new BigDecimal("2"));
        diff.setDiffType(WmsConstants.DIFF_TYPE_MORE);
        diff.setStatus(status);
        return diff;
    }

    private static WmsSyncRun run() {
        WmsSyncRun run = new WmsSyncRun();
        run.setId(RUN_ID);
        run.setRunNo(RUN_NO);
        return run;
    }
}
