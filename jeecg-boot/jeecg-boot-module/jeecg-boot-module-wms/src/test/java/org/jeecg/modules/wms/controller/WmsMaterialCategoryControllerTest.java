package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @Description: 物料分类接口测试（直接调用控制器方法，不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsMaterialCategoryControllerTest {

    private static final String MSG_DUPLICATED = "编码已存在，请重新输入";

    /** 造控制器 + 注入 mock 服务层。 */
    private static WmsMaterialCategoryController controller(IWmsMaterialCategoryService service) {
        WmsMaterialCategoryController controller = new WmsMaterialCategoryController();
        WmsTestSupport.inject(controller, "service", service);
        return controller;
    }

    // ------------------------------------------------------------ 分类树（BD001-T01）

    /** 分类树正常返回 200 与树数据。 */
    @Test
    void treeList_shouldReturnOkWithTree() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        SelectTreeModel model = new SelectTreeModel();
        model.setKey("1");
        model.setTitle("半成品");
        doReturn(Collections.singletonList(model)).when(service).queryTreeList();

        Result<List<SelectTreeModel>> result = controller(service).treeList();

        assertTrue(result.isSuccess());
        assertEquals(200, result.getCode());
        assertEquals(1, result.getResult().size());
        assertEquals("半成品", result.getResult().get(0).getTitle());
    }

    /** 空数据时返回空树而非报错。 */
    @Test
    void treeList_shouldReturnEmptyTreeOnNoData() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doReturn(Collections.emptyList()).when(service).queryTreeList();

        Result<List<SelectTreeModel>> result = controller(service).treeList();

        assertEquals(200, result.getCode());
        assertTrue(result.getResult().isEmpty());
    }

    // ------------------------------------------------------------ 分页列表（BD001-T02 按类型筛选）

    /** 分类类型筛选必须经 QueryGenerator 生效，并把分页结果原样返回。 */
    @Test
    void queryPageList_shouldAcceptTypeFilter() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        Page<WmsMaterialCategory> page = new Page<>(1, 10);
        WmsMaterialCategory row = new WmsMaterialCategory()
                .setCategoryCode("C01").setCategoryName("半成品").setCategoryType("半成品");
        row.setId("1");
        page.setRecords(Collections.singletonList(row));
        page.setTotal(1);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("categoryType", "半成品");

        Result<IPage<WmsMaterialCategory>> result = controller(service)
                .queryPageList(new WmsMaterialCategory(), 1, 10, request);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getResult().getTotal());
        assertEquals("半成品", result.getResult().getRecords().get(0).getCategoryType());
    }

    // ------------------------------------------------------------ 编码唯一性校验

    /**
     * 空编码必须被挡在服务层之前，并给出明确提示。
     *
     * <p>用 `""` 而非 `"   "`：`oConvertUtils.isEmpty` 不 trim，纯空白**不算空**，
     * 走不到这条分支（空白会照常落到服务层）。
     */
    @Test
    void checkCode_shouldRejectBlankCode() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);

        Result<Boolean> result = controller(service).checkCode("", null);

        assertEquals(false, result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals("分类编码不能为空", result.getMessage());
    }

    /** 编码已占用时返回错误提示。 */
    @Test
    void checkCode_shouldRejectExistingCode() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doReturn(true).when(service).checkCodeExists("C01", null);

        Result<Boolean> result = controller(service).checkCode("C01", null);

        assertEquals(false, result.isSuccess());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 编码可用时返回 200。 */
    @Test
    void checkCode_shouldReturnOkWhenFree() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doReturn(false).when(service).checkCodeExists("C01", null);

        Result<Boolean> result = controller(service).checkCode("C01", null);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getResult());
    }

    // ------------------------------------------------------------ 新增（BD001-T03/T04）

    /** 新增成功返回 200 与「添加成功！」。 */
    @Test
    void add_shouldReturnOk() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);

        Result<WmsMaterialCategory> result = controller(service)
                .add(new WmsMaterialCategory().setCategoryCode("C01").setCategoryName("半成品"));

        assertEquals(200, result.getCode());
        assertEquals("添加成功！", result.getMessage());
        verify(service).addCategory(any());
    }

    /** 编码重复时控制器必须把服务层异常转成 Result.error，且消息原样透出（BD001-T04）。 */
    @Test
    void add_shouldReturnErrorOnDuplicateCode() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).addCategory(any());

        Result<WmsMaterialCategory> result = controller(service)
                .add(new WmsMaterialCategory().setCategoryCode("C01").setCategoryName("半成品"));

        assertEquals(false, result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    // ------------------------------------------------------------ 编辑（BD001-T06）

    /** 编辑成功返回「修改成功！」。 */
    @Test
    void edit_shouldReturnOk() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        WmsMaterialCategory input = new WmsMaterialCategory().setCategoryCode("C01").setCategoryName("半成品（改名）");
        input.setId("1");

        Result<WmsMaterialCategory> result = controller(service).edit(input);

        assertEquals(200, result.getCode());
        assertEquals("修改成功！", result.getMessage());
        verify(service).editCategory(input);
    }

    /** 编辑撞码时消息原样透出。 */
    @Test
    void edit_shouldReturnErrorOnDuplicateCode() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).editCategory(any());

        Result<WmsMaterialCategory> result = controller(service)
                .edit(new WmsMaterialCategory().setCategoryCode("C01").setCategoryName("半成品"));

        assertEquals(false, result.isSuccess());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    // ------------------------------------------------------------ 停用（BD001-T08）

    /** 停用成功返回「停用成功！」。 */
    @Test
    void disable_shouldReturnOk() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);

        Result<?> result = controller(service).disable("1");

        assertEquals(200, result.getCode());
        assertEquals("停用成功！", result.getMessage());
        verify(service).disableCategory("1");
    }

    // ------------------------------------------------------------ 删除（BD001-T07）

    /** 无关联数据时删除成功。 */
    @Test
    void delete_shouldReturnOk() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);

        Result<?> result = controller(service).delete("1");

        assertEquals(200, result.getCode());
        assertEquals("删除成功！", result.getMessage());
        verify(service).removeCategory("1");
    }

    /** 分类下有物料时删除必须被拒，且把拒绝原因透给前端（BD001-T07）。 */
    @Test
    void delete_shouldReturnErrorWhenHasMaterial() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doThrow(new RuntimeException("该分类下存在物料，不允许删除"))
                .when(service).removeCategory("1");

        Result<?> result = controller(service).delete("1");

        assertEquals(false, result.isSuccess());
        assertEquals("该分类下存在物料，不允许删除", result.getMessage());
    }

    // ------------------------------------------------------------ 通过id查询

    /** 查不到时返回「未找到对应实体」。 */
    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        doReturn(null).when(service).getById("404");

        Result<WmsMaterialCategory> result = controller(service).queryById("404");

        assertEquals(false, result.isSuccess());
        assertEquals("未找到对应实体", result.getMessage());
    }

    /** 查到实体时返回 200。 */
    @Test
    void queryById_shouldReturnOkWhenFound() {
        IWmsMaterialCategoryService service = mock(IWmsMaterialCategoryService.class);
        WmsMaterialCategory entity = new WmsMaterialCategory()
                .setCategoryCode("C01").setCategoryName("半成品")
                .setStatus(WmsConstants.STATUS_ENABLE);
        entity.setId("1");
        doReturn(entity).when(service).getById("1");

        Result<WmsMaterialCategory> result = controller(service).queryById("1");

        assertEquals(200, result.getCode());
        assertEquals("C01", result.getResult().getCategoryCode());
    }
}