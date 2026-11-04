package org.jeecg.modules.wms.audit;

import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeInboundWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeeOutstockWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReceiveWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReturnWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 回写 ERP 通道的审计采集（XT-002）。
 *
 * <p>回写是「错数据」的另一个入口：金蝶那边落了单，WMS 这边却以为失败了。
 * 所以成功与失败、包括信用额度拦截这类业务拒绝，都必须留痕。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class SyncAuditRecordingTest {

    private IWmsAuditLogService auditService;
    private WmsErpWriteAuditAspect aspect;

    @BeforeEach
    void setUp() {
        auditService = mock(IWmsAuditLogService.class);
        when(auditService.snapshot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(String[].class)))
                .thenAnswer(inv -> "{\"snapshotOf\":\"" + inv.getArgument(0).getClass().getSimpleName() + "\"}");
        aspect = new WmsErpWriteAuditAspect(auditService);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private WmsAuditEvent capture() {
        ArgumentCaptor<WmsAuditEvent> captor = ArgumentCaptor.forClass(WmsAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    private static WmsProductionInbound inbound() {
        return new WmsProductionInbound().setInboundNo("RK001").setWorkOrderNo("MO1").setStatus("待同步");
    }

    private static WmsOutboundOrder outbound() {
        return new WmsOutboundOrder().setOutboundNo("CK001").setStatus("待同步");
    }

    /** 入库回写成功：记下 WMS 单号、通道、成功结果与回写后的 ERP 单号。 */
    @Test
    void inboundWriteback_recordsSuccess() {
        FakeInboundWriter writer = new FakeInboundWriter(WmsOrderSyncResult.ok(), "SCRK001");
        ((IWmsKingdeeInboundWriter) proxy(writer)).write(inbound());

        WmsAuditEvent event = capture();
        assertEquals("入库单", event.getBizType());
        assertEquals("RK001", event.getBizNo());
        assertEquals("回写ERP", event.getAction());
        assertEquals("回写ERP", event.getChannel());
        assertEquals("成功", event.getResult());
        assertNotNull(event.getAfterValue(), "回写后的 ERP 单号要能从 after_value 看出来");
    }

    /** 回写失败要把金蝶的原始错误带出来，否则事后只能看到「失败了」。 */
    @Test
    void inboundWriteback_recordsFailureWithErpError() {
        FakeInboundWriter writer = new FakeInboundWriter(
                WmsOrderSyncResult.fail("保存失败：必填项 FBomId 不能为空"), null);
        ((IWmsKingdeeInboundWriter) proxy(writer)).write(inbound());

        WmsAuditEvent event = capture();
        assertEquals("RK001", event.getBizNo());
        assertEquals("失败", event.getResult());
        assertTrue(event.getErrorMsg().contains("FBomId"), "实际：" + event.getErrorMsg());
    }

    /** 信用额度拦截是业务闸门，不是系统异常——同样要能追溯是谁的单被拦了。 */
    @Test
    void outstockWriteback_recordsCreditReject() {
        FakeOutstockWriter writer = new FakeOutstockWriter(
                WmsOrderSyncResult.fail("提交失败：客户信用额度不足"));
        ((IWmsKingdeeOutstockWriter) proxy(writer)).write(outbound(), Collections.emptyList());

        WmsAuditEvent event = capture();
        assertEquals("出库单", event.getBizType());
        assertEquals("CK001", event.getBizNo());
        assertEquals("失败", event.getResult());
        assertTrue(event.getErrorMsg().contains("信用额度"), "实际：" + event.getErrorMsg());
    }

    /** 回写器自己抛异常：审计仍记失败，异常照旧向上抛。 */
    @Test
    void writebackThrows_recordsFailureAndRethrows() {
        FakeInboundWriter writer = new FakeInboundWriter(null, null);
        IWmsKingdeeInboundWriter proxy = proxy(writer);

        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> proxy.write(inbound()));

        assertEquals("boom", ex.getMessage());
        assertEquals("失败", capture().getResult());
    }

    /**
     * V2.4 新回写器返回 {@link WmsKingdeeResult}（不是 WmsOrderSyncResult）：
     * 采购收料回写同样必须留审计行，并把 ERP 单号带进 after_value。
     */
    @Test
    void kingdeeResultInboundWriteback_recordsAuditWithErpNumber() {
        WmsInboundOrder order = new WmsInboundOrder().setOrderNo("SL001").setSyncStatus("待同步");
        FakePurchaseReceiveWriter writer = new FakePurchaseReceiveWriter(
                WmsKingdeeResult.ok("{}", null, "CGRK001", "erpId1"));

        ((IWmsKingdeePurchaseReceiveWriter) proxy(writer)).write(order);

        WmsAuditEvent event = capture();
        assertEquals("入库单", event.getBizType());
        assertEquals("SL001", event.getBizNo());
        assertEquals("回写ERP", event.getAction());
        assertEquals("回写ERP", event.getChannel());
        assertEquals("成功", event.getResult());
        assertTrue(String.valueOf(event.getAfterValue()).contains("CGRK001"),
                "ERP 单号必须能从 after_value 看出来：" + event.getAfterValue());
    }

    /** WmsKingdeeResult 失败：金蝶原始错误必须落审计，不能只剩一个「失败」。 */
    @Test
    void kingdeeResultInboundWriteback_recordsFailureWithErpError() {
        WmsInboundOrder order = new WmsInboundOrder().setOrderNo("SL002");
        FakePurchaseReceiveWriter writer = new FakePurchaseReceiveWriter(
                WmsKingdeeResult.fail("保存失败：必填项 FSupplierId 不能为空"));

        ((IWmsKingdeePurchaseReceiveWriter) proxy(writer)).write(order);

        WmsAuditEvent event = capture();
        assertEquals("SL002", event.getBizNo());
        assertEquals("失败", event.getResult());
        assertTrue(event.getErrorMsg().contains("FSupplierId"), "实际：" + event.getErrorMsg());
    }

    /** 采购退货回写的业务类型是不良品记录，单号取来源单号。 */
    @Test
    void purchaseReturnWriteback_recordsDefectRecordBiz() {
        WmsDefectRecord record = new WmsDefectRecord()
                .setSourceBillNo("SL001").setDisposalType("RETURN");
        FakePurchaseReturnWriter writer = new FakePurchaseReturnWriter(
                WmsKingdeeResult.ok("{}", null, "TH001", "erpId2"));

        ((IWmsKingdeePurchaseReturnWriter) proxy(writer)).write(record, "C001", "WH01");

        WmsAuditEvent event = capture();
        assertEquals("不良品记录", event.getBizType());
        assertEquals("SL001", event.getBizNo());
        assertEquals("成功", event.getResult());
        assertTrue(String.valueOf(event.getAfterValue()).contains("TH001"), String.valueOf(event.getAfterValue()));
    }

    /** 审计服务写库失败不影响回写结果。 */
    @Test
    void auditFailure_doesNotAffectWriteback() {
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditService).record(org.mockito.ArgumentMatchers.any(WmsAuditEvent.class));
        FakeInboundWriter writer = new FakeInboundWriter(WmsOrderSyncResult.ok(), "SCRK001");

        WmsOrderSyncResult result = ((IWmsKingdeeInboundWriter) proxy(writer)).write(inbound());

        assertTrue(result.isSuccess());
    }

    static class FakeInboundWriter implements IWmsKingdeeInboundWriter {
        private final WmsOrderSyncResult next;
        private final String erpBillNo;

        FakeInboundWriter(WmsOrderSyncResult next, String erpBillNo) {
            this.next = next;
            this.erpBillNo = erpBillNo;
        }

        @Override
        public WmsOrderSyncResult write(WmsProductionInbound inbound) {
            if (next == null) {
                throw new RuntimeException("boom");
            }
            if (erpBillNo != null) {
                inbound.setErpBillNo(erpBillNo);
            }
            return next;
        }
    }

    static class FakePurchaseReceiveWriter implements IWmsKingdeePurchaseReceiveWriter {
        private final WmsKingdeeResult next;

        FakePurchaseReceiveWriter(WmsKingdeeResult next) {
            this.next = next;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public WmsKingdeeResult write(WmsInboundOrder order) {
            return next;
        }
    }

    static class FakePurchaseReturnWriter implements IWmsKingdeePurchaseReturnWriter {
        private final WmsKingdeeResult next;

        FakePurchaseReturnWriter(WmsKingdeeResult next) {
            this.next = next;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public WmsKingdeeResult write(WmsDefectRecord record, String customerNumber, String warehouseCode) {
            return next;
        }

        @Override
        public boolean existsByReference(String ref) {
            return false;
        }
    }

    static class FakeOutstockWriter implements IWmsKingdeeOutstockWriter {
        private final WmsOrderSyncResult next;

        FakeOutstockWriter(WmsOrderSyncResult next) {
            this.next = next;
        }

        @Override
        public WmsOrderSyncResult write(WmsOutboundOrder order, List<org.jeecg.modules.wms.entity.WmsOutboundDetail> details) {
            return next;
        }
    }
}
