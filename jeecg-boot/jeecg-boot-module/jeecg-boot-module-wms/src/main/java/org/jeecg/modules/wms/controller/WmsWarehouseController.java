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
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
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
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-库区")
@RestController
@RequestMapping("/wms/warehouse")
@Slf4j
public class WmsWarehouseController extends JeecgController<WmsWarehouse, IWmsWarehouseService> {

    /** 越界库位数要查 wms_location，故由库位服务提供，避免库区服务反向依赖库位实体之外的东西。 */
    @Autowired
    private IWmsLocationService wmsLocationService;

    @Operation(summary = "库区-库区树")
    @GetMapping(value = "/treeList")
    public Result<List<SelectTreeModel>> treeList() {
        return Result.OK(service.queryTreeList());
    }

    @Operation(summary = "库区-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsWarehouse>> queryPageList(WmsWarehouse wmsWarehouse,
                                                     @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                     @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                     HttpServletRequest req) {
        QueryWrapper<WmsWarehouse> queryWrapper = QueryGenerator.initQueryWrapper(wmsWarehouse, req.getParameterMap());
        queryWrapper.orderByAsc("warehouse_code");
        Page<WmsWarehouse> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    @Operation(summary = "库区-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsWarehouse> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsWarehouse entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        entity.setOutOfEnvelopeCount(wmsLocationService.countOutOfEnvelope(id));
        return Result.OK(entity);
    }

    /**
     * 位于当前维度之外的库位数。
     *
     * <p>维度是「声明」，库位是「已存在的资产」：缩维后两者可能不一致。
     * 系统**不拦截**这种不一致，只把它显示出来，由人决定是补回维度还是删库位。
     */
    @Operation(summary = "库区-越界库位数")
    @GetMapping(value = "/outOfEnvelopeCount")
    public Result<Long> outOfEnvelopeCount(@RequestParam(name = "warehouseId", required = true) String warehouseId) {
        return Result.OK(wmsLocationService.countOutOfEnvelope(warehouseId));
    }

    @Operation(summary = "库区-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("库区编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("库区编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "库区-添加", operateType = 2)
    @Operation(summary = "库区-添加")
    @PostMapping(value = "/add")
    public Result<WmsWarehouse> add(@RequestBody WmsWarehouse wmsWarehouse) {
        try {
            service.addWarehouse(wmsWarehouse);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-编辑", operateType = 3)
    @Operation(summary = "库区-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsWarehouse> edit(@RequestBody WmsWarehouse wmsWarehouse) {
        try {
            service.editWarehouse(wmsWarehouse);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-停用", operateType = 3)
    @Operation(summary = "库区-停用")
    @PostMapping(value = "/disable")
    public Result<?> disable(@RequestParam(name = "id", required = true) String id) {
        service.disableWarehouse(id);
        return Result.OK("停用成功！");
    }

    @AutoLog(value = "库区-删除", operateType = 4)
    @Operation(summary = "库区-删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeWarehouse(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库区-批量删除", operateType = 4)
    @Operation(summary = "库区-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        try {
            for (String id : ids.split(",")) {
                service.removeWarehouse(id);
            }
            return Result.OK("批量删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }
}