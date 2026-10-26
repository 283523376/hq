package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 委外回写占位实现——必须明确失败，不得静默成功（spec 2026-10-26 §5.1）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeeSubcontractWriterStubTest {

    private final WmsKingdeeSubcontractWriterStub stub = new WmsKingdeeSubcontractWriterStub();

    @Test
    void writePickMtrlMustFailExplicitly() {
        WmsOrderSyncResult result = stub.writePickMtrl(new WmsOutboundOrder(), List.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("E6"), result.getErrorMessage());
    }

    @Test
    void writeReturnMtrlMustFailExplicitly() {
        WmsOrderSyncResult result = stub.writeReturnMtrl(new WmsOutboundOrder(), List.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("E6"), result.getErrorMessage());
    }
}
