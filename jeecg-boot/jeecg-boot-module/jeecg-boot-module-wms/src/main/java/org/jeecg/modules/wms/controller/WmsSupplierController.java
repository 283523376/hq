package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 * @deprecated 方案 A（原材料+焊接线边仓）已移出项目范围；菜单已收回，
 *             保留代码与数据用于历史查询与回滚，不再新增入口。
 */
@Deprecated
@Tag(name = "WMS-供应商")
@RestController
@RequestMapping("/wms/supplier")
@Slf4j
public class WmsSupplierController extends JeecgController<WmsSupplier, IWmsSupplierService> {

    /**
     * 分页列表查询（支持 supplierCode / supplierName / status）
     */
    @Operation(summary = "供应商-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsSupplier>> queryPageList(WmsSupplier wmsSupplier,
                                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                   HttpServletRequest req) {
        // 查询窗口模糊匹配（2026-11-02）：QueryGenerator 对 String 拼的是等值，
        // 这里把文本字段置空后手写 like，让「任意连续子串」都能命中。
        String supplierCodeKeyword = wmsSupplier.getSupplierCode();
        String supplierNameKeyword = wmsSupplier.getSupplierName();
        wmsSupplier.setSupplierCode(null);
        wmsSupplier.setSupplierName(null);
        QueryWrapper<WmsSupplier> queryWrapper = QueryGenerator.initQueryWrapper(wmsSupplier, req.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(supplierCodeKeyword), "supplier_code",
                supplierCodeKeyword == null ? null : supplierCodeKeyword.trim());
        queryWrapper.like(oConvertUtils.isNotEmpty(supplierNameKeyword), "supplier_name",
                supplierNameKeyword == null ? null : supplierNameKeyword.trim());
        queryWrapper.orderByDesc("create_time");
        Page<WmsSupplier> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    /**
     * 取全部启用中的供应商（订单页下拉用）。清空非必要字段以减小报文。
     */
    @Operation(summary = "供应商-启用列表（下拉用）")
    @GetMapping(value = "/listAll")
    public Result<List<WmsSupplier>> listAll() {
        List<WmsSupplier> list = service.listAllEnabled();
        list.forEach(item -> {
            item.setContact(null);
            item.setPhone(null);
            item.setSupplyScope(null);
        });
        return Result.OK(list);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "供应商-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsSupplier> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsSupplier entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.OK(entity);
    }

    /**
     * 供应商编码唯一性校验
     */
    @Operation(summary = "供应商-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("供应商编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "供应商-添加", operateType = 2)
    @Operation(summary = "供应商-添加")
    @PostMapping(value = "/add")
    @RequiresPermissions("wms:supplier:add")
    public Result<WmsSupplier> add(@RequestBody WmsSupplier wmsSupplier) {
        try {
            service.addSupplier(wmsSupplier);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "供应商-编辑", operateType = 3)
    @Operation(summary = "供应商-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    @RequiresPermissions("wms:supplier:edit")
    public Result<WmsSupplier> edit(@RequestBody WmsSupplier wmsSupplier) {
        try {
            service.editSupplier(wmsSupplier);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 停用
     */
    @AutoLog(value = "供应商-停用", operateType = 3)
    @Operation(summary = "供应商-停用")
    @PostMapping(value = "/disable")
    @RequiresPermissions("wms:supplier:edit")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableSupplier(id);
        return Result.OK("停用成功！");
    }

    /**
     * 通过id删除。拒绝原因必须原样透出（JeecgBootExceptionHandler 会给未捕获异常加前缀，
     * 破坏 TC-BD002-006 的逐字文案），故显式 catch。
     */
    @AutoLog(value = "供应商-删除", operateType = 4)
    @Operation(summary = "供应商-删除")
    @DeleteMapping(value = "/delete")
    @RequiresPermissions("wms:supplier:delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeSupplier(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除。守卫在 service.removeBatch 的事务内逐条生效，任一条被拒则整体回滚。
     */
    @AutoLog(value = "供应商-批量删除", operateType = 4)
    @Operation(summary = "供应商-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    @RequiresPermissions("wms:supplier:delete")
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

    /** 删除预检：前端弹确认框前调用（只读）；后端守卫仍在删除时兜底。 */
    @Operation(summary = "供应商-删除预检")
    @GetMapping(value = "/checkDelete")
    public Result<WmsDeleteCheckVO> checkDelete(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(service.checkDelete(id));
    }

    /** 批量删除预检：列出不可删明细。 */
    @Operation(summary = "供应商-批量删除预检")
    @GetMapping(value = "/checkDeleteBatch")
    public Result<WmsBatchDeleteCheckVO> checkDeleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        return Result.OK(service.checkDeleteBatch(Arrays.asList(ids.split(","))));
    }
}
