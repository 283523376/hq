package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @Description: 物料分类
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Tag(name = "WMS-物料分类")
@RestController
@RequestMapping("/wms/materialCategory")
@Slf4j
public class WmsMaterialCategoryController extends JeecgController<WmsMaterialCategory, IWmsMaterialCategoryService> {

    /**
     * 分类树（全量，含 children）
     */
    @Operation(summary = "物料分类-分类树")
    @GetMapping(value = "/treeList")
    public Result<List<SelectTreeModel>> treeList() {
        return Result.OK(service.queryTreeList());
    }

    /**
     * 分页列表查询
     */
    @Operation(summary = "物料分类-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsMaterialCategory>> queryPageList(WmsMaterialCategory wmsMaterialCategory,
                                                            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                            HttpServletRequest req) {
        QueryWrapper<WmsMaterialCategory> queryWrapper = QueryGenerator.initQueryWrapper(wmsMaterialCategory, req.getParameterMap());
        queryWrapper.orderByAsc("category_code");
        Page<WmsMaterialCategory> page = new Page<>(pageNo, pageSize);
        IPage<WmsMaterialCategory> pageList = service.page(page, queryWrapper);
        return Result.OK(pageList);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "物料分类-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsMaterialCategory> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsMaterialCategory entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.OK(entity);
    }

    /**
     * 分类编码唯一性校验
     */
    @Operation(summary = "物料分类-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("分类编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "物料分类-添加", operateType = 2)
    @Operation(summary = "物料分类-添加")
    @PostMapping(value = "/add")
    public Result<WmsMaterialCategory> add(@RequestBody WmsMaterialCategory wmsMaterialCategory) {
        try {
            service.addCategory(wmsMaterialCategory);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "物料分类-编辑", operateType = 3)
    @Operation(summary = "物料分类-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsMaterialCategory> edit(@RequestBody WmsMaterialCategory wmsMaterialCategory) {
        try {
            service.editCategory(wmsMaterialCategory);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 停用
     */
    @AutoLog(value = "物料分类-停用", operateType = 3)
    @Operation(summary = "物料分类-停用")
    @PostMapping(value = "/disable")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableCategory(id);
        return Result.OK("停用成功！");
    }

    /**
     * 删除（分类下存在物料或子分类时拒绝）
     */
    @AutoLog(value = "物料分类-删除", operateType = 4)
    @Operation(summary = "物料分类-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeCategory(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }
}
