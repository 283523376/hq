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
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
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

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Tag(name = "WMS-订单")
@RestController
@RequestMapping("/wms/order")
@Slf4j
public class WmsOrderController extends JeecgController<WmsOrder, IWmsOrderService> {

    /**
     * 分页列表查询（支持 orderNo / orderType / orderStatus / syncStatus / supplierId / 创建时间区间）
     */
    @Operation(summary = "订单-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsOrder>> queryPageList(WmsOrder wmsOrder,
                                                @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                HttpServletRequest req) {
        // 代码逻辑说明: supplierName 是非持久化字段（表里无对应列），必须先置空，
        // 否则 QueryGenerator 会按它拼出 supplier_name 条件导致 SQL 报错
        wmsOrder.setSupplierName(null);
        QueryWrapper<WmsOrder> queryWrapper = QueryGenerator.initQueryWrapper(wmsOrder, req.getParameterMap());
        queryWrapper.orderByDesc("create_time");
        Page<WmsOrder> page = new Page<>(pageNo, pageSize);
        IPage<WmsOrder> pageList = service.page(page, queryWrapper);
        service.fillSupplierName(pageList.getRecords());
        return Result.OK(pageList);
    }

    /**
     * 通过id查询
     */
    @Operation(summary = "订单-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsOrder> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsOrder entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        service.fillSupplierName(java.util.Collections.singletonList(entity));
        return Result.OK(entity);
    }

    /**
     * 订单号唯一性校验
     */
    @Operation(summary = "订单-订单号唯一性校验")
    @GetMapping(value = "/checkOrderNo")
    public Result<Boolean> checkOrderNo(@RequestParam(name = "code") String code,
                                        @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("订单号不能为空");
        }
        if (service.checkOrderNoExists(code, id)) {
            return Result.error("订单号已存在，请重新输入");
        }
        return Result.OK(true);
    }

    /**
     * 添加
     */
    @AutoLog(value = "订单-添加", operateType = 2)
    @Operation(summary = "订单-添加")
    @PostMapping(value = "/add")
    public Result<WmsOrder> add(@RequestBody WmsOrder wmsOrder) {
        try {
            service.addOrder(wmsOrder);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑
     */
    @AutoLog(value = "订单-编辑", operateType = 3)
    @Operation(summary = "订单-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsOrder> edit(@RequestBody WmsOrder wmsOrder) {
        try {
            service.editOrder(wmsOrder);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 审核（通过 / 驳回）
     */
    @AutoLog(value = "订单-审核", operateType = 3)
    @Operation(summary = "订单-审核")
    @PostMapping(value = "/audit")
    public Result<?> audit(@RequestBody WmsOrderAuditDTO dto) {
        try {
            service.auditOrder(dto);
            return Result.OK(Boolean.TRUE.equals(dto.getPass()) ? "审核通过！" : "已驳回！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 下达（内部触发同步 ERP/MES）
     */
    @AutoLog(value = "订单-下达", operateType = 3)
    @Operation(summary = "订单-下达")
    @PostMapping(value = "/dispatch")
    public Result<WmsOrderSyncOutcomeVO> dispatch(@RequestParam(name = "id", required = true) String id) {
        try {
            return Result.OK("下达成功！", service.dispatchOrder(id));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 重新同步（仅同步失败/待同步的已下达订单）
     */
    @AutoLog(value = "订单-重新同步", operateType = 3)
    @Operation(summary = "订单-重新同步")
    @PostMapping(value = "/resync")
    public Result<WmsOrderSyncOutcomeVO> resync(@RequestParam(name = "id", required = true) String id) {
        try {
            return Result.OK("已重新同步！", service.resyncOrder(id));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 通过id删除
     */
    @AutoLog(value = "订单-删除", operateType = 4)
    @Operation(summary = "订单-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeOrder(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除
     */
    @AutoLog(value = "订单-批量删除", operateType = 4)
    @Operation(summary = "订单-批量删除")
    @DeleteMapping(value = "/deleteBatch")
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
}
