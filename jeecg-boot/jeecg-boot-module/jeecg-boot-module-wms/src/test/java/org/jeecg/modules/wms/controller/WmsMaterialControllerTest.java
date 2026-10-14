package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.util.PmsUtil;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 物料主数据接口测试（含批量导入/导出；不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsMaterialControllerTest {

    private static final String MSG_DUPLICATED = "编码已存在，请重新输入";

    @TempDir
    Path tempDir;

    /**
     * 导入出错时 autopoi 会把错误清单写到 {@code PmsUtil.uploadPath} 下。
     * 该字段是静态的、靠 Spring {@code @Value} 注入，单测里为 null——不重定向的话
     * 会在模块目录下生成一个 `null/logs/...` 垃圾目录。
     */
    @BeforeEach
    void redirectImportErrorLog() {
        new PmsUtil().setUploadPath(tempDir.toString());
        WmsTestSupport.bindShiro();
    }

    @AfterEach
    void tearDown() {
        WmsTestSupport.unbindShiro();
    }

    private static WmsMaterialController controller(IWmsMaterialService service) {
        WmsMaterialController controller = new WmsMaterialController();
        WmsTestSupport.inject(controller, "service", service);
        return controller;
    }

    private static MockMultipartHttpServletRequest multipartRequest(byte[] xlsx) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "material.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));
        return request;
    }

    /** 读出导入错误清单的正文（autopoi 写出的 .txt）。 */
    private static String readErrorLog(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            Path log = paths.filter(p -> p.toString().endsWith(".txt")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("未生成导入错误清单文件"));
            return Files.readString(log, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------ 分页列表

    /** 列表必须回填分类名称，且返回 200。 */
    @Test
    void queryPageList_shouldReturnPageAndFillCategoryInfo() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        Page<WmsMaterial> page = new Page<>(1, 10);
        List<WmsMaterial> records = Collections.singletonList(
                new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓"));
        page.setRecords(records);
        page.setTotal(1);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        Result<IPage<WmsMaterial>> result = controller(service)
                .queryPageList(new WmsMaterial(), 1, 10, new MockHttpServletRequest());

        assertEquals(200, result.getCode());
        assertEquals(1, result.getResult().getTotal());
        verify(service).fillCategoryInfo(records);
    }

    /**
     * 非持久化字段（categoryName / categoryCode）**绝不能**进入 SQL 条件。
     *
     * <p>表里没有这两列，一旦 QueryGenerator 拿它们拼出 `category_name` 条件，列表接口会直接 SQL 报错。
     * 控制器必须先置空——本用例守住这一点。（BD-002 的供应商回填字段曾真的踩过同样的坑。）
     */
    @Test
    void queryPageList_shouldIgnoreNonPersistentFields() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        Page<WmsMaterial> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        WmsMaterial probe = new WmsMaterial().setCategoryName("半成品").setCategoryCode("C01");

        Result<IPage<WmsMaterial>> result = controller(service)
                .queryPageList(probe, 1, 10, new MockHttpServletRequest());

        assertEquals(200, result.getCode());
        // 直接断言控制器的动作：它必须把非持久化字段置空后才交给 QueryGenerator
        assertNull(probe.getCategoryName(), "控制器必须先置空 categoryName 再构造查询条件");
        assertNull(probe.getCategoryCode(), "控制器必须先置空 categoryCode 再构造查询条件");

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).page(any(Page.class), captor.capture());
        QueryWrapper<WmsMaterial> wrapper = (QueryWrapper<WmsMaterial>) captor.getValue();
        // 必须先渲染 SQL 片段：MyBatis-Plus 的条件参数是惰性填充的，不调 getSqlSegment()
        // 时 getParamNameValuePairs() 恒为空，下面的 assertFalse 会退化成永真断言（什么都不验证）
        String sqlSegment = wrapper.getSqlSegment();
        assertFalse(sqlSegment.contains("category_name"),
                "非持久化字段 categoryName 不得被拼进查询条件，实际：" + sqlSegment);
        assertFalse(sqlSegment.contains("category_code"),
                "非持久化字段 categoryCode 不得被拼进查询条件，实际：" + sqlSegment);
        assertFalse(wrapper.getParamNameValuePairs().containsValue("半成品"),
                "非持久化字段 categoryName 的值不得出现在 SQL 参数中");
        assertFalse(wrapper.getParamNameValuePairs().containsValue("C01"),
                "非持久化字段 categoryCode 的值不得出现在 SQL 参数中");
    }

    /** 查不到时返回「未找到对应实体」。 */
    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doReturn(null).when(service).getById("404");

        Result<WmsMaterial> result = controller(service).queryById("404");

        assertEquals(false, result.isSuccess());
        assertEquals("未找到对应实体", result.getMessage());
    }

    /** 查到实体时回填分类信息并返回 200。 */
    @Test
    void queryById_shouldReturnOkAndFillCategoryInfo() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        WmsMaterial entity = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓").setCategoryId("C1");
        entity.setId("1");
        doReturn(entity).when(service).getById("1");

        Result<WmsMaterial> result = controller(service).queryById("1");

        assertEquals(200, result.getCode());
        verify(service).fillCategoryInfo(any());
    }

    // ------------------------------------------------------------ 编码唯一性校验

    /** 空编码必须被挡下。 */
    @Test
    void checkCode_shouldRejectBlankCode() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<Boolean> result = controller(service).checkCode("", null);

        assertEquals(false, result.isSuccess());
        assertEquals("物料编码不能为空", result.getMessage());
    }

    /** 编码已占用时给出提示。 */
    @Test
    void checkCode_shouldRejectExistingCode() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doReturn(true).when(service).checkCodeExists("M001", null);

        Result<Boolean> result = controller(service).checkCode("M001", null);

        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 编码可用时返回 200。 */
    @Test
    void checkCode_shouldReturnOkWhenFree() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doReturn(false).when(service).checkCodeExists("M001", null);

        Result<Boolean> result = controller(service).checkCode("M001", null);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getResult());
    }

    // ------------------------------------------------------------ 增改停删

    /** 新增成功（BD001-T09）。 */
    @Test
    void add_shouldReturnOk() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<WmsMaterial> result = controller(service)
                .add(new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓"));

        assertEquals(200, result.getCode());
        assertEquals("添加成功！", result.getMessage());
    }

    /** 编码重复时消息原样透出（BD001-T10）。 */
    @Test
    void add_shouldReturnErrorOnDuplicateCode() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).addMaterial(any());

        Result<WmsMaterial> result = controller(service)
                .add(new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓"));

        assertEquals(false, result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 编辑成功（BD001-T12）。 */
    @Test
    void edit_shouldReturnOk() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        WmsMaterial input = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓（改名）");
        input.setId("1");

        Result<WmsMaterial> result = controller(service).edit(input);

        assertEquals(200, result.getCode());
        assertEquals("修改成功！", result.getMessage());
        verify(service).editMaterial(input);
    }

    /** 编辑撞码时消息原样透出。 */
    @Test
    void edit_shouldReturnErrorOnDuplicateCode() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doThrow(new RuntimeException(MSG_DUPLICATED)).when(service).editMaterial(any());

        Result<WmsMaterial> result = controller(service)
                .edit(new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓"));

        assertEquals(false, result.isSuccess());
        assertEquals(MSG_DUPLICATED, result.getMessage());
    }

    /** 停用成功。 */
    @Test
    void disable_shouldReturnOk() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<?> result = controller(service).disable("1");

        assertEquals(200, result.getCode());
        assertEquals("停用成功！", result.getMessage());
    }

    /** 删除成功（BD001-T13）。 */
    @Test
    void delete_shouldReturnOk() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<?> result = controller(service).delete("1");

        assertEquals(200, result.getCode());
        assertEquals("删除成功！", result.getMessage());
    }

    /** 批量删除的空 ids 必须被挡下。 */
    @Test
    void deleteBatch_shouldRejectBlankIds() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<?> result = controller(service).deleteBatch("");

        assertEquals(false, result.isSuccess());
        assertEquals("参数不识别！", result.getMessage());
    }

    /** 批量删除按逗号切分 ids 并原样传给服务层。 */
    @Test
    void deleteBatch_shouldParseCommaSeparatedIds() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        Result<?> result = controller(service).deleteBatch("1,2,3");

        assertEquals(200, result.getCode());
        verify(service).removeBatch(Arrays.asList("1", "2", "3"));
    }

    // ------------------------------------------------------------ 导出（BD001-T18）

    /** 导出必须组装出 Excel 视图、含数据列表与文件名。 */
    @Test
    void exportXls_shouldBuildExcelModelAndView() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        List<WmsMaterial> list = Collections.singletonList(
                new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓"));
        doReturn(list).when(service).list(any(Wrapper.class));

        ModelAndView mv = controller(service).exportXls(new MockHttpServletRequest(), new WmsMaterial());

        assertInstanceOf(JeecgEntityExcelView.class, mv.getView());
        assertEquals("物料列表", mv.getModel().get(NormalExcelConstants.FILE_NAME));
        assertEquals(list, mv.getModel().get(NormalExcelConstants.DATA_LIST));
        verify(service).fillCategoryInfo(list);
    }

    /** 无数据导出同样要能组装视图，不得报错。 */
    @Test
    void exportXls_shouldWorkWithEmptyResult() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        ModelAndView mv = controller(service).exportXls(new MockHttpServletRequest(), new WmsMaterial());

        assertInstanceOf(JeecgEntityExcelView.class, mv.getView());
        assertTrue(((List<?>) mv.getModel().get(NormalExcelConstants.DATA_LIST)).isEmpty());
    }

    /** 下载空白模板：数据列表为空，文件名与导出不同。 */
    @Test
    void importTemplate_shouldReturnEmptyTemplate() {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        ModelAndView mv = controller(service).importTemplate();

        assertInstanceOf(JeecgEntityExcelView.class, mv.getView());
        assertEquals("物料导入模板", mv.getModel().get(NormalExcelConstants.FILE_NAME));
        assertTrue(((List<?>) mv.getModel().get(NormalExcelConstants.DATA_LIST)).isEmpty());
    }

    // ------------------------------------------------------------ 批量导入（BD001-T15/T16/T17）

    /** 全部合法时必须逐行入库，且补齐分类ID/默认状态/清空 id（BD001-T15）。 */
    @Test
    void importExcel_shouldImportAllRows() throws IOException {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        when(service.resolveCategoryIdByCode("CAT-BAN")).thenReturn("C1");
        when(service.checkCodeExists(any(), any())).thenReturn(false);

        MockMultipartHttpServletRequest request = multipartRequest(WmsTestSupport.materialXlsx(Arrays.asList(
                new String[]{"M001", "螺栓", "CAT-BAN", "M8x20"},
                new String[]{"M002", "螺母", "CAT-BAN", "M8"})));

        Result<?> result = controller(service)
                .importExcel(request, new MockHttpServletResponse());

        assertEquals(200, result.getCode());
        assertEquals("共2行数据全部导入成功！", result.getMessage());

        ArgumentCaptor<WmsMaterial> captor = ArgumentCaptor.forClass(WmsMaterial.class);
        verify(service, times(2)).save(captor.capture());
        WmsMaterial first = captor.getAllValues().get(0);
        assertEquals("M001", first.getMaterialCode());
        assertEquals("C1", first.getCategoryId(), "导入必须按分类编码解析出 categoryId");
        assertEquals(WmsConstants.STATUS_ENABLE, first.getStatus());
        assertNull(first.getId(), "导入一律新增，id 必须清空");
        assertEquals(0, first.getDelFlag());
    }

    /**
     * 出错行必须报出**物理 Excel 行号**，且不影响同文件其它行入库（BD001-T16 / Review Focus #1）。
     *
     * <p>模板是「两行标题 + 一行表头」，故列表下标 i 对应物理第 i+4 行。行号算错会让用户照着提示改错行。
     */
    @Test
    void importExcel_shouldReportPhysicalRowNumbers() throws IOException {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        when(service.resolveCategoryIdByCode("CAT-BAN")).thenReturn("C1");
        when(service.resolveCategoryIdByCode("NO_SUCH")).thenReturn(null);
        when(service.checkCodeExists(any(), any())).thenReturn(false);

        MockMultipartHttpServletRequest request = multipartRequest(WmsTestSupport.materialXlsx(Arrays.asList(
                new String[]{"M001", "螺栓", "NO_SUCH", ""},   // 物理第 4 行：分类不存在
                new String[]{"", "缺编码", "CAT-BAN", ""},      // 物理第 5 行：编码为空
                new String[]{"M003", "垫片", "CAT-BAN", ""}))); // 物理第 6 行：正常

        Result<?> result = controller(service)
                .importExcel(request, new MockHttpServletResponse());

        // 有错误行时 autopoi 返回 201 与统计对象
        assertEquals(201, result.getCode());
        assertEquals("文件导入成功，但有错误。", result.getMessage());
        Map<?, ?> data = (Map<?, ?>) result.getResult();
        assertEquals(3, ((Number) data.get("totalCount")).intValue());
        assertEquals(2, ((Number) data.get("errorCount")).intValue());
        assertEquals(1, ((Number) data.get("successCount")).intValue());

        // 部分成功：第 6 行必须已入库
        verify(service, times(1)).save(any());

        String errorText = readErrorLog(tempDir);
        assertTrue(errorText.contains("第 4 行"), "错误清单应报出物理行号 4，实际内容：" + errorText);
        assertTrue(errorText.contains("第 5 行"), "错误清单应报出物理行号 5，实际内容：" + errorText);
        assertTrue(errorText.contains("分类编码 NO_SUCH 不存在"), "实际内容：" + errorText);
        assertTrue(errorText.contains("物料编码不能为空"), "实际内容：" + errorText);
    }

    /**
     * 两种「重复」必须给出可区分的提示（Review Focus #2）：
     * 文件内重复 →「物料编码 X 在文件中重复」；库内已存在 →「编码已存在，请重新输入」。
     * 混为一谈会让用户无法判断该改文件还是改库。
     */
    @Test
    void importExcel_shouldDistinguishDuplicateKinds() throws IOException {
        IWmsMaterialService service = mock(IWmsMaterialService.class);
        when(service.resolveCategoryIdByCode("CAT-BAN")).thenReturn("C1");
        when(service.checkCodeExists(eq("M099"), any())).thenReturn(true);
        when(service.checkCodeExists(eq("M001"), any())).thenReturn(false);

        MockMultipartHttpServletRequest request = multipartRequest(WmsTestSupport.materialXlsx(Arrays.asList(
                new String[]{"M001", "螺栓", "CAT-BAN", ""},   // 物理第 4 行：正常
                new String[]{"M001", "螺母", "CAT-BAN", ""},   // 物理第 5 行：文件内重复
                new String[]{"M099", "垫片", "CAT-BAN", ""}))); // 物理第 6 行：库内已存在

        Result<?> result = controller(service)
                .importExcel(request, new MockHttpServletResponse());

        assertEquals(201, result.getCode());
        String errorText = readErrorLog(tempDir);
        assertTrue(errorText.contains("物料编码 M001 在文件中重复"),
                "文件内重复必须与库内重复区分开，实际内容：" + errorText);
        assertTrue(errorText.contains("编码已存在，请重新输入"),
                "库内已存在必须给出既有提示，实际内容：" + errorText);
        verify(service, times(1)).save(any());
    }

    /**
     * ⚠ 现状记录（BD001-T17 缺口 G-17）：**空文件不报「文件内容为空」**。
     *
     * <p>空 xlsx 解析出 0 行，走 {@code imporReturnRes(0, 0, [])} 的「无错误」分支，
     * 返回「共0行数据全部导入成功！」——与蓝图期望的「文件内容为空」不符。
     * 本用例固化当前行为，供将来补上该提示时立即变红。
     */
    @Test
    void importExcel_emptyFile_shouldReturnZeroRowsMessage() throws IOException {
        IWmsMaterialService service = mock(IWmsMaterialService.class);

        MockMultipartHttpServletRequest request = multipartRequest(WmsTestSupport.materialXlsx(null));

        Result<?> result = controller(service)
                .importExcel(request, new MockHttpServletResponse());

        assertEquals(200, result.getCode());
        assertEquals("共0行数据全部导入成功！", result.getMessage());
        verify(service, times(0)).save(any());
    }
}