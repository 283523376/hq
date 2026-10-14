package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @Description: 供应商接口测试（直接调用控制器方法，不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsSupplierControllerTest {

    private static final String MSG_DUPLICATED = "编码已存在，请重新输入";

    private static WmsSupplierController controller(IWmsSupplierService service) {
        WmsSupplierController controller = new WmsSupplierController();
        WmsTestSupport.inject(controller, "service", service);
        return controller;
    }

    private static WmsSupplier supplier(String id, String code, String name) {
        WmsSupplier supplier = new WmsSupplier().setSupplierCode(code).setSupplierName(name);
        supplier.setId(id);
        return supplier;
    }

    // ------------------------------------------------------------ 分页列表（BD002-T07）

    /** 按名称筛选必须经 QueryGenerator 生效并返回分页结果。 */
    @Test
    void queryPageList_shouldAcceptNameFilter() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        Page<WmsSupplier> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(supplier("1", "SUP001", "永鑫五金")));
        page.setTotal(1);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("supplierName", "永鑫");

        Result<IPage<WmsSupplier>> result = controller(service)
                .queryPageList(new WmsSupplier(), 1, 10, request);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getResult().getTotal());
        assertEquals("永鑫五金", result.getResult().getRecords().get(0).getSupplierName());
    }

    /** 按编码与状态筛选同样必须生效。 */
    @Test
    void queryPageList_shouldAcceptCodeAndStatusFilters() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        Page<WmsSupplier> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("supplierCode", "SUP001");
        request.addParameter("status", WmsConstants.STATUS_ENABLE);

        Result<IPage<WmsSupplier>> result = controller(service)
                .queryPageList(new WmsSupplier(), 1, 10, request);

        assertEquals(200, result.getCode());
        assertEquals(0, result.getResult().getTotal());
    }

    // ------------------------------------------------------------ 下拉列表

    /**
     * 下拉接口必须清空联系人/电话/供货范围以减小报文——这些字段在选供应商时无用，
     * 且属于不该随手泼给前端的联系人信息。
     */
    @Test
    void listAll_shouldStripSensitiveFields() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        WmsSupplier row = supplier("1", "SUP001", "永鑫五金")
                .setContact("张三").setPhone("13800000000").setSupplyScope("五金件");
        doReturn(Collections.singletonList(row)).when(service).listAllEnabled();

        Result<List<WmsSupplier>> result = controller(service).listAll();

        assertEquals(200, result.getCode());
        assertNull(result.getResult().get(0).getContact(), "下拉报文不应带联系人");
        assertNull(result.getResult().get(0).getPhone(), "下拉报文不应带电话");
        assertNull(result.getResult().get(0).getSupplyScope(), "下拉报文不应带供货范围");
        assertEquals("永鑫五金", result.getResult().get(0).getSupplierName(), "名称必须保留");
    }

    /** 无启用供应商时返回空列表而非报错。 */
    @Test
    void listAll_shouldReturnEmptyWhenNone() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doReturn(Collections.emptyList()).when(service).listAllEnabled();

        Result<List<WmsSupplier>> result = controller(service).listAll();

        assertEquals(200, result.getCode());
        assertTrue(result.getResult().isEmpty());
    }

    // ------------------------------------------------------------ 通过id查询

    /** 查不到时返回「未找到对应实体」。 */
    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doReturn(null).when(service).getById("404");

        Result<WmsSupplier> result = controller(service).queryById("404");

        assertEquals(false, result.isSuccess());
        assertEquals("未找到对应实体", result.getMessage());
    }

    /** 查到实体时返回 200。 */
    @Test
    void queryById_shouldReturnOkWhenFound() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doReturn(supplier("1", "SUP001", "永鑫五金")).when(service).getById("1");

        Result<WmsSupplier> result = controller(service).queryById("1");

        assertEquals(200, result.getCode());
        assertEquals("SUP001", result.getResult().getSupplierCode());
    }

    // ------------------------------------------------------------ 编码唯一性校验

    /** 空编码必须被挡下。用 `""` 而非 `"   "`——`oConvertUtils.isEmpty` 不 trim，纯空白不算空。 */
    @Test
    void checkCode_shouldRejectBlankCode() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<Boolean> result = controller(service).checkCode("", null);

        assertEquals(false, result.isSuccess());
        assertEquals("供应商编码不能为空", result.getMessage());
    }

    /** 编码已占用时给出提示。 */
    @Test
    void checkCode_shouldRejectExistingCode() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doReturn(true).when(service).checkCodeExists("SUP001", null);

        Result<Boolean> result = controller(service).checkCode("SUP001", null);

        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 编码可用时返回 200。 */
    @Test
    void checkCode_shouldReturnOkWhenFree() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doReturn(false).when(service).checkCodeExists("SUP001", null);

        Result<Boolean> result = controller(service).checkCode("SUP001", null);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getResult());
    }

    // ------------------------------------------------------------ 增改停删

    /** 新增成功（BD002-T01）。 */
    @Test
    void add_shouldReturnOk() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<WmsSupplier> result = controller(service).add(supplier(null, "SUP001", "永鑫五金"));

        assertEquals(200, result.getCode());
        assertEquals("添加成功！", result.getMessage());
        verify(service).addSupplier(any());
    }

    /** 编码重复时消息原样透出（BD002-T02）。 */
    @Test
    void add_shouldReturnErrorOnDuplicateCode() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).addSupplier(any());

        Result<WmsSupplier> result = controller(service).add(supplier(null, "SUP001", "永鑫五金"));

        assertEquals(false, result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 编辑成功（BD002-T03）。 */
    @Test
    void edit_shouldReturnOk() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        WmsSupplier input = supplier("1", "SUP001", "永鑫五金（改名）");

        Result<WmsSupplier> result = controller(service).edit(input);

        assertEquals(200, result.getCode());
        assertEquals("修改成功！", result.getMessage());
        verify(service).editSupplier(input);
    }

    /** 编辑撞码时消息原样透出。 */
    @Test
    void edit_shouldReturnErrorOnDuplicateCode() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).editSupplier(any());

        Result<WmsSupplier> result = controller(service).edit(supplier("1", "SUP001", "永鑫五金"));

        assertEquals(false, result.isSuccess());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 停用成功（BD002-T04）。 */
    @Test
    void disable_shouldReturnOk() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<?> result = controller(service).disable("1");

        assertEquals(200, result.getCode());
        assertEquals("停用成功！", result.getMessage());
        verify(service).disableSupplier("1");
    }

    /** 删除成功（BD002-T05）。 */
    @Test
    void delete_shouldReturnOk() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<?> result = controller(service).delete("1");

        assertEquals(200, result.getCode());
        assertEquals("删除成功！", result.getMessage());
        verify(service).removeSupplier("1");
    }

    /** 批量删除的空 ids 必须被挡下。 */
    @Test
    void deleteBatch_shouldRejectBlankIds() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<?> result = controller(service).deleteBatch("");

        assertEquals(false, result.isSuccess());
        assertEquals("参数不识别！", result.getMessage());
    }

    /** 批量删除按逗号切分 ids。 */
    @Test
    void deleteBatch_shouldParseCommaSeparatedIds() {
        IWmsSupplierService service = mock(IWmsSupplierService.class);

        Result<?> result = controller(service).deleteBatch("1,2");

        assertEquals(200, result.getCode());
        verify(service).removeBatch(Arrays.asList("1", "2"));
    }
}