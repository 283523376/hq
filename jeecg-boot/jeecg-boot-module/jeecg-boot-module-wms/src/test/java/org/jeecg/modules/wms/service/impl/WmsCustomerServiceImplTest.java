package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * @Description: 客户档案（受托方，V2.4）业务单测——编码查重 / 删除守卫 / 下拉。
 *
 * <p>受托方口径：受托方与金蝶保持一致（金蝶里受托方是客户 BD_Customer），
 * 故客户档案是独立主数据，删除守卫盯 {@code wms_inventory.consignor_id}
 * 与 {@code wms_inbound_order.consignor_id}。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsCustomerServiceImplTest {

    // ============================================================ 新增 / 编辑

    /** 新增：清空 id、复位删除时间戳、状态默认启用、文本去空格。 */
    @Test
    void addCustomer_shouldApplyDefaultsForNewEntity() {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsCustomer customer = new WmsCustomer()
                .setCustomerCode("  C001  ").setCustomerName("  受托客户一  ")
                .setContact("  张三  ").setPhone("  13800000000  ");
        customer.setId("客户端伪造ID");
        customer.setDeletedAt(1700000000000L);

        service.addCustomer(customer);

        assertNull(customer.getId(), "新增必须清空 id，交由 MyBatis-Plus 生成");
        assertEquals(0L, customer.getDeletedAt().longValue(), "新增必须把删除时间戳复位为 0（未删除）");
        assertEquals(WmsConstants.STATUS_ENABLE, customer.getStatus());
        assertEquals("C001", customer.getCustomerCode());
        assertEquals("受托客户一", customer.getCustomerName());
        assertEquals("张三", customer.getContact());
        assertEquals("13800000000", customer.getPhone());
    }

    /** 编码/名称缺失必须拒绝，不能落脏数据。 */
    @Test
    void addCustomer_shouldRejectBlankCodeAndName() {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());

        RuntimeException code = assertThrows(RuntimeException.class, () -> service.addCustomer(
                new WmsCustomer().setCustomerCode("   ").setCustomerName("受托客户一")));
        assertEquals("客户编码不能为空", code.getMessage());

        RuntimeException name = assertThrows(RuntimeException.class, () -> service.addCustomer(
                new WmsCustomer().setCustomerCode("C001").setCustomerName("   ")));
        assertEquals("客户名称不能为空", name.getMessage());
        verify(service, never()).save(any());
    }

    /** 编码重复必须拒绝。 */
    @Test
    void addCustomer_shouldRejectDuplicatedCode() {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addCustomer(
                new WmsCustomer().setCustomerCode("C001").setCustomerName("受托客户一")));

        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    /** 编辑：trim 后按自身 id 排除查重并更新。 */
    @Test
    void editCustomer_shouldTrimAndUpdate() {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsCustomer customer = new WmsCustomer()
                .setCustomerCode("  C001  ").setCustomerName("  受托客户一（改名）  ")
                .setContact("  李四  ");
        customer.setId("1");

        service.editCustomer(customer);

        assertEquals("C001", customer.getCustomerCode());
        assertEquals("受托客户一（改名）", customer.getCustomerName());
        assertEquals("李四", customer.getContact());
        verify(service).checkCodeExists("C001", "1");
        verify(service).updateById(customer);
    }

    /** 停用只需一条更新，状态置 0。 */
    @Test
    void disableCustomer_shouldSetStatusDisabled() {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        doReturn(true).when(service).updateById(any());

        service.disableCustomer("1");

        ArgumentCaptor<WmsCustomer> captor = ArgumentCaptor.forClass(WmsCustomer.class);
        verify(service).updateById(captor.capture());
        assertEquals("1", captor.getValue().getId());
        assertEquals(WmsConstants.STATUS_DISABLE, captor.getValue().getStatus());
    }

    // ============================================================ 删除守卫

    private WmsCustomerServiceImpl guardService(long inventoryCount, long orderCount,
                                               List<WmsInboundOrder> orders) {
        WmsCustomerServiceImpl service = spy(new WmsCustomerServiceImpl());
        WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
        WmsInboundOrderMapper orderMapper = mock(WmsInboundOrderMapper.class);
        WmsTestSupport.inject(service, "wmsInventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "wmsInboundOrderMapper", orderMapper);
        doReturn(inventoryCount).when(inventoryMapper).selectCount(any(Wrapper.class));
        doReturn(orderCount).when(orderMapper).selectCount(any(Wrapper.class));
        doReturn(orders).when(orderMapper).selectList(any(Wrapper.class));
        return service;
    }

    /** 有委托库存行 → 不许删，且文案逐字透出。 */
    @Test
    void removeCustomer_shouldRejectWhenConsignedInventoryExists() {
        WmsCustomerServiceImpl service = guardService(2L, 0L, Collections.emptyList());
        doReturn(true).when(service).removeById(any(String.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeCustomer("S1"));

        assertEquals("该客户存在受托库存或受托入库单，不允许删除", ex.getMessage());
        verify(service, never()).removeById(any(String.class));
    }

    /** 有受托入库单 → 不许删；预检要能报出单号样本。 */
    @Test
    void removeCustomer_shouldRejectWhenConsignedOrderExists() {
        WmsCustomerServiceImpl service = guardService(0L, 3L,
                Arrays.asList(new WmsInboundOrder().setOrderNo("SL001"),
                        new WmsInboundOrder().setOrderNo("SL002")));
        doReturn(true).when(service).removeById(any(String.class));

        WmsDeleteCheckVO check = service.checkDelete("S1");
        assertEquals(false, check.isDeletable());
        assertEquals("存在受托入库单", check.getBlockers().get(0).getMessage());
        assertEquals(3L, check.getBlockers().get(0).getCount());
        assertEquals(Arrays.asList("SL001", "SL002"), check.getBlockers().get(0).getSamples());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeCustomer("S1"));
        assertEquals("该客户存在受托库存或受托入库单，不允许删除", ex.getMessage());
    }

    /** 两类引用同时存在时一次报全（预检的价值就在于「一次说清」）。 */
    @Test
    void checkDelete_shouldReportBothBlockers() {
        WmsCustomerServiceImpl service = guardService(1L, 1L,
                List.of(new WmsInboundOrder().setOrderNo("SL001")));

        WmsDeleteCheckVO vo = service.checkDelete("S1");

        assertEquals(false, vo.isDeletable());
        assertEquals(2, vo.getBlockers().size());
        assertTrue(vo.getBlockers().stream().anyMatch(b -> "存在受托库存".equals(b.getMessage())));
        assertTrue(vo.getBlockers().stream().anyMatch(b -> "存在受托入库单".equals(b.getMessage())));
    }

    /** 无任何引用 → 正常删除。 */
    @Test
    void removeCustomer_shouldRemoveWhenNoReferences() {
        WmsCustomerServiceImpl service = guardService(0L, 0L, Collections.emptyList());
        doReturn(true).when(service).removeById("1");

        service.removeCustomer("1");

        verify(service).removeById("1");
    }

    /** 批量删除预检：列出不可删明细（编码 + 原因）。 */
    @Test
    void checkDeleteBatch_shouldListBlockedItems() {
        WmsCustomerServiceImpl service = guardService(1L, 0L, Collections.emptyList());
        doReturn(new WmsCustomer().setCustomerCode("C001")).when(service).getById("S1");

        WmsBatchDeleteCheckVO vo = service.checkDeleteBatch(List.of("S1"));

        assertEquals(false, vo.isDeletable());
        assertEquals(1, vo.getBlockedItems().size());
        assertEquals("C001", vo.getBlockedItems().get(0).getCode());
        assertTrue(vo.getBlockedItems().get(0).getReason().contains("存在受托库存"));
    }

    /** 查不到的 id 预检返回「可删」（与供应商同一口径）。 */
    @Test
    void checkDelete_unknownIdIsDeletable() {
        WmsCustomerServiceImpl service = guardService(0L, 0L, Collections.emptyList());

        assertEquals(true, service.checkDelete("not-exist").isDeletable());
    }
}
