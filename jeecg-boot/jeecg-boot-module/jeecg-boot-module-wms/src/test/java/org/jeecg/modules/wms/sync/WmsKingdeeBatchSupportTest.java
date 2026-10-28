package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批号保障单测：缺失自动创建（默认开）、不可用按 auto-repair 开关处理（默认关）。
 */
class WmsKingdeeBatchSupportTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeBatchSupport support;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        support = new WmsKingdeeBatchSupport(formClient, properties);
        when(formClient.save(eq("BD_BatchMainFile"), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "B1", "id1"));
    }

    private void stubQuery(List<List<Object>> rows) {
        when(formClient.executeBillQuery(eq("BD_BatchMainFile"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensure_createsMissingLotAsUsable() {
        stubQuery(List.of());

        assertNull(support.ensureUsable("WA.1", "B1", "101"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("BD_BatchMainFile"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertEquals("B1", model.get("FNumber"));
        assertEquals("WA.1", ((Map<?, ?>) model.get("FMaterialId")).get("FNumber"));
        assertEquals("101", ((Map<?, ?>) model.get("FUseOrgId")).get("FNumber"));
        // 关键：必须带可用状态，否则金蝶默认 T（不可用）
        assertEquals("2", model.get("FLotStatus"));
    }

    @Test
    void ensure_skipsCreateWhenDisabled() {
        properties.getBatch().setAutoCreateEnabled(false);
        stubQuery(List.of());

        String error = support.ensureUsable("WA.1", "B1", "101");
        assertNotNull(error);
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void ensure_passesWhenLotStatusIsNotT() {
        // 真机校准：1 与 2 都可用（领料成功用的是 1），只有 T 不可用
        stubQuery(List.of(List.of("B1", "WA.1", "1")));
        assertNull(support.ensureUsable("WA.1", "B1", "101"));
        stubQuery(List.of(List.of("B1", "WA.1", "2")));
        assertNull(support.ensureUsable("WA.1", "B1", "101"));
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void ensure_reportsUnusableLotByDefault() {
        stubQuery(List.of(List.of("B1", "WA.1", "T")));

        String error = support.ensureUsable("WA.1", "B1", "101");
        assertNotNull(error);
        assertEquals(true, error.contains("auto-repair-enabled"));
        // 默认不覆盖金蝶既有批号状态
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensure_repairsUnusableLotWhenEnabled() {
        properties.getBatch().setAutoRepairEnabled(true);
        stubQuery(List.of(List.of("B1", "WA.1", "T")));

        assertNull(support.ensureUsable("WA.1", "B1", "101"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("BD_BatchMainFile"), captor.capture());
        assertEquals("2", captor.getValue().get("FLotStatus"));
    }

    @Test
    void ensure_ignoresQueryFailure() {
        when(formClient.executeBillQuery(eq("BD_BatchMainFile"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("超时"));
        assertNull(support.ensureUsable("WA.1", "B1", "101"));
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void ensure_noopWhenLotBlank() {
        assertNull(support.ensureUsable("WA.1", "", "101"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }
}
