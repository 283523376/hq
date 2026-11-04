package org.jeecg.modules.wms.service.impl;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: CK-001 领料动作单测：领取人取登录账号、设备校验、部分领料、取消与通知。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsPickActionTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsWorkOrderMapper workOrderMapper;
    private WmsMaterialMapper materialMapper;
    private WmsDeviceMapper deviceMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private IWmsOutboundTaskService taskService;
    private ISysBaseAPI sysBaseAPI;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        deviceMapper = mock(WmsDeviceMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        taskService = mock(IWmsOutboundTaskService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);

        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "workOrderMapper", workOrderMapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);
        WmsTestSupport.inject(service, "deviceMapper", deviceMapper);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "taskService", taskService);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);

        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenReturn(1);
        when(orderMapper.update(any(WmsOutboundOrder.class), any())).thenReturn(1);
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);
        when(allocationMapper.selectList(any())).thenReturn(List.of());

        WmsWorkOrder workOrder = new WmsWorkOrder()
                .setWorkOrderNo("WO1")
                .setMaterialCode("PBC001")
                .setMaterialName("未加工集成电路板")
                .setProductType("半成品")
                .setBatchNo("B1")
                .setWorkOrderStatus("生产中");
        workOrder.setCreateBy("planner1");
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder);

        WmsMaterial material = new WmsMaterial();
        material.setId("mat1");
        material.setMaterialCode("PBC001");
        material.setMaterialName("未加工集成电路板");
        material.setUnit("PCS");
        when(materialMapper.selectOne(any())).thenReturn(material);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.remove();
    }

    /** 绑定登录上下文：WmsUtils 从 Shiro principal 取账号/姓名。 */
    private void loginAs(String username, String realname) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRealname(realname);
        Subject subject = mock(Subject.class);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
    }

    private WmsOutboundDetailDTO detail(String qty, String pickQty) {
        WmsOutboundDetailDTO dto = new WmsOutboundDetailDTO();
        dto.setMaterialCode("PBC001");
        dto.setRequiredQty(new BigDecimal(qty));
        if (pickQty != null) {
            dto.setPickQty(new BigDecimal(pickQty));
        }
        return dto;
    }

    private WmsOutboundPickCreateDTO pickDto(WmsOutboundDetailDTO... details) {
        WmsOutboundPickCreateDTO dto = new WmsOutboundPickCreateDTO();
        dto.setWorkOrderNo("WO1");
        dto.setDetails(List.of(details));
        return dto;
    }

    private WmsOutboundOrder order(String status) {
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo("LL1001")
                .setOutboundType("生产领料")
                .setWorkOrderNo("WO1")
                .setStatus(status)
                .setSyncStatus("无需同步");
        order.setId("ob1");
        return order;
    }

    // ==================== 创建：领取人 / 设备 ====================

    @Test
    void createPick_pickerComesFromLoginAccount() {
        loginAs("wx001", "张三");

        WmsOutboundOrder created = service.createPick(pickDto(detail("10", null)));

        assertEquals("wx001", created.getPickerId());
        assertEquals("张三", created.getPickerName());
    }

    @Test
    void createPick_recordsDeviceWhenProvided() {
        loginAs("wx001", "张三");
        WmsDevice device = new WmsDevice();
        device.setId("dev1");
        when(deviceMapper.selectById("dev1")).thenReturn(device);
        WmsOutboundPickCreateDTO dto = pickDto(detail("10", null));
        dto.setDeviceId("dev1");

        WmsOutboundOrder created = service.createPick(dto);

        assertEquals("dev1", created.getDeviceId());
        verify(deviceMapper).selectById("dev1");
    }

    @Test
    void createPick_unknownDeviceRejected() {
        when(deviceMapper.selectById("dev404")).thenReturn(null);
        WmsOutboundPickCreateDTO dto = pickDto(detail("10", null));
        dto.setDeviceId("dev404");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.createPick(dto));

        assertEquals("领料设备不存在：dev404", e.getMessage());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    // ==================== 部分领料 ====================

    @Test
    void createPick_pickQtyExceedingRequirementRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createPick(pickDto(detail("10", "11"))));

        assertTrue(e.getMessage().contains("超过工单需求"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    @Test
    void createPick_nonPositivePickQtyRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createPick(pickDto(detail("10", "0"))));

        assertEquals("本次领料数量必须大于 0", e.getMessage());
    }

    @Test
    void createPick_partialPickOverridesRequiredQty() {
        service.createPick(pickDto(detail("10", "4")));

        ArgumentCaptor<WmsOutboundDetail> captor = ArgumentCaptor.forClass(WmsOutboundDetail.class);
        verify(detailMapper).insert(captor.capture());
        WmsOutboundDetail stored = captor.getValue();
        assertEquals(0, stored.getRequiredQty().compareTo(new BigDecimal("4")));
        // 语义不变：分配/实发数量仍从 0 起算
        assertEquals(0, stored.getAllocatedQty().signum());
        assertEquals(0, stored.getActualQty().signum());
    }

    // ==================== 取消 ====================

    @Test
    void cancelPick_closesOrderWithReasonAndNotifiesCreator() {
        when(orderMapper.selectById("ob1")).thenReturn(order("待分配"));

        service.cancelPick("ob1", "物料不符", "wx001");

        ArgumentCaptor<WmsOutboundOrder> patch = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(patch.capture(), any());
        assertEquals("已关闭", patch.getValue().getStatus());
        assertEquals("物料不符", patch.getValue().getCancelReason());
        assertEquals("wx001", patch.getValue().getCloseBy());
        assertNotNull(patch.getValue().getCloseTime());

        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals("planner1", message.getValue().getToUser());
        assertEquals("WMS领料取消通知", message.getValue().getTitle());
        assertTrue(message.getValue().getContent().contains("物料不符"),
                message.getValue().getContent());
    }

    @Test
    void cancelPick_notificationFailureStillCancels() {
        when(orderMapper.selectById("ob1")).thenReturn(order("已分配"));
        doThrow(new RuntimeException("消息服务不可用"))
                .when(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));

        assertDoesNotThrow(() -> service.cancelPick("ob1", "库存不足", "wx001"));

        ArgumentCaptor<WmsOutboundOrder> patch = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(patch.capture(), any());
        assertEquals("已关闭", patch.getValue().getStatus());
        assertEquals("库存不足", patch.getValue().getCancelReason());
    }

    @Test
    void cancelPick_fallsBackToDefaultRecipientWhenWorkOrderHasNoCreator() {
        WmsWorkOrder noCreator = new WmsWorkOrder().setWorkOrderNo("WO1")
                .setWorkOrderStatus("生产中");
        when(workOrderMapper.selectOne(any())).thenReturn(noCreator);
        when(orderMapper.selectById("ob1")).thenReturn(order("待分配"));

        service.cancelPick("ob1", "物料不符", "wx001");

        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(org.jeecg.modules.wms.constant.WmsConstants.ALERT_TO_USER,
                message.getValue().getToUser());
    }

    @Test
    void cancelPick_rejectsIllegalStatus() {
        when(orderMapper.selectById("ob1")).thenReturn(order("执行中"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPick("ob1", "物料不符", "wx001"));

        assertEquals("当前状态不允许取消", e.getMessage());
        verify(orderMapper, never()).update(any(WmsOutboundOrder.class), any());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void cancelPick_requiresReason() {
        when(orderMapper.selectById("ob1")).thenReturn(order("待分配"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPick("ob1", "  ", "wx001"));

        assertEquals("取消原因不能为空", e.getMessage());
    }

    @Test
    void cancelPick_unknownOrderThrows() {
        when(orderMapper.selectById("ob404")).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPick("ob404", "物料不符", "wx001"));

        assertEquals("出库单不存在", e.getMessage());
    }

    /** 已分配即可能已下发任务/锁定库存：取消要撤销任务并释放锁定。 */
    @Test
    void cancelPick_releasesLockedAllocation() {
        when(orderMapper.selectById("ob1")).thenReturn(order("已分配"));
        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("ob1")
                .setInventoryId("inv1")
                .setAllocatedQty(new BigDecimal("6"))
                .setTaskStatus("已受理");
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));
        org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper =
                mock(org.jeecg.modules.wms.mapper.WmsInventoryMapper.class);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);

        service.cancelPick("ob1", "库存不足", "wx001");

        verify(taskService).cancel("ob1");
        verify(inventoryMapper).releaseLocked("inv1", new BigDecimal("6"));
    }
}
