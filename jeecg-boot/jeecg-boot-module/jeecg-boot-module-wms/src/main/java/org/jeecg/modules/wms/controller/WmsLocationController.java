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
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
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
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Tag(name = "WMS-库位")
@RestController
@RequestMapping("/wms/location")
@Slf4j
public class WmsLocationController extends JeecgController<WmsLocation, IWmsLocationService> {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    /**
     * 分页列表查询。
     *
     * <p>{@code warehouseType} 支持按库区类型过滤（左侧树点类型节点）。实现方式是
     * 先查该类型下的库区 id 再 {@code in}，**避免写 JOIN**；但**id 集合为空时必须直接返回空页** ——
     * MyBatis-Plus 会把空集合拼成 {@code IN ()}，那是 SQL 语法错误，而使用者期望的
     * 只是「无匹配记录」。
     */
    @Operation(summary = "库位-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<WmsLocation>> queryPageList(WmsLocation wmsLocation,
                                                    @RequestParam(name = "warehouseType", required = false) String warehouseType,
                                                    @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                    HttpServletRequest req) {
        // 非持久化字段必须先置空，否则 QueryGenerator 会拼出不存在的 warehouse_name 列条件
        wmsLocation.setWarehouseName(null);
        QueryWrapper<WmsLocation> queryWrapper = QueryGenerator.initQueryWrapper(wmsLocation, req.getParameterMap());
        if (oConvertUtils.isNotEmpty(warehouseType)) {
            List<String> warehouseIds = wmsWarehouseService.queryIdsByType(warehouseType);
            if (warehouseIds.isEmpty()) {
                return Result.OK(new Page<>(pageNo, pageSize));
            }
            queryWrapper.in("warehouse_id", warehouseIds);
        }
        queryWrapper.orderByAsc("warehouse_id")
                .orderByAsc("layer_no").orderByAsc("row_no").orderByAsc("col_no");
        Page<WmsLocation> page = new Page<>(pageNo, pageSize);
        IPage<WmsLocation> pageList = service.page(page, queryWrapper);
        fillWarehouseNames(pageList.getRecords());
        return Result.OK(pageList);
    }

    @Operation(summary = "库位-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<WmsLocation> queryById(@RequestParam(name = "id", required = true) String id) {
        WmsLocation entity = service.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        fillWarehouseNames(Collections.singletonList(entity));
        return Result.OK(entity);
    }

    @Operation(summary = "库位-编码唯一性校验")
    @GetMapping(value = "/checkCode")
    public Result<Boolean> checkCode(@RequestParam(name = "code") String code,
                                     @RequestParam(name = "id", required = false) String id) {
        if (oConvertUtils.isEmpty(code)) {
            return Result.error("库位编码不能为空");
        }
        if (service.checkCodeExists(code, id)) {
            return Result.error("库位编码已存在，请重新输入");
        }
        return Result.OK(true);
    }

    @AutoLog(value = "库位-批量生成", operateType = 2)
    @Operation(summary = "库位-按范围批量生成")
    @PostMapping(value = "/generate")
    public Result<WmsLocationGenResultVO> generate(@RequestBody WmsLocationGenDTO dto) {
        try {
            return Result.OK(service.generate(dto));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-添加", operateType = 2)
    @Operation(summary = "库位-手工新增单个")
    @PostMapping(value = "/add")
    public Result<WmsLocation> add(@RequestBody WmsLocation wmsLocation) {
        try {
            service.addLocation(wmsLocation);
            return Result.OK("添加成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-编辑", operateType = 3)
    @Operation(summary = "库位-编辑（坐标不可改，仅承重/状态）")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<WmsLocation> edit(@RequestBody WmsLocation wmsLocation) {
        try {
            service.editLocation(wmsLocation);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-属性更新", operateType = 3)
    @Operation(summary = "库位-属性更新（承重/状态；占用时拒绝）")
    @PostMapping(value = "/updateAttr")
    public Result<?> updateAttr(@RequestBody WmsLocationAttrDTO dto) {
        try {
            service.updateAttr(dto);
            return Result.OK("修改成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-释放", operateType = 3)
    @Operation(summary = "库位-释放（占用 → 空闲）")
    @PostMapping(value = "/release")
    public Result<?> release(@RequestParam(name = "id", required = true) String id) {
        try {
            service.release(id);
            return Result.OK("释放成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-删除", operateType = 4)
    @Operation(summary = "库位-删除（仅空闲）")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        try {
            service.removeLocation(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "库位-批量删除", operateType = 4)
    @Operation(summary = "库位-批量删除（仅空闲）")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        try {
            service.removeBatch(Arrays.asList(ids.split(",")));
            return Result.OK("批量删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 删除预检：非空闲库位在这里给出原因；后端守卫仍在删除时兜底。 */
    @Operation(summary = "库位-删除预检")
    @GetMapping(value = "/checkDelete")
    public Result<WmsDeleteCheckVO> checkDelete(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(service.checkDelete(id));
    }

    /** 批量删除预检：列出不可删明细。 */
    @Operation(summary = "库位-批量删除预检")
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
     * <p>库区被逻辑删除时 {@code listByIds} 查不到它，此处留 {@code null} 而不是抛异常 ——
     * 列表要能显示「已删除库区的库位」，否则数据修复时反而看不到问题所在。
     */
    private void fillWarehouseNames(List<WmsLocation> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<String> warehouseIds = records.stream()
                .map(WmsLocation::getWarehouseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (warehouseIds.isEmpty()) {
            return;
        }
        List<WmsWarehouse> warehouses = wmsWarehouseService.listByIds(warehouseIds);
        Map<String, String> nameById = warehouses.stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName, (a, b) -> a));
        for (WmsLocation record : records) {
            record.setWarehouseName(nameById.get(record.getWarehouseId()));
        }
    }
}