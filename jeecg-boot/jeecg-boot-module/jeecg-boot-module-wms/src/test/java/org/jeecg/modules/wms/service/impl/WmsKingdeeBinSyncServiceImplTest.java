package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsKingdeeBin;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsKingdeeBinMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 金蝶仓位属性同步单测：值集解析、幂等 upsert、金蝶停用不删除、开关。
 */
class WmsKingdeeBinSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeBinMapper binMapper;
    private WmsLocationMapper locationMapper;
    private WmsInventoryMapper inventoryMapper;
    private IWmsSyncLogService syncLogService;
    private WmsKingdeeBinSyncServiceImpl service;

    private static final String VIEW_JSON = "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Result\":{"
            + "\"Number\":\"001\",\"FLEXVALUESENTRY\":["
            // 金蝶 View 的 Name 是 [{Key,Value}] 数组（不是按语言的 Map）——真机形态
            + "{\"Number\":\"01\",\"Name\":[{\"Key\":1054,\"Value\":\"A\"},{\"Key\":2052,\"Value\":\"A\"}],"
            + "\"Id\":100001,\"Forbid\":false},"
            + "{\"Number\":\"02\",\"Name\":[{\"Key\":2052,\"Value\":\"B\"}],\"Id\":100002,\"Forbid\":true}]}}}";

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setDefaultOrgNumber("100");
        properties.getBinOrgNumbers().add("100");
        binMapper = mock(WmsKingdeeBinMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsKingdeeBinSyncServiceImpl(formClient, properties, binMapper,
                locationMapper, inventoryMapper, syncLogService);

        when(formClient.executeBillQuery(eq("BD_FLEXVALUES"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(Arrays.asList("001", "100")), null, null));
        when(formClient.view("BD_FLEXVALUES", "001"))
                .thenReturn(WmsKingdeeResult.ok(VIEW_JSON, null, null, null));
        when(binMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void sync_createsBinsFromValueSet() {
        when(binMapper.selectOne(any())).thenReturn(null);

        WmsKingdeeStockSyncResultVO vo = service.syncBins();

        assertEquals(2, vo.getFetched());
        assertEquals(2, vo.getCreated());
        ArgumentCaptor<WmsKingdeeBin> captor = ArgumentCaptor.forClass(WmsKingdeeBin.class);
        verify(binMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<WmsKingdeeBin> saved = captor.getAllValues();
        assertEquals("001", saved.get(0).getFlexNumber());
        assertEquals("01", saved.get(0).getBinNumber());
        assertEquals("A", saved.get(0).getBinName());
        assertEquals(100001L, saved.get(0).getFlexId());
        assertEquals("正常", saved.get(0).getSyncStatus());
        // 金蝶侧 Forbid=true → 标「金蝶已停用」
        assertEquals("金蝶已停用", saved.get(1).getSyncStatus());
    }

    @Test
    void sync_updatesExistingInsteadOfDuplicating() {
        WmsKingdeeBin existing = new WmsKingdeeBin();
        existing.setId("b1");
        existing.setFlexNumber("001");
        existing.setBinNumber("01");
        existing.setSyncStatus("正常");
        when(binMapper.selectOne(any())).thenReturn(existing);

        WmsKingdeeStockSyncResultVO vo = service.syncBins();

        assertEquals(0, vo.getCreated());
        verify(binMapper, never()).insert(any(WmsKingdeeBin.class));
        verify(binMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(WmsKingdeeBin.class));
    }

    @Test
    void sync_marksAbsentAsDisabled_notDeleted() {
        WmsKingdeeBin stale = new WmsKingdeeBin();
        stale.setId("old1");
        stale.setOrgNumber("100");
        stale.setFlexNumber("999");
        stale.setBinNumber("X");
        stale.setSyncStatus("正常");
        when(binMapper.selectOne(any())).thenReturn(null);
        when(binMapper.selectList(any())).thenReturn(List.of(stale));

        WmsKingdeeStockSyncResultVO vo = service.syncBins();

        assertTrue(vo.getMessage().contains("标记停用"));
        // 绝不物理删除
        verify(binMapper, never()).deleteById(anyString());
        verify(binMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(WmsKingdeeBin.class));
    }

    @Test
    void sync_skipsWhenKingdeeDisabled() {
        properties.setEnabled(false);
        WmsKingdeeStockSyncResultVO vo = service.syncBins();
        assertTrue(vo.getMessage().contains("跳过"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }
}
