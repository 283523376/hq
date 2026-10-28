package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: PRD_PPBOM 需求读取：数量扣减、非正数跳过、物料映射、禁用与失败兜底。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeePpbomReaderImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsMaterialCodeResolver materialResolver;
    private WmsKingdeePpbomReaderImpl reader;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setMaterialMap(Map.of("PBC001", "WA.123"));
        materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.reverseKingdeeCode(anyString())).thenAnswer(inv -> {
            for (Map.Entry<String, String> e : properties.getMaterialMap().entrySet()) {
                if (e.getValue().equals(inv.getArgument(0))) {
                    return e.getKey();
                }
            }
            return null;
        });
        reader = new WmsKingdeePpbomReaderImpl(formClient, properties, materialResolver);
    }

    @Test
    void read_subtractsPickedQty_andSkipsNonPositive() {
        when(formClient.executeBillQuery(eq("PRD_PPBOM"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        List.of("WA.123", "未加工集成电路板", 100, 100, 30, "02.011", "", "zhi", 261486),
                        List.of("WA.123", "未加工集成电路板", 5, 5, 5, "02.011", "", "zhi", 261487)
                ), null, null));

        List<WmsOutboundRequirementVO> list = reader.read("FG001173");

        assertEquals(1, list.size());
        assertEquals("PBC001", list.get(0).getMaterialCode());
        assertEquals(0, list.get(0).getRequiredQty().compareTo(new BigDecimal("70")));
        assertEquals("261486", list.get(0).getSourceEntryId());
        assertTrue(list.get(0).getMatched());
    }

    @Test
    void read_kingdeeDisabled_returnsEmpty() {
        properties.setEnabled(false);

        assertTrue(reader.read("FG001173").isEmpty());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void read_queryFails_returnsEmptyAndDoesNotThrow() {
        when(formClient.executeBillQuery(eq("PRD_PPBOM"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("元数据错误"));

        assertTrue(reader.read("FG001173").isEmpty());
    }

    @Test
    void read_unmappedMaterial_keepsKingdeeCodeAndMarksUnmatched() {
        properties.setMaterialMap(Map.of());
        when(formClient.executeBillQuery(eq("PRD_PPBOM"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        List.of("WA.999", "未知物料", 10, 10, 0, "02.011", "L1", "zhi", 1)
                ), null, null));

        List<WmsOutboundRequirementVO> list = reader.read("FG001173");

        assertEquals(1, list.size());
        assertEquals("WA.999", list.get(0).getMaterialCode());
        assertFalse(list.get(0).getMatched());
        assertEquals("L1", list.get(0).getBatchNo());
    }
}
