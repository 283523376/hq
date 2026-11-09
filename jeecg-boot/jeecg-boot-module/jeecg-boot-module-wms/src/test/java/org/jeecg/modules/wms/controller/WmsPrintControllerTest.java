package org.jeecg.modules.wms.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.service.IWmsPrintService;
import org.jeecg.modules.wms.vo.WmsPrintBillListVO;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 单据打印接口单测（DY-001 Task 5）：权限码、参数透传、错误文案原样透出、审计留痕。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsPrintControllerTest {

    private IWmsPrintService service;
    private WmsPrintController controller;

    @BeforeEach
    void setUp() {
        service = mock(IWmsPrintService.class);
        controller = new WmsPrintController(service);
    }

    /** 两个端点都挂 wms:print:list，且 data 带审计留痕（XT-002 口径：查询也留痕）。 */
    @Test
    void endpoints_requirePrintListPermission_andDataIsAudited() throws Exception {
        for (String name : new String[]{"data", "localBills", "bills"}) {
            Method m = "bills".equals(name)
                    ? WmsPrintController.class.getMethod(name, String.class, String.class,
                            String.class, Integer.class, Integer.class)
                    : WmsPrintController.class.getMethod(name, String.class, String.class);
            RequiresPermissions p = m.getAnnotation(RequiresPermissions.class);
            assertNotNull(p, name + " 缺少 @RequiresPermissions");
            assertEquals("wms:print:list", p.value()[0]);
        }
        Method data = WmsPrintController.class.getMethod("data", String.class, String.class);
        WmsAudit audit = data.getAnnotation(WmsAudit.class);
        assertNotNull(audit, "data 端点必须带 @WmsAudit（XT-002）");
        assertEquals("单据打印", audit.bizType());
        assertEquals("查询", audit.action());
    }

    /** 参数原样透传，返回 service 组装的 VO。 */
    @Test
    void data_passesParamsAndReturnsVO() {
        WmsPrintDataVO vo = new WmsPrintDataVO().setBillType("LOSS").setTitle("盘亏单");
        when(service.printData("LOSS", "QTCK10126110002")).thenReturn(vo);

        Result<WmsPrintDataVO> result = controller.data("LOSS", "QTCK10126110002");

        assertTrue(result.isSuccess());
        assertEquals("盘亏单", result.getResult().getTitle());
        verify(service).printData("LOSS", "QTCK10126110002");
    }

    /** 业务异常文案原样透出（不加全局前缀）。 */
    @Test
    void data_errorTextPassedThrough() {
        when(service.printData(anyString(), anyString()))
                .thenThrow(new RuntimeException("单据不存在或未审核，不能打印（单号：QTCK404）"));

        Result<WmsPrintDataVO> result = controller.data("LOSS", "QTCK404");

        assertEquals(500, result.getCode());
        assertEquals("单据不存在或未审核，不能打印（单号：QTCK404）", result.getMessage());
    }

    /** 下拉端点透传类型与关键词。 */
    @Test
    void localBills_passesParams() {
        when(service.localBills("PROFIT", "PD2026"))
                .thenReturn(List.of(Map.of("billNo", "PD202611090002")));

        Result<List<Map<String, String>>> result = controller.localBills("PROFIT", "PD2026");

        assertTrue(result.isSuccess());
        assertEquals("PD202611090002", result.getResult().get(0).get("billNo"));
        verify(service).localBills(eq("PROFIT"), eq("PD2026"));
    }

    /** 列表端点：参数透传（模糊关键词 + 分页）。 */
    @Test
    void bills_passesKeywordAndPaging() {
        WmsPrintBillListVO vo = new WmsPrintBillListVO().setPageNo(2).setPageSize(20).setTotal(41L);
        when(service.bills("LOSS", "PD2026", "MONTH", 2, 20)).thenReturn(vo);

        Result<WmsPrintBillListVO> result = controller.bills("LOSS", "PD2026", "MONTH", 2, 20);

        assertTrue(result.isSuccess());
        assertEquals(41L, result.getResult().getTotal());
        verify(service).bills("LOSS", "PD2026", "MONTH", 2, 20);
    }
}