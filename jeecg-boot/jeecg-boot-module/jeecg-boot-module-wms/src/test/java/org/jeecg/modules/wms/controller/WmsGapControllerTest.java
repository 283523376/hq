package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsConversionCreateDTO;
import org.jeecg.modules.wms.dto.WmsDefectInboundDTO;
import org.jeecg.modules.wms.dto.WmsDefectReturnDTO;
import org.jeecg.modules.wms.dto.WmsPickCancelDTO;
import org.jeecg.modules.wms.dto.WmsPutawayDTO;
import org.jeecg.modules.wms.dto.WmsRawInboundDTO;
import org.jeecg.modules.wms.entity.WmsConversionOrder;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsConversionService;
import org.jeecg.modules.wms.service.IWmsDefectService;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsRawInboundService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsRawPutawayResultVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: V2.4 Task 9——入库/改制/不良/领料接口的权限码、审计与错误透传。
 *
 * <p>锁四件事：
 * <ol>
 *   <li>每个新端点的 {@code @RequiresPermissions} 与 Flyway 权限码<strong>逐字相等</strong>
 *       （不一致就是运行时 403，单测之外看不出来）；</li>
 *   <li>写端点都带 {@code @WmsAudit}，动作与业务类型取 {@code WmsConstants}；</li>
 *   <li>服务抛 RuntimeException 时返回 {@code Result.error}，message 原样透出，
 *       <strong>不能出现全局处理器的「操作失败，」前缀</strong>；</li>
 *   <li>旧 {@code /wms/inbound} 链路对 {@code sourceType} 非空的单据一律拒绝。</li>
 * </ol>
 *
 * <p>权限码来源（逐字抄自脚本，改脚本或改注解都必须同步本表）：
 * <ul>
 *   <li>{@code V3.9.5_47__wms_consigned_inventory_and_gap.sql}：
 *       wms:inbound:consigned、wms:conversion:view/create/execute/cancel、
 *       wms:defect:inbound、wms:outbound:pick:cancel</li>
 *   <li>{@code V3.9.5_44__wms_defect_disposal.sql}：wms:defect:list</li>
 *   <li>{@code V3.9.5_49__wms_defect_return_permission.sql}：wms:defect:return（Task 9 补齐）</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsGapControllerTest {

    /** 端点声明：控制器类 / 方法名 / 权限码 / 是否写端点 / 审计业务类型 / 审计动作。 */
    private record Endpoint(Class<?> controller, String method, String permission,
                            boolean write, String bizType, String action) {

        String key(String message) {
            return message + "：" + controller.getSimpleName() + "#" + method;
        }
    }

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(WmsRawInboundController.class, "page", "wms:inbound:consigned",
                    false, null, null),
            new Endpoint(WmsRawInboundController.class, "receive", "wms:inbound:consigned",
                    true, WmsConstants.AUDIT_BIZ_INBOUND, WmsConstants.AUDIT_ACTION_CREATE),
            new Endpoint(WmsRawInboundController.class, "putaway", "wms:inbound:consigned",
                    true, WmsConstants.AUDIT_BIZ_INBOUND, WmsConstants.AUDIT_ACTION_PUTAWAY),
            new Endpoint(WmsRawInboundController.class, "resync", "wms:inbound:consigned",
                    true, WmsConstants.AUDIT_BIZ_INBOUND, WmsConstants.AUDIT_ACTION_WRITE_BACK),
            new Endpoint(WmsConversionController.class, "create", "wms:conversion:create",
                    true, WmsConstants.AUDIT_BIZ_CONVERSION, WmsConstants.AUDIT_ACTION_CREATE),
            new Endpoint(WmsConversionController.class, "complete", "wms:conversion:execute",
                    true, WmsConstants.AUDIT_BIZ_CONVERSION, WmsConstants.AUDIT_ACTION_COMPLETE),
            new Endpoint(WmsConversionController.class, "cancel", "wms:conversion:cancel",
                    true, WmsConstants.AUDIT_BIZ_CONVERSION, WmsConstants.AUDIT_ACTION_CANCEL_BILL),
            new Endpoint(WmsConversionController.class, "page", "wms:conversion:view",
                    false, null, null),
            new Endpoint(WmsDefectController.class, "recordInbound", "wms:defect:inbound",
                    true, WmsConstants.AUDIT_BIZ_DEFECT_RECORD, WmsConstants.AUDIT_ACTION_RECEIVE),
            new Endpoint(WmsDefectController.class, "returnToConsignor", "wms:defect:return",
                    true, WmsConstants.AUDIT_BIZ_DEFECT_RECORD, WmsConstants.AUDIT_ACTION_RETURN),
            new Endpoint(WmsDefectController.class, "records", "wms:defect:list",
                    false, null, null),
            new Endpoint(WmsOutboundController.class, "cancelPick", "wms:outbound:pick:cancel",
                    true, WmsConstants.AUDIT_BIZ_OUTBOUND, WmsConstants.AUDIT_ACTION_CANCEL_PICK));

    private static Method endpointMethod(Endpoint endpoint) {
        return Arrays.stream(endpoint.controller().getDeclaredMethods())
                .filter(m -> m.getName().equals(endpoint.method()) && !m.isSynthetic())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到端点方法：" + endpoint.controller().getSimpleName() + "#" + endpoint.method()));
    }

    // ------------------------------------------------------------ 1. 权限码

    @Test
    void permissionAnnotationsMatchExpectedCodes() {
        for (Endpoint endpoint : ENDPOINTS) {
            RequiresPermissions annotation = endpointMethod(endpoint).getAnnotation(RequiresPermissions.class);
            assertNotNull(annotation, endpoint.key("缺少 @RequiresPermissions"));
            assertEquals(endpoint.permission(), annotation.value()[0],
                    endpoint.key("权限码与 Flyway 脚本不一致（运行时会 403）"));
        }
    }

    // ------------------------------------------------------------ 2. 审计

    @Test
    void writeEndpointsCarryAuditAnnotation() {
        for (Endpoint endpoint : ENDPOINTS) {
            Method method = endpointMethod(endpoint);
            if (!endpoint.write()) {
                assertNull(method.getAnnotation(WmsAudit.class), endpoint.key("查询端点不应带 @WmsAudit"));
                continue;
            }
            WmsAudit audit = method.getAnnotation(WmsAudit.class);
            assertNotNull(audit, endpoint.key("写端点缺少 @WmsAudit"));
            assertEquals(endpoint.bizType(), audit.bizType(), endpoint.key("审计业务类型不一致"));
            assertEquals(endpoint.action(), audit.action(), endpoint.key("审计动作不一致"));
        }
    }

    // ------------------------------------------------------------ 3. 错误透传（不经过全局处理器）

    @Test
    void rawInboundReceive_passesErrorTextThrough() {
        IWmsRawInboundService service = mock(IWmsRawInboundService.class);
        when(service.receive(any(WmsRawInboundDTO.class), any()))
                .thenThrow(new RuntimeException("ERP 订单不存在：PO001"));
        WmsRawInboundController controller = new WmsRawInboundController(service);

        Result<WmsInboundOrder> result = controller.receive(new WmsRawInboundDTO());

        assertRawError(result, "ERP 订单不存在：PO001");
    }

    /** 原材料列表必须走专用分页：旧的 /wms/inbound/list 会混入生产入库与历史收料单。 */
    @Test
    void rawInboundPage_forwardsFiltersToService() {
        IWmsRawInboundService service = mock(IWmsRawInboundService.class);
        when(service.listPage(1, 10, "CONSIGNED", "待分配", "RK001", "受托方一"))
                .thenReturn(new Page<>(1, 10));
        WmsRawInboundController controller = new WmsRawInboundController(service);

        Result<IPage<WmsInboundOrder>> result = controller.page(1, 10, "CONSIGNED", "待分配",
                "RK001", "受托方一");

        assertTrue(result.isSuccess(), result.getMessage());
        verify(service).listPage(1, 10, "CONSIGNED", "待分配", "RK001", "受托方一");
    }

    @Test
    void rawInboundPutaway_passesErrorTextThrough() {
        IWmsRawInboundService service = mock(IWmsRawInboundService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("库位不可用"))
                .when(service).putawayRaw(eq("in1"), any(WmsPutawayDTO.class), any());
        WmsRawInboundController controller = new WmsRawInboundController(service);

        Result<WmsRawPutawayResultVO> result = controller.putaway("in1", new WmsPutawayDTO());

        assertEquals(500, result.getCode());
        assertEquals("库位不可用", result.getMessage());
    }

    /** 重试回写失败也必须原样透出原因（补偿入口不能静默失败）。 */
    @Test
    void rawInboundResync_passesErrorTextThrough() {
        IWmsRawInboundService service = mock(IWmsRawInboundService.class);
        when(service.resync(eq("in1"), any())).thenThrow(new RuntimeException("仅已审核单据可重试回写"));
        WmsRawInboundController controller = new WmsRawInboundController(service);

        Result<WmsRawPutawayResultVO> result = controller.resync("in1");

        assertEquals(500, result.getCode());
        assertEquals("仅已审核单据可重试回写", result.getMessage());
    }

    @Test
    void conversionComplete_passesErrorTextThrough() {
        IWmsConversionService service = mock(IWmsConversionService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("当前状态不允许完成"))
                .when(service).complete(eq("c1"), any());
        WmsConversionController controller = new WmsConversionController(service);

        Result<Void> result = controller.complete("c1");

        assertEquals(500, result.getCode());
        assertEquals("当前状态不允许完成", result.getMessage());
    }

    @Test
    void conversionCreate_passesErrorTextThrough() {
        IWmsConversionService service = mock(IWmsConversionService.class);
        when(service.create(any(WmsConversionCreateDTO.class), any()))
                .thenThrow(new RuntimeException("生产订单未下达"));
        WmsConversionController controller = new WmsConversionController(service);

        Result<WmsConversionOrder> result = controller.create(new WmsConversionCreateDTO());

        assertEquals(500, result.getCode());
        assertEquals("生产订单未下达", result.getMessage());
        assertNull(result.getResult());
    }

    @Test
    void defectInboundAndReturn_passErrorTextThrough() {
        IWmsDefectService service = mock(IWmsDefectService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("受托库存不足"))
                .when(service).recordInbound(any(WmsDefectInboundDTO.class), any());
        when(service.returnToConsignor(eq("r1"), any()))
                .thenThrow(new RuntimeException("不良品记录不存在"));
        WmsDefectController controller = new WmsDefectController(service);

        Result<Void> inbound = controller.recordInbound(new WmsDefectInboundDTO());
        assertEquals(500, inbound.getCode());
        assertEquals("受托库存不足", inbound.getMessage());

        Result<WmsDefectRecord> returned = controller.returnToConsignor(new WmsDefectReturnDTO().setRecordId("r1"));
        assertEquals(500, returned.getCode());
        assertEquals("不良品记录不存在", returned.getMessage());
    }

    @Test
    void outboundCancelPick_passesErrorTextThroughAndForwardsReason() {
        IWmsOutboundService service = mock(IWmsOutboundService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("仅生产领料单可取消，其他单据请走关单"))
                .when(service).cancelPick(eq("o1"), eq("库存不足"), isNull());
        WmsOutboundController controller = new WmsOutboundController();
        WmsTestSupport.inject(controller, "service", service);
        WmsPickCancelDTO dto = new WmsPickCancelDTO();
        dto.setReason("库存不足");

        Result<Void> result = controller.cancelPick("o1", dto);

        assertEquals(500, result.getCode());
        assertEquals("仅生产领料单可取消，其他单据请走关单", result.getMessage());
        verify(service).cancelPick(eq("o1"), eq("库存不足"), isNull());
    }

    @Test
    void defectRecords_forwardsQueryEntityUntouched() {
        IWmsDefectService service = mock(IWmsDefectService.class);
        IPage<WmsDefectRecord> empty = new Page<>(1, 10);
        when(service.listPage(eq(1), eq(10), any(WmsDefectRecord.class))).thenReturn(empty);
        WmsDefectController controller = new WmsDefectController(service);
        WmsDefectRecord query = new WmsDefectRecord().setMaterialCode("M00").setStatus("待处理");

        Result<IPage<WmsDefectRecord>> result = controller.records(query, 1, 10);

        assertTrue(result.isSuccess());
        assertNotNull(result.getResult());
        // 物料关键词的模糊匹配在服务层完成，控制器不得改写查询对象
        verify(service).listPage(1, 10, query);
    }

    /** 断言：失败码 500、message 原样、无全局处理器前缀、无数据。 */
    private void assertRawError(Result<?> result, String expected) {
        assertEquals(500, result.getCode());
        assertEquals(expected, result.getMessage());
        assertFalse(result.getMessage().startsWith("操作失败，"),
                "失败文案被全局异常处理器改写：" + result.getMessage());
        assertNull(result.getResult());
    }

    // ------------------------------------------------------------ 4. 旧链路守卫

    private WmsInboundController inboundController(IWmsInboundService service) {
        WmsInboundController controller = new WmsInboundController();
        WmsTestSupport.inject(controller, "service", service);
        return controller;
    }

    private WmsInboundOrder rawOrder() {
        WmsInboundOrder order = new WmsInboundOrder()
                .setSourceType(WmsConstants.INBOUND_SOURCE_PURCHASE)
                .setInboundType(WmsConstants.INBOUND_TYPE_RAW_PURCHASE)
                .setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE);
        order.setId("in1");
        return order;
    }

    @Test
    void legacyPutaway_rejectsRawInboundOrder() {
        IWmsInboundService service = mock(IWmsInboundService.class);
        when(service.getById("in1")).thenReturn(rawOrder());
        WmsInboundController controller = inboundController(service);

        Result<Void> result = controller.putaway("in1", new WmsPutawayDTO());

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("外购/受托原材料入库通道"), result.getMessage());
        assertTrue(result.getMessage().contains("/wms/raw-inbound/in1/putaway"), result.getMessage());
        verify(service, never()).putaway(anyString(), any(WmsPutawayDTO.class));
    }

    @Test
    void legacySync_rejectsRawInboundOrder() {
        IWmsInboundService service = mock(IWmsInboundService.class);
        when(service.getById("in1")).thenReturn(rawOrder());
        WmsInboundController controller = inboundController(service);

        Result<?> result = controller.resync("in1");

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("外购/受托原材料入库通道"), result.getMessage());
        verify(service, never()).claimForSync(anyString(), any());
    }

    @Test
    void legacyPutaway_stillWorksForOrdersWithoutSourceType() {
        IWmsInboundService service = mock(IWmsInboundService.class);
        WmsInboundOrder legacy = new WmsInboundOrder()
                .setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY);
        legacy.setId("in2");
        when(service.getById("in2")).thenReturn(legacy);
        WmsInboundController controller = inboundController(service);

        Result<Void> result = controller.putaway("in2", new WmsPutawayDTO());

        assertTrue(result.isSuccess());
        verify(service).putaway(eq("in2"), any(WmsPutawayDTO.class));
    }

    /** 空串与 null 等价：旧链路只写 source_type，未写的行可能是 '' 而不是 NULL。 */
    @Test
    void legacyPutaway_treatsEmptySourceTypeAsLegacy() {
        IWmsInboundService service = mock(IWmsInboundService.class);
        WmsInboundOrder legacy = new WmsInboundOrder()
                .setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY)
                .setSourceType("");
        legacy.setId("in3");
        when(service.getById("in3")).thenReturn(legacy);
        WmsInboundController controller = inboundController(service);

        Result<Void> result = controller.putaway("in3", new WmsPutawayDTO());

        assertTrue(result.isSuccess());
        verify(service).putaway(eq("in3"), any(WmsPutawayDTO.class));
    }

    @Test
    void legacyPutaway_returnsErrorWhenOrderMissing() {
        IWmsInboundService service = mock(IWmsInboundService.class);
        when(service.getById("in404")).thenReturn(null);
        WmsInboundController controller = inboundController(service);

        Result<Void> result = controller.putaway("in404", new WmsPutawayDTO());

        assertEquals(500, result.getCode());
        assertEquals("单据不存在", result.getMessage());
        verify(service, never()).putaway(anyString(), any(WmsPutawayDTO.class));
    }
}
