package org.jeecg.modules.wms.constant;

import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @Description: 外协/样品出库常量与配置默认值（spec 2026-10-26 §5.1 §5.2）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsConstantsOutboundTypesTest {

    @Test
    void shouldExposeTwoNewOutboundTypesAndDraftStatus() {
        assertEquals("外协出库", WmsConstants.OUTBOUND_TYPE_SUBCONTRACT);
        assertEquals("样品出库", WmsConstants.OUTBOUND_TYPE_SAMPLE);
        assertEquals("草稿", WmsConstants.OUTBOUND_STATUS_DRAFT);
    }

    /** 新类型不得与既有两类重名，否则出库网关会按错误类型回写。 */
    @Test
    void newTypesMustDifferFromExistingOnes() {
        assertNotEquals(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_TYPE_SUBCONTRACT);
        assertNotEquals(WmsConstants.OUTBOUND_TYPE_SALES, WmsConstants.OUTBOUND_TYPE_SAMPLE);
    }

    @Test
    void subcontractAndSampleConfigDefaultToUnconfigured() {
        WmsKingdeeProperties properties = new WmsKingdeeProperties();

        WmsKingdeeProperties.Subcontract subcontract = properties.getSubcontract();
        assertNotNull(subcontract);
        assertNull(subcontract.getOrgFilter());
        assertNull(subcontract.getPickStatusDone());

        WmsKingdeeProperties.Sample sample = properties.getSample();
        assertNotNull(sample);
        assertNull(sample.getStockCode());
    }
}
