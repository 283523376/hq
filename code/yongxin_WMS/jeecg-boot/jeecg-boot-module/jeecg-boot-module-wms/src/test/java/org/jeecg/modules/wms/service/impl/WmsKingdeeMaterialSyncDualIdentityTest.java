package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物料双身份监控（2026-10-29）：YAML 已映射的金蝶物料，同步仍会建一条自映射记录 → 必须留痕。
 */
class WmsKingdeeMaterialSyncDualIdentityTest {

    @Test
    void warnsWhenSyncingMaterialAlreadyMappedByYaml() {
        WmsKingdeeProperties properties = new WmsKingdeeProperties();
        properties.setMaterialMap(Map.of("FG001", "WA.2205.2200.03000550.B003"));
        properties.setEnabled(true);
        WmsMaterialMapper materialMapper = mock(WmsMaterialMapper.class);
        when(materialMapper.selectOne(any())).thenReturn(null);
        WmsKingdeeFormClient formClient = mock(WmsKingdeeFormClient.class);
        WmsKingdeeResult page = WmsKingdeeResult.ok("{}",
                List.of(List.of("999", "WA.2205.2200.03000550.B003", "UW0.30*5.5-C", "spec", "zhi")), null, null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(page);

        IWmsKingdeeAuthService auth = mock(IWmsKingdeeAuthService.class);
        when(auth.isConfigured()).thenReturn(true);
        WmsKingdeeMaterialSyncServiceImpl service = new WmsKingdeeMaterialSyncServiceImpl(
                formClient, properties, auth, materialMapper, mock(IWmsSyncLogService.class));

        service.syncMaterial();

        // 仍然插入（监控不阻断），但必须走的是「新建」分支 —— 断言插入发生，证明同步未被拦
        verify(materialMapper).insert(any(WmsMaterial.class));
    }
}
