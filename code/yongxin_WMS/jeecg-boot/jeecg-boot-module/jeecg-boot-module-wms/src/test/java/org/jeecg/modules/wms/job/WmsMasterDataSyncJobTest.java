package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsKingdeeCategorySyncService;
import org.jeecg.modules.wms.service.IWmsKingdeeMaterialSyncService;
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
    private IWmsKingdeeMaterialSyncService materialSyncService;
    private WmsKingdeeProperties properties;
    private IWmsKingdeeAuthService authService;
    private WmsMasterDataSyncJob job;

    @BeforeEach
    void setUp() {
        warehouseSyncService = mock(IWmsKingdeeWarehouseSyncService.class);
        categorySyncService = mock(IWmsKingdeeCategorySyncService.class);
        materialSyncService = mock(IWmsKingdeeMaterialSyncService.class);
        properties = new WmsKingdeeProperties();
        authService = mock(IWmsKingdeeAuthService.class);
        job = new WmsMasterDataSyncJob(warehouseSyncService, categorySyncService, materialSyncService, properties, authService);
    }

    @Test
    void shouldDoNothingWhenDisabledByDefault() {
        job.syncForTest();

        verify(warehouseSyncService, never()).syncStock();
        verify(categorySyncService, never()).syncCategory();
        verify(categorySyncService, never()).syncMaterialGroups();
    }

    @Test
    void shouldDoNothingWhenGlobalKingdeeSwitchOff() {
        properties.getMasterData().setEnabled(true);
        // 全局总闸关闭：master-data 单独开着也不得打金蝶
        properties.setEnabled(false);
        when(authService.isConfigured()).thenReturn(true);

        job.syncForTest();

        verify(warehouseSyncService, never()).syncStock();
        verify(categorySyncService, never()).syncCategory();
        verify(categorySyncService, never()).syncMaterialGroups();
    }

    @Test
    void shouldDoNothingWhenKingdeeNotConfigured() {
        properties.getMasterData().setEnabled(true);
        when(authService.isConfigured()).thenReturn(false);

        job.syncForTest();

        verify(warehouseSyncService, never()).syncStock();
        verify(categorySyncService, never()).syncCategory();
        verify(categorySyncService, never()).syncMaterialGroups();
    }

    @Test
    void shouldRunBothSyncsWhenEnabledAndConfigured() {
        properties.getMasterData().setEnabled(true);
        properties.setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(warehouseSyncService.syncStock()).thenReturn(new WmsKingdeeStockSyncResultVO());
        when(categorySyncService.syncCategory()).thenReturn(new WmsKingdeeStockSyncResultVO());
        when(categorySyncService.syncMaterialGroups()).thenReturn(new WmsKingdeeStockSyncResultVO());

        job.syncForTest();

        verify(warehouseSyncService).syncStock();
        verify(categorySyncService).syncMaterialGroups();
        verify(categorySyncService).syncCategory();
    }

    /** QA M4：分组同步必须排在分类（代表分组回写）之前——先建映射再回写，顺序不能被调换。 */
    @Test
    void shouldSyncMaterialGroupsBeforeCategory() {
        properties.getMasterData().setEnabled(true);
        properties.setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(warehouseSyncService.syncStock()).thenReturn(new WmsKingdeeStockSyncResultVO());
        when(categorySyncService.syncMaterialGroups()).thenReturn(new WmsKingdeeStockSyncResultVO());
        when(categorySyncService.syncCategory()).thenReturn(new WmsKingdeeStockSyncResultVO());

        job.syncForTest();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(categorySyncService);
        inOrder.verify(categorySyncService).syncMaterialGroups();
        inOrder.verify(categorySyncService).syncCategory();
    }

    /**
     * 2026-11-12 R2：物料分组同步必须随任务跑（否则合并后新分组不会补分类/映射），
     * 且它失败不能拖垮其余同步。
     */
    @Test
    void shouldKeepOtherSyncsRunningWhenMaterialGroupsFails() {
        properties.getMasterData().setEnabled(true);
        properties.setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(categorySyncService.syncMaterialGroups()).thenThrow(new RuntimeException("分组同步失败"));
        when(categorySyncService.syncCategory()).thenReturn(new WmsKingdeeStockSyncResultVO());

        assertDoesNotThrow(() -> job.syncForTest());

        verify(categorySyncService).syncMaterialGroups();
        verify(categorySyncService).syncCategory();
    }

    @Test
    void shouldSwallowSyncExceptions() {
        properties.getMasterData().setEnabled(true);
        properties.setEnabled(true);
        when(authService.isConfigured()).thenReturn(true);
        when(warehouseSyncService.syncStock()).thenThrow(new RuntimeException("金蝶超时"));

        assertDoesNotThrow(() -> job.syncForTest());
        // 仓库同步失败不应阻断分类同步
        verify(categorySyncService).syncCategory();
    }
}
