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
import org.jeecg.modules.wms.constant.WmsConstants;
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
                                                    @RequestParam(name = "keyword", required = false) String keyword,
                                                    @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                    HttpServletRequest req) {
        // 非持久化字段必须先置空，否则 QueryGenerator 会拼出不存在的 warehouse_name 列条件
        wmsLocation.setWarehouseName(null);
        // 查询窗口模糊匹配（2026-11-02）：QueryGenerator 对 String 拼的是等值，
        // 这里把文本字段置空后手写 like，让「任意连续子串」都能命中。
        String locationCodeKeyword = wmsLocation.getLocationCode();
        wmsLocation.setLocationCode(null);
        QueryWrapper<WmsLocation> queryWrapper = QueryGenerator.initQueryWrapper(wmsLocation, req.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(locationCodeKeyword), "location_code",
                locationCodeKeyword == null ? null : locationCodeKeyword.trim());
        // 范围收窄（2026-10-19）：线边柜方案 A 已移出，其库位不再展示（数据保留可回滚）
        queryWrapper.notInSql("warehouse_id",
                "SELECT id FROM wms_warehouse WHERE warehouse_type = '" + WmsConstants.WAREHOUSE_TYPE_LINE_SIDE
                        + "' AND deleted_at = 0");
        // 具体库区 id 优先：树上选中库区节点会同时带 warehouseId 与 warehouseType（M2），
        // 此时类型分支会冗余拼一个「该类型全部库区」的 IN 子句。id 已是最精确过滤，跳过类型分支。
        if (oConvertUtils.isEmpty(wmsLocation.getWarehouseId())
                && oConvertUtils.isNotEmpty(warehouseType)) {
            List<String> warehouseIds = wmsWarehouseService.queryIdsByType(warehouseType);
            if (warehouseIds.isEmpty()) {
                return Result.OK(new Page<>(pageNo, pageSize));
            }
            queryWrapper.in("warehouse_id", warehouseIds);
        }
        // 全局关键词（2026-11-02）：一次跨字段模糊，与单字段框是 AND 关系。
        if (oConvertUtils.isNotEmpty(keyword)) {
            final String kw = keyword.trim();
            queryWrapper.and(w -> w.like("location_code", kw)
            );
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
    @RequiresPermissions("wms:location:generate")
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
    @RequiresPermissions("wms:location:add")
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
    @RequiresPermissions("wms:location:edit")
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
    @RequiresPermissions("wms:location:attr")
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
    @RequiresPermissions("wms:location:release")
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
    @RequiresPermissions("wms:location:delete")
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
    @RequiresPermissions("wms:location:delete")
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