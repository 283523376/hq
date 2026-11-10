package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.springframework.web.servlet.ModelAndView;
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
 * @Description: 客户档案（受托方口径，V2.4）
 *
 * <p>受托方与金蝶保持一致：金蝶里受托方是「客户」（BD_Customer），
 * 因此 WMS 新增客户档案，不再复用供应商档案。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Tag(name = "WMS-客户档案")
@RestController
@RequestMapping("/wms/customer")
@Slf4j
public class WmsCustomerController extends JeecgController<WmsCustomer, IWmsCustomerService> {

    /**
     * 分页列表查询（支持 customerCode / customerName / status）。
     */
    @Operation(summary = "客户-分页列表查询")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:customer:list")
    public Result<IPage<WmsCustomer>> queryPageList(WmsCustomer wmsCustomer,
                                                   @RequestParam(name = "keyword", required = false) String keyword,
                                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                   HttpServletRequest req) {
        // 查询窗口模糊匹配：QueryGenerator 对 String 拼的是等值，文本字段置空后手写 like，
        // 让「任意连续子串」都能命中（2026-11-02 全项目口径）。
        String codeKeyword = wmsCustomer.getCustomerCode();
        String nameKeyword = wmsCustomer.getCustomerName();
        wmsCustomer.setCustomerCode(null);
        wmsCustomer.setCustomerName(null);
        QueryWrapper<WmsCustomer> queryWrapper = QueryGenerator.initQueryWrapper(wmsCustomer, req.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(codeKeyword), "customer_code",
                codeKeyword == null ? null : codeKeyword.trim());
        queryWrapper.like(oConvertUtils.isNotEmpty(nameKeyword), "customer_name",
                nameKeyword == null ? null : nameKeyword.trim());
        // 全局关键词：一次跨字段模糊，与单字段框是 AND 关系；OR 必须包在 and(...) 里
        if (oConvertUtils.isNotEmpty(keyword)) {
            final String kw = keyword.trim();
            queryWrapper.and(w -> w.like("customer_code", kw)
                    .or().like("customer_name", kw)
                    .or().like("contact", kw)
            );
        }
        queryWrapper.orderByDesc("create_time");
        Page<WmsCustomer> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    /**
     * 取全部启用中的客户（受托方下拉用）。清空非必要字段以减小报文。
     *
     * <p>不挂 {@code wms:customer:list}：到货登记、台账筛选等页面都要用它取下拉，
     * 若只有客户档案页的访问者能拿到，其它角色会看到空下拉。
     */
    @Operation(summary = "客户-启用列表（受托方下拉用）")
    @GetMapping(value = "/listAll")
    public Result<List<WmsCustomer>> listAll() {
        List<WmsCustomer> list = service.listAllEnabled();
        list.forEach(item -> {
            item.setContact(null);
            item.setPhone(null);
        });
        return Result.OK(list);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "客户-通过id查询")
    @GetMapping(value = "/queryById")
    @RequiresPermissions("wms:customer:list")
    public Result<WmsCustomer> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsCustomer entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.OK(entity);
    }

    /**
     * 客户编码唯一性校验
     */
    @Operation(summary = "客户-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("客户编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_CREATE,
            bizNoSpEL = "#wmsCustomer.customerCode")
    @Operation(summary = "客户-添加")
    @PostMapping(value = "/add")
    @RequiresPermissions("wms:customer:create")
    public Result<WmsCustomer> add(@RequestBody WmsCustomer wmsCustomer) {
        try {
            service.addCustomer(wmsCustomer);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_UPDATE,
            bizNoSpEL = "#wmsCustomer.customerCode")
    @Operation(summary = "客户-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    @RequiresPermissions("wms:customer:edit")
    public Result<WmsCustomer> edit(@RequestBody WmsCustomer wmsCustomer) {
        try {
            service.editCustomer(wmsCustomer);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 停用
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_UPDATE,
            bizNoSpEL = "#id")
    @Operation(summary = "客户-停用")
    @PostMapping(value = "/disable")
    @RequiresPermissions("wms:customer:edit")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        try {
            service.disableCustomer(id);
            return Result.OK("停用成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 通过id删除。拒绝原因必须原样透出（JeecgBootExceptionHandler 会给未捕获异常加前缀），
     * 故显式 catch。
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_DELETE,
            bizNoSpEL = "#id")
    @Operation(summary = "客户-删除")
    @DeleteMapping(value = "/delete")
    @RequiresPermissions("wms:customer:delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeCustomer(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除。守卫在 service.removeBatch 的事务内逐条生效，任一条被拒则整体回滚。
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_DELETE,
            bizNoSpEL = "#ids")
    @Operation(summary = "客户-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    @RequiresPermissions("wms:customer:delete")
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
    @Operation(summary = "客户-删除预检")
    @GetMapping(value = "/checkDelete")
    public Result<WmsDeleteCheckVO> checkDelete(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(service.checkDelete(id));
    }

    /** 批量删除预检：列出不可删明细。 */
    @Operation(summary = "客户-批量删除预检")
    @GetMapping(value = "/checkDeleteBatch")
    public Result<WmsBatchDeleteCheckVO> checkDeleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        return Result.OK(service.checkDeleteBatch(Arrays.asList(ids.split(","))));
    }

    /**
     * 导出 Excel（导出的是「按当前查询条件命中的全部行」，不是当前页）。
     *
     * <p>导出能力挂在 {@code wms:customer:list} 上：本期按钮权限只有
     * list/create/edit/delete 四个，导出属查询的延伸，不再单开权限码。
     */
    @Operation(summary = "客户-导出")
    @RequestMapping(value = "/exportXls", method = RequestMethod.GET)
    @RequiresPermissions("wms:customer:list")
    public ModelAndView exportXls(HttpServletRequest request, WmsCustomer wmsCustomer) {
        // 文本字段走 like（与列表同口径），QueryGenerator 对 String 拼等值在这里不适用
        String codeKeyword = wmsCustomer.getCustomerCode();
        String nameKeyword = wmsCustomer.getCustomerName();
        wmsCustomer.setCustomerCode(null);
        wmsCustomer.setCustomerName(null);
        QueryWrapper<WmsCustomer> queryWrapper = QueryGenerator.initQueryWrapper(wmsCustomer, request.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(codeKeyword), "customer_code",
                codeKeyword == null ? null : codeKeyword.trim());
        queryWrapper.like(oConvertUtils.isNotEmpty(nameKeyword), "customer_name",
                nameKeyword == null ? null : nameKeyword.trim());
        String selections = request.getParameter("selections");
        if (oConvertUtils.isNotEmpty(selections)) {
            queryWrapper.in("id", Arrays.asList(selections.split(",")));
        }
        queryWrapper.orderByDesc("create_time");
        List<WmsCustomer> list = service.list(queryWrapper);

        ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
        mv.addObject(NormalExcelConstants.DATA_LIST, list);
        mv.addObject(NormalExcelConstants.FILE_NAME, "客户档案");
        mv.addObject(NormalExcelConstants.CLASS, WmsCustomer.class);
        LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        String realname = user == null ? "" : user.getRealname();
        mv.addObject(NormalExcelConstants.PARAMS,
                new ExportParams("客户档案", "导出人:" + realname, "导出信息", ExcelType.XSSF));
        String exportFields = request.getParameter(NormalExcelConstants.EXPORT_FIELDS);
        if (oConvertUtils.isNotEmpty(exportFields)) {
            mv.addObject(NormalExcelConstants.EXPORT_FIELDS, exportFields);
        }
        return mv;
    }
}
