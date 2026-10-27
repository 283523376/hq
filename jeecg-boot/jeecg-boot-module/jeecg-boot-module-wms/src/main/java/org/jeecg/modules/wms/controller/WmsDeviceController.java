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
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-设备")
@RestController
@RequestMapping("/wms/device")
@Slf4j
public class WmsDeviceController extends JeecgController<WmsDevice, IWmsDeviceService> {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Operation(summary = "设备-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsDevice>> queryPageList(WmsDevice wmsDevice,
                                                  @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                  HttpServletRequest req) {
        // 非持久化字段必须先置空，否则 QueryGenerator 会拼出不存在的 warehouse_name 列条件
        wmsDevice.setWarehouseName(null);
        QueryWrapper<WmsDevice> queryWrapper = QueryGenerator.initQueryWrapper(wmsDevice, req.getParameterMap());
        queryWrapper.orderByAsc("device_type").orderByAsc("device_code");
        Page<WmsDevice> page = new Page<>(pageNo, pageSize);
        IPage<WmsDevice> pageList = service.page(page, queryWrapper);
        fillWarehouseNames(pageList.getRecords());
        return Result.OK(pageList);
    }

    @Operation(summary = "设备-未绑定库区的设备列表（绑定弹窗数据源）")
    @GetMapping(value = "/listUnbound")
    public Result<List<WmsDevice>> listUnbound() {
        QueryWrapper<WmsDevice> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNull("warehouse_id").orderByAsc("device_code");
        return Result.OK(service.list(queryWrapper));
    }

    @Operation(summary = "设备-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsDevice> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsDevice entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        fillWarehouseNames(Collections.singletonList(entity));
        return Result.OK(entity);
    }

    @Operation(summary = "设备-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("设备编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("设备编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "设备-添加", operateType = 2)
    @Operation(summary = "设备-添加")
    @PostMapping(value = "/add")
    @RequiresPermissions("wms:device:add")
    public Result<WmsDevice> add(@RequestBody WmsDevice wmsDevice) {
        try {
            service.addDevice(wmsDevice);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-编辑", operateType = 3)
    @Operation(summary = "设备-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    @RequiresPermissions("wms:device:edit")
    public Result<WmsDevice> edit(@RequestBody WmsDevice wmsDevice) {
        try {
            service.editDevice(wmsDevice);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 绑定设备到库区。
     *
     * <p><b>注意返回语义</b>：绑定成功即 {@code Result.OK}，即使有离线设备。
     * 离线告警放在 {@code warning} 字段里，前端用 {@code useMessage().warning} 展示 ——
     * 若走 {@code Result.error}，使用者会误判成「绑定失败」。
     */
    @AutoLog(value = "设备-绑定库区", operateType = 3)
    @Operation(summary = "设备-绑定归属库区")
    @PostMapping(value = "/bind")
    @RequiresPermissions("wms:device:bind")
    public Result<WmsDeviceBindResultVO> bind(@RequestBody WmsDeviceBindDTO dto) {
        try {
            return Result.OK(service.bind(dto));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-解绑库区", operateType = 3)
    @Operation(summary = "设备-解绑库区")
    @PostMapping(value = "/unbind")
    @RequiresPermissions("wms:device:bind")
    public Result<?> unbind(@RequestBody List<String> ids) {
        try {
            service.unbind(ids);
            return Result.OK("解绑成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 设备侧回传接口：调用方是 WCS/RMS（机器），不是登录用户，
     * 因此**不能**加 {@code @RequiresPermissions}——否则上游回传会因无 token 全部 401，
     * 设备在线状态与离线告警随之失效。仅靠登录态外的网关/内网访问控制保护。
     */
    @AutoLog(value = "设备-状态上报", operateType = 3)
    @Operation(summary = "设备-状态上报（WCS/RMS 回传）")
    @PostMapping(value = "/reportStatus")
    public Result<?> reportStatus(@RequestBody WmsDeviceStatusDTO dto) {
        try {
            service.reportStatus(dto);
            return Result.OK("上报成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "设备-删除", operateType = 4)
    @Operation(summary = "设备-删除")
    @DeleteMapping(value = "/delete")
    @RequiresPermissions("wms:device:delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeDevice(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 批量删除。守卫在 service.removeBatch 的事务内逐条生效，任一条被拒则整体回滚。 */
    @AutoLog(value = "设备-批量删除", operateType = 4)
    @Operation(summary = "设备-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    @RequiresPermissions("wms:device:delete")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        try {
            service.removeBatch(Arrays.asList(ids.split(",")));
            return Result.OK("批量删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 删除预检：绑定中的设备在这里给出「先解绑」提示；后端守卫仍在删除时兜底。 */
    @Operation(summary = "设备-删除预检")
    @GetMapping(value = "/checkDelete")
    public Result<WmsDeleteCheckVO> checkDelete(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(service.checkDelete(id));
    }

    /** 批量删除预检：列出不可删明细。 */
    @Operation(summary = "设备-批量删除预检")
    @GetMapping(value = "/checkDeleteBatch")
    public Result<WmsBatchDeleteCheckVO> checkDeleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("参数不识别！");
        }
        return Result.OK(service.checkDeleteBatch(Arrays.asList(ids.split(","))));
    }

    /**
     * 回填库区名。
     *
     * <p>设备可能未绑定（{@code warehouse_id} 为 null），也可能指向已逻辑删除的库区 ——
     * 两种情况下 {@code listByIds} 都查不到，此处留 {@code null} 而不是抛异常。
     */
    private void fillWarehouseNames(List<WmsDevice> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<String> warehouseIds = records.stream()
                .map(WmsDevice::getWarehouseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (warehouseIds.isEmpty()) {
            return;
        }
        Map<String, String> nameById = wmsWarehouseService.listByIds(warehouseIds).stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName, (a, b) -> a));
        for (WmsDevice record : records) {
            record.setWarehouseName(nameById.get(record.getWarehouseId()));
        }
    }
}