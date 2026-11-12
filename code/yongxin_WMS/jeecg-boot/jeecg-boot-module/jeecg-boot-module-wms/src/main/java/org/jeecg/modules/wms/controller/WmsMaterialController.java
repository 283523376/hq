package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.ImportExcelUtil;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.util.WmsMaterialGroupMapping;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 物料主数据
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Tag(name = "WMS-物料")
@RestController
@RequestMapping("/wms/material")
@Slf4j
public class WmsMaterialController extends JeecgController<WmsMaterial, IWmsMaterialService> {

    private final org.jeecg.modules.wms.service.IWmsKingdeeMaterialSyncService kingdeeMaterialSyncService;

    public WmsMaterialController(org.jeecg.modules.wms.service.IWmsKingdeeMaterialSyncService kingdeeMaterialSyncService) {
        this.kingdeeMaterialSyncService = kingdeeMaterialSyncService;
    }

    /**
     * 物料-从金蝶同步（BD_MATERIAL → wms_material，恒等映射；自动化优先 P0）
     */
    @AutoLog(value = "物料-从金蝶同步", operateType = 2)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_SYNC_IN,
            bizNoSpEL = "'" + WmsConstants.AUDIT_BIZ_NO_MASTER_DATA_SYNC + "'")
    @Operation(summary = "物料-从金蝶同步")
    @PostMapping(value = "/sync-from-kingdee")
    @RequiresPermissions("wms:material:add")
    public Result<org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO> syncFromKingdee() {
        try {
            return Result.OK("同步完成", kingdeeMaterialSyncService.syncMaterial());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页列表查询（支持 categoryId / materialCode / materialName / status）
     */
    @Operation(summary = "物料-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsMaterial>> queryPageList(WmsMaterial wmsMaterial,
                                                    @RequestParam(name = "keyword", required = false) String keyword,
                                                    @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                    @RequestParam(name = "categoryType", required = false) String categoryType,
                                                    HttpServletRequest req) {
        // 代码逻辑说明: categoryName/categoryCode 是非持久化字段（表里无对应列），
        // 必须先置空，否则 QueryGenerator 会按它们拼出 category_name/category_code 条件导致 SQL 报错
        wmsMaterial.setCategoryName(null);
        wmsMaterial.setCategoryCode(null);
        // 查询窗口模糊匹配（2026-11-02）：QueryGenerator 对 String 拼的是等值，
        // 这里把文本字段置空后手写 like，让「任意连续子串」都能命中。
        // 兼容历史调用方的 QueryGenerator 约定（值两侧带 * 表示 LIKE）：这里手写 like，
        // 星号会被当字面量 → 任何关键词恒 0 行（2026-11-12 审计发现出库弹窗仍在这么传）。
        String materialCodeKeyword = stripLikeWildcards(wmsMaterial.getMaterialCode());
        String materialNameKeyword = stripLikeWildcards(wmsMaterial.getMaterialName());
        wmsMaterial.setMaterialCode(null);
        wmsMaterial.setMaterialName(null);
        QueryWrapper<WmsMaterial> queryWrapper = QueryGenerator.initQueryWrapper(wmsMaterial, req.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(materialCodeKeyword), "material_code", materialCodeKeyword);
        queryWrapper.like(oConvertUtils.isNotEmpty(materialNameKeyword), "material_name", materialNameKeyword);
                // 全局关键词（2026-11-02）：一次跨字段模糊，与单字段框是 AND 关系。
        // OR 必须包在 and(...) 里，否则会与其它条件串成 xxx AND a OR b，优先级错误。
        final String kw = stripLikeWildcards(keyword);
        if (oConvertUtils.isNotEmpty(kw)) {
            queryWrapper.and(w -> w.like("material_code", kw)
                    .or().like("material_name", kw)
                    .or().like("kingdee_code", kw)
            );
        }
        // 按分类类型过滤（2026-11-05）：到货登记只要原材料，下拉别把辅料/成品也列出来
        // （选完才被后端拒绝，体验很差）。分类类型在 wms_material_category 上，物料表只有 category_id，
        // 所以走子查询；categoryType 先过白名单再拼字符串——值只可能来自固定枚举，不做白名单就是拼 SQL。
        if (oConvertUtils.isNotEmpty(categoryType)) {
            String type = categoryType.trim();
            if (!WmsMaterialGroupMapping.ALLOWED_TYPES.contains(type)) {
                return Result.error("分类类型非法（允许："
                        + String.join("/", WmsMaterialGroupMapping.ALLOWED_TYPES) + "），实际：" + type);
            }
            queryWrapper.inSql("category_id",
                    "SELECT id FROM wms_material_category WHERE deleted_at = 0 AND category_type = '" + type + "'");
        }
        queryWrapper.orderByDesc("create_time");
        Page<WmsMaterial> page = new Page<>(pageNo, pageSize);
        IPage<WmsMaterial> pageList = service.page(page, queryWrapper);
        service.fillCategoryInfo(pageList.getRecords());
        return Result.OK(pageList);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "物料-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsMaterial> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsMaterial entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        List<WmsMaterial> one = new ArrayList<>(1);
        one.add(entity);
        service.fillCategoryInfo(one);
        return Result.OK(entity);
    }

    /**
     * 物料编码唯一性校验
     */
    @Operation(summary = "物料-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("物料编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "物料-添加", operateType = 2)
    @Operation(summary = "物料-添加")
    @PostMapping(value = "/add")
    @RequiresPermissions("wms:material:add")
    public Result<WmsMaterial> add(@RequestBody WmsMaterial wmsMaterial) {
        try {
            service.addMaterial(wmsMaterial);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "物料-编辑", operateType = 3)
    @Operation(summary = "物料-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    @RequiresPermissions("wms:material:edit")
    public Result<WmsMaterial> edit(@RequestBody WmsMaterial wmsMaterial) {
        try {
            service.editMaterial(wmsMaterial);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 停用
     */
    @AutoLog(value = "物料-停用", operateType = 3)
    @Operation(summary = "物料-停用")
    @PostMapping(value = "/disable")
    @RequiresPermissions("wms:material:edit")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableMaterial(id);
        return Result.OK("停用成功！");
    }

    /**
     * 通过id删除
     */
    @AutoLog(value = "物料-删除", operateType = 4)
    @Operation(summary = "物料-删除")
    @DeleteMapping(value = "/delete")
    @RequiresPermissions("wms:material:delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeMaterial(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除。守卫在 service.removeBatch 的事务内逐条生效，任一条被拒则整体回滚。
     */
    @AutoLog(value = "物料-批量删除", operateType = 4)
    @Operation(summary = "物料-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    @RequiresPermissions("wms:material:delete")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        try {
            service.removeBatch(Arrays.asList(ids.split(",")));
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 删除预检：物料当前恒可删；未来库存表接入后这里会给出禁止原因。 */
    @Operation(summary = "物料-删除预检")
    @GetMapping(value = "/checkDelete")
    public Result<WmsDeleteCheckVO> checkDelete(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(service.checkDelete(id));
    }

    /** 批量删除预检：列出不可删明细。 */
    @Operation(summary = "物料-批量删除预检")
    @GetMapping(value = "/checkDeleteBatch")
    public Result<WmsBatchDeleteCheckVO> checkDeleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        return Result.OK(service.checkDeleteBatch(Arrays.asList(ids.split(","))));
    }

    /**
     * 导出excel
     */
    @Operation(summary = "物料-导出")
    @RequestMapping(value = "/exportXls")
    @RequiresPermissions("wms:material:export")
    public ModelAndView exportXls(HttpServletRequest request, WmsMaterial wmsMaterial) {
        wmsMaterial.setCategoryName(null);
        wmsMaterial.setCategoryCode(null);
        QueryWrapper<WmsMaterial> queryWrapper = QueryGenerator.initQueryWrapper(wmsMaterial, request.getParameterMap());
        // 导出选中数据
        String selections = request.getParameter("selections");
        if (oConvertUtils.isNotEmpty(selections)) {
            queryWrapper.in("id", Arrays.asList(selections.split(",")));
        }
        List<WmsMaterial> list = service.list(queryWrapper);
        service.fillCategoryInfo(list);

        ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
        mv.addObject(NormalExcelConstants.DATA_LIST, list);
        mv.addObject(NormalExcelConstants.FILE_NAME, "物料列表");
        mv.addObject(NormalExcelConstants.CLASS, WmsMaterial.class);
        LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        String realname = user == null ? "" : user.getRealname();
        mv.addObject(NormalExcelConstants.PARAMS,
                new ExportParams("物料列表", "导出人:" + realname, "导出信息", ExcelType.XSSF));
        String exportFields = request.getParameter(NormalExcelConstants.EXPORT_FIELDS);
        if (oConvertUtils.isNotEmpty(exportFields)) {
            mv.addObject(NormalExcelConstants.EXPORT_FIELDS, exportFields);
        }
        return mv;
    }

    /**
     * 下载空白导入模板（与导出格式一致：两行标题 + 一行表头，无数据行）。
     */
    @Operation(summary = "物料-下载导入模板")
    @RequestMapping(value = "/importTemplate")
    @RequiresPermissions("wms:material:import")
    public ModelAndView importTemplate() {
        ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
        mv.addObject(NormalExcelConstants.DATA_LIST, new ArrayList<WmsMaterial>());
        mv.addObject(NormalExcelConstants.FILE_NAME, "物料导入模板");
        mv.addObject(NormalExcelConstants.CLASS, WmsMaterial.class);
        // 第二行放填写说明（同时保证与导入的 titleRows=2 对齐）
        mv.addObject(NormalExcelConstants.PARAMS,
                new ExportParams("物料导入模板", "填写说明：物料编码、物料名称、分类编码为必填；请勿修改或删除前两行与表头行", "导入模板", ExcelType.XSSF));
        return mv;
    }

    /**
     * 通过excel导入数据
     *
     * <p>逐行保存、逐行收集错误，错误信息带**物理 Excel 行号**（两行标题 + 一行表头 → 数据从第 4 行起）。
     * 不使用事务，避免部分成功被整体回滚。
     */
    @AutoLog(value = "物料-导入", operateType = 5)
    @Operation(summary = "物料-导入")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    @RequiresPermissions("wms:material:import")
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) throws IOException {
        MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
        Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
        List<String> errorMessage = new ArrayList<>();
        int successLines = 0;
        int errorLines = 0;
        Set<String> codesInFile = new HashSet<>();

        for (Map.Entry<String, MultipartFile> entry : fileMap.entrySet()) {
            MultipartFile file = entry.getValue();
            ImportParams params = new ImportParams();
            // 与平台导出/导入模板保持一致：第 1 行标题、第 2 行第二标题（导出人 / 填写说明）、第 3 行表头
            params.setTitleRows(2);
            params.setHeadRows(1);
            params.setNeedSave(false);
            try {
                List<WmsMaterial> list = ExcelImportUtil.importExcel(file.getInputStream(), WmsMaterial.class, params);
                for (int i = 0; i < list.size(); i++) {
                    WmsMaterial material = list.get(i);
                    int physicalRow = i + WmsConstants.IMPORT_ROW_OFFSET;
                    try {
                        saveImportedRow(material, codesInFile);
                        successLines++;
                    } catch (Exception e) {
                        errorLines++;
                        errorMessage.add("第 " + physicalRow + " 行：" + e.getMessage());
                        log.error("物料导入第 {} 行失败：{}", physicalRow, e.getMessage());
                    }
                }
            } catch (Exception e) {
                errorMessage.add("解析文件异常：" + e.getMessage());
                log.error(e.getMessage(), e);
            } finally {
                try {
                    file.getInputStream().close();
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
        return ImportExcelUtil.imporReturnRes(errorLines, successLines, errorMessage);
    }

    /**
     * 校验并保存导入的一行；校验不通过时抛出带原因的异常。
     */
    private void saveImportedRow(WmsMaterial material, Set<String> codesInFile) {
        if (oConvertUtils.isEmpty(material.getMaterialCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        if (oConvertUtils.isEmpty(material.getMaterialName())) {
            throw new RuntimeException("物料名称不能为空");
        }
        if (oConvertUtils.isNotEmpty(material.getCategoryCode())) {
            String categoryId = service.resolveCategoryIdByCode(material.getCategoryCode());
            if (oConvertUtils.isEmpty(categoryId)) {
                throw new RuntimeException("分类编码 " + material.getCategoryCode() + " 不存在");
            }
            material.setCategoryId(categoryId);
        }
        if (!codesInFile.add(material.getMaterialCode())) {
            throw new RuntimeException("物料编码 " + material.getMaterialCode() + " 在文件中重复");
        }
        if (service.checkCodeExists(material.getMaterialCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(material.getStatus())) {
            material.setStatus(WmsConstants.STATUS_ENABLE);
        }
        // 导入一律新增：清空 id，由 MyBatis-Plus 生成
        material.setId(null);
        // 显式标记「未删除」：删除时间戳方案下 0 = 未删除（见 WmsMaterial.deletedAt）
        material.setDeletedAt(0L);
        Date now = new Date();
        material.setCreateTime(now);
        material.setUpdateTime(now);
        material.setCreateBy(org.jeecg.modules.wms.util.WmsUtils.currentUsername());
        material.setUpdateBy(material.getCreateBy());
        service.save(material);
    }

    /**
     * 去掉首尾的 {@code *}：JeecgBoot 的 QueryGenerator 用「两侧带 * 表示 LIKE」，
     * 本控制器的列表查询是手写 {@code like}，不剥星号会把 {@code *接头*} 当字面量匹配。
     * 保留中间星号（那是真正的通配意图，MyBatis-Plus 的 like 会转成 % 中的一部分）。
     */
    static String stripLikeWildcards(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().replaceAll("^\\*+|\\*+$", "").trim();
    }
}
