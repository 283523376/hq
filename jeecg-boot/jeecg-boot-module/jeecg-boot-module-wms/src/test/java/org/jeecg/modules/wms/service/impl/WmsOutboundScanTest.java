package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeePpbomReader;
import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;
import org.jeecg.modules.wms.vo.WmsOutboundScanVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: CK-001 扫码：工单校验、PPBOM 需求来源标记、手工兜底。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundScanTest {

    private WmsWorkOrderMapper workOrderMapper;
    private IWmsKingdeePpbomReader ppbomReader;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        ppbomReader = mock(IWmsKingdeePpbomReader.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mock(WmsOutboundOrderMapper.class));
        WmsTestSupport.inject(service, "detailMapper", mock(WmsOutboundDetailMapper.class));
        WmsTestSupport.inject(service, "workOrderMapper", workOrderMapper);
        WmsTestSupport.inject(service, "ppbomReader", ppbomReader);

        WmsWorkOrder workOrder = new WmsWorkOrder()
                .setWorkOrderNo("FG001173")
                .setMaterialCode("FG001")
                .setMaterialName("成品电路板")
                .setProductType("半成品")
                .setPlanQty(new BigDecimal("10000"))
                .setBatchNo("B1")
                .setWorkOrderStatus("生产中");
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder);
    }

    @Test
    void scan_unknownWorkOrder_throws() {
        when(workOrderMapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scanPick("NOPE"));
        assertEquals("工单号无效", e.getMessage());
    }

    @Test
    void scan_withPpbomDetails_marksSource() {
        when(ppbomReader.read("FG001173")).thenReturn(List.of(requirement("PBC001", "70")));

        WmsOutboundScanVO vo = service.scanPick("FG001173");

        assertEquals("FG001173", vo.getWorkOrderNo());
        assertEquals("金蝶用料清单", vo.getRequirementSource());
        assertEquals(1, vo.getDetails().size());
        assertEquals("PBC001", vo.getDetails().get(0).getMaterialCode());
        assertFalse(vo.getDetails().get(0).getMatched() == null);
    }

    @Test
    void scan_withoutPpbom_marksManual() {
        when(ppbomReader.read(anyString())).thenReturn(List.of());

        WmsOutboundScanVO vo = service.scanPick("FG001173");

        assertEquals("手工", vo.getRequirementSource());
        assertTrue(vo.getDetails().isEmpty());
        assertTrue(vo.getWarning() != null && vo.getWarning().contains("手工录入"));
    }

    private WmsOutboundRequirementVO requirement(String code, String qty) {
        WmsOutboundRequirementVO vo = new WmsOutboundRequirementVO();
        vo.setMaterialCode(code);
        vo.setRequiredQty(new BigDecimal(qty));
        vo.setMatched(true);
        return vo;
    }
}
