package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsKingdeeCategorySyncService;
import org.jeecg.modules.wms.service.IWmsKingdeeWarehouseSyncService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 主数据同步 Job 单测——默认关闭不触发、开关+鉴权齐备才跑、异常不外抛（spec D6）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsMasterDataSyncJobTest {

    private IWmsKingdeeWarehouseSyncService warehouseSyncService;
    private IWmsKingdeeCategorySyncService categorySyncService;
    private WmsKingdeeProperties properties;
    private IWmsKingdeeAuthService authService;
    private WmsMasterDataSyncJob job;

    @BeforeEach
    void setUp() {
        warehouseSyncService = mock(IWmsKingdeeWarehouseSyncService.class);
        categorySyncService = mock(IWmsKingdeeCategorySyncService.class);
        properties = new WmsKingdeeProperties();
        authService = mock(IWmsKingdeeAuthService.class);
        job = new WmsMasterDataSyncJob(warehouseSyncService, categorySyncService, properties, authService);
    }

    @Test
    void shouldDoNothingWhenDisabledByDefault() {
        job.syncForTest();

        verify(warehouseSyncService, never()).syncStock();
        verify(categorySyncService, never()).syncCategory();
    }

    @Test
    void shouldDoNothingWhenKingdeeNotConfigured() {
        properties.getMasterData().setEnabled(true);
        when(authService.isConfigured()).thenReturn(false);

        job.syncForTest();

        verify(warehouseSyncService, never()).syncStock();
        verify(categorySyncService, never()).syncCategory();
    }

    @Test
    void shouldRunBothSyncsWhenEnabledAndConfigured() {
        properties.getMasterData().setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(warehouseSyncService.syncStock()).thenReturn(new WmsKingdeeStockSyncResultVO());
        when(categorySyncService.syncCategory()).thenReturn(new WmsKingdeeStockSyncResultVO());

        job.syncForTest();

        verify(warehouseSyncService).syncStock();
        verify(categorySyncService).syncCategory();
    }

    @Test
    void shouldSwallowSyncExceptions() {
        properties.getMasterData().setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(warehouseSyncService.syncStock()).thenThrow(new RuntimeException("金蝶超时"));

        assertDoesNotThrow(() -> job.syncForTest());
        // 仓库同步失败不应阻断分类同步
        verify(categorySyncService).syncCategory();
    }
}
