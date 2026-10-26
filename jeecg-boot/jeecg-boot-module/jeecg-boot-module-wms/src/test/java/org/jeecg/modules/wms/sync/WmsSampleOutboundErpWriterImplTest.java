package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @Description: 样品回写——未配置与形态未定都必须明确失败，且不得发金蝶请求（spec §5.2 / E5）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsSampleOutboundErpWriterImplTest {

    private WmsKingdeeProperties properties;
    private WmsKingdeeFormClient formClient;
    private WmsSampleOutboundErpWriterImpl writer;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        formClient = mock(WmsKingdeeFormClient.class);
        writer = new WmsSampleOutboundErpWriterImpl(properties, formClient);
    }

    @Test
    void shouldFailWhenStockCodeNotConfigured() {
        WmsOrderSyncResult result = writer.write(new WmsOutboundOrder(), List.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("E5"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void shouldStillFailWhenStockCodeConfiguredButFormUndecided() {
        properties.getSample().setStockCode("YF.01");

        WmsOrderSyncResult result = writer.write(new WmsOutboundOrder(), List.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("形态"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }
}
