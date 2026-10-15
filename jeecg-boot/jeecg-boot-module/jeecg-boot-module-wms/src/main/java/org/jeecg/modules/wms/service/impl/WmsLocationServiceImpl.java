package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsLocationCodeGenerator;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsLocationServiceImpl extends ServiceImpl<WmsLocationMapper, WmsLocation>
        implements IWmsLocationService {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Override
    public WmsLocationGenResultVO generate(WmsLocationGenDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWarehouseId())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(dto.getWarehouseId());
        long total = validateRange(dto, warehouse);

        // 审计字段取一次复用：逐行调 WmsUtils.fillCreate 会做 5 万次 SecurityUtils 查询
        String username = WmsUtils.currentUsername();
        Date now = new Date();
        int generated = 0;
        List<WmsLocation> batch = new ArrayList<>(WmsConstants.LOCATION_GEN_BATCH_SIZE);
        try {
            for (int row = dto.getRowFrom(); row <= dto.getRowTo(); row++) {
                for (int col = dto.getColFrom(); col <= dto.getColTo(); col++) {
                    for (int layer = dto.getLayerFrom(); layer <= dto.getLayerTo(); layer++) {
                        batch.add(buildLocation(warehouse, row, col, layer, username, now));
                        if (batch.size() >= WmsConstants.LOCATION_GEN_BATCH_SIZE) {
                            generated += baseMapper.insertIgnoreBatch(batch);
                            batch.clear();
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                generated += baseMapper.insertIgnoreBatch(batch);
            }
        } catch (Exception e) {
            log.error("库区[{}]批量生成库位失败，已生成 {} 个，范围 R{}~{} C{}~{} L{}~{}：{}",
                    warehouse.getWarehouseCode(), generated,
                    dto.getRowFrom(), dto.getRowTo(),
                    dto.getColFrom(), dto.getColTo(),
                    dto.getLayerFrom(), dto.getLayerTo(), e.getMessage(), e);
            throw new RuntimeException("已生成 " + generated + " 个后失败：" + e.getMessage()
                    + "。重跑同一范围可安全续做（已生成的会自动跳过）。");
        }
        int skipped = (int) (total - generated);
        log.info("库区[{}]批量生成库位完成：请求 {} 个，新生成 {} 个，跳过 {} 个",
                warehouse.getWarehouseCode(), total, generated, skipped);
        return new WmsLocationGenResultVO(generated, skipped);
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsLocation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("location_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addLocation(WmsLocation location) {
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(location.getWarehouseId());
        Integer row = location.getRowNo();
        Integer col = location.getColNo();
        Integer layer = location.getLayerNo();
        if (row == null || col == null || layer == null) {
            throw new RuntimeException("排 / 列 / 层不能为空");
        }
        if (row < 1 || col < 1 || layer < 1) {
            throw new RuntimeException("排 / 列 / 层必须为正整数");
        }
        requireWithinEnvelope(warehouse, row, col, layer);
        long exists = this.count(new QueryWrapper<WmsLocation>()
                .eq("warehouse_id", location.getWarehouseId())
                .eq("row_no", row).eq("col_no", col).eq("layer_no", layer));
        if (exists > 0) {
            throw new RuntimeException("该坐标的库位已存在，请重新输入");
        }
        location.setLocationCode(
                WmsLocationCodeGenerator.locationCode(warehouse.getWarehouseCode(), row, col, layer));
        if (oConvertUtils.isEmpty(location.getStatus())) {
            location.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        }
        WmsUtils.fillCreate(location);
        this.save(location);
    }

    @Override
    public void editLocation(WmsLocation location) {
        WmsLocation existing = requireExisting(location.getId());
        if (coordinateChanged(location, existing)) {
            throw new RuntimeException("库位坐标不可修改，如需变更请删除后重新生成");
        }
        applyAttr(location.getId(), location.getMaxWeight(), location.getStatus(), existing);
    }

    @Override
    public void updateAttr(WmsLocationAttrDTO dto) {
        if (dto == null) {
            throw new RuntimeException("库位不存在");
        }
        WmsLocation existing = requireExisting(dto.getId());
        applyAttr(dto.getId(), dto.getMaxWeight(), dto.getStatus(), existing);
    }

    @Override
    public void release(String id) {
        WmsLocation existing = requireExisting(id);
        if (!WmsConstants.LOCATION_STATUS_OCCUPIED.equals(existing.getStatus())) {
            throw new RuntimeException("仅占用状态的库位可释放");
        }
        WmsLocation update = new WmsLocation();
        update.setId(id);
        update.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        WmsUtils.fillUpdate(update);
        this.updateById(update);
    }

    @Override
    public void removeLocation(String id) {
        WmsLocation existing = requireExisting(id);
        if (!WmsConstants.LOCATION_STATUS_IDLE.equals(existing.getStatus())) {
            throw new RuntimeException("库位已占用或有故障，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public long countOutOfEnvelope(String warehouseId) {
        WmsWarehouse warehouse = oConvertUtils.isEmpty(warehouseId)
                ? null : wmsWarehouseService.getById(warehouseId);
        if (warehouse == null
                || warehouse.getRowCount() == null || warehouse.getColCount() == null
                || warehouse.getLayerCount() == null) {
            return 0L;
        }
        // 维度是「声明」，库位是「已存在的资产」：缩维后两者可能不一致。
        // 这里只统计、不拦截 —— 由人决定是补回维度还是删库位。
        return this.count(new QueryWrapper<WmsLocation>()
                .eq("warehouse_id", warehouseId)
                .and(w -> w.gt("row_no", warehouse.getRowCount())
                        .or().gt("col_no", warehouse.getColCount())
                        .or().gt("layer_no", warehouse.getLayerCount())));
    }

    /** 取库位，不存在直接抛。 */
    private WmsLocation requireExisting(String id) {
        WmsLocation existing = oConvertUtils.isEmpty(id) ? null : this.getById(id);
        if (existing == null) {
            throw new RuntimeException("库位不存在");
        }
        return existing;
    }

    private boolean coordinateChanged(WmsLocation submitted, WmsLocation existing) {
        return (submitted.getRowNo() != null && !submitted.getRowNo().equals(existing.getRowNo()))
                || (submitted.getColNo() != null && !submitted.getColNo().equals(existing.getColNo()))
                || (submitted.getLayerNo() != null && !submitted.getLayerNo().equals(existing.getLayerNo()));
    }

    /**
     * 应用属性变更（承重 / 状态），约束②在此强制。
     *
     * <p>{@code null} 字段不写库（MyBatis-Plus 默认忽略 null），故「只改承重」是安全的。
     */
    private void applyAttr(String id, BigDecimal maxWeight, String status, WmsLocation existing) {
        if (WmsConstants.LOCATION_STATUS_OCCUPIED.equals(existing.getStatus())) {
            throw new RuntimeException("库位内有物料，不允许修改属性");
        }
        WmsLocation update = new WmsLocation();
        update.setId(id);
        update.setMaxWeight(maxWeight);
        update.setStatus(status);
        WmsUtils.fillUpdate(update);
        this.updateById(update);
    }

    /**
     * 范围与包络守卫，返回请求总数。
     *
     * <p>库区维度只是「生成上限」，不参与编码计算，故这里只做边界与上限校验。
     */
    private long validateRange(WmsLocationGenDTO dto, WmsWarehouse warehouse) {
        Integer rowFrom = dto.getRowFrom();
        Integer rowTo = dto.getRowTo();
        Integer colFrom = dto.getColFrom();
        Integer colTo = dto.getColTo();
        Integer layerFrom = dto.getLayerFrom();
        Integer layerTo = dto.getLayerTo();
        if (rowFrom == null || rowTo == null || colFrom == null || colTo == null
                || layerFrom == null || layerTo == null) {
            throw new RuntimeException("库位范围不合法");
        }
        if (rowFrom < 1 || colFrom < 1 || layerFrom < 1
                || rowFrom > rowTo || colFrom > colTo || layerFrom > layerTo) {
            throw new RuntimeException("库位范围不合法");
        }
        // 上限取范围末端：各段数字位数随数值单调增长，故末端即最坏情形
        requireWithinEnvelope(warehouse, rowTo, colTo, layerTo);
        long total = (long) (rowTo - rowFrom + 1) * (colTo - colFrom + 1) * (layerTo - layerFrom + 1);
        if (total > WmsConstants.LOCATION_GEN_MAX_PER_REQUEST) {
            throw new RuntimeException("单次最多生成 " + WmsConstants.LOCATION_GEN_MAX_PER_REQUEST
                    + " 个库位，本次 " + total + " 个，请按层 / 列分批");
        }
        // 编码长度只随各段数字位数单调增长，故同样取末端即最坏情形
        String longestCode = WmsLocationCodeGenerator.locationCode(
                warehouse.getWarehouseCode(), rowTo, colTo, layerTo);
        if (longestCode.length() > WmsConstants.LOCATION_CODE_MAX_LENGTH) {
            throw new RuntimeException("库区编码过长，生成的库位编码将超过 "
                    + WmsConstants.LOCATION_CODE_MAX_LENGTH + " 字符（示例：" + longestCode
                    + "），请缩短库区编码或缩小范围");
        }
        return total;
    }

    /**
     * 维度与包络守卫。
     *
     * <p>库区维度可以自由增减，但**为空或非正数时不允许落库位** —— 否则会把 NULL
     * 当成上限，算出满屏 {@code null} 段。线边柜这类不适用网格模型的库区就是靠这条挡住的。
     */
    private void requireWithinEnvelope(WmsWarehouse warehouse, int row, int col, int layer) {
        if (warehouse.getRowCount() == null || warehouse.getRowCount() <= 0
                || warehouse.getColCount() == null || warehouse.getColCount() <= 0
                || warehouse.getLayerCount() == null || warehouse.getLayerCount() <= 0) {
            throw new RuntimeException("库区未配置网格维度，请先在库区上填写排 / 列 / 层上限");
        }
        if (row > warehouse.getRowCount()) {
            throw new RuntimeException("第 " + row + " 排超出库区排数上限 " + warehouse.getRowCount());
        }
        if (col > warehouse.getColCount()) {
            throw new RuntimeException("第 " + col + " 列超出库区列数上限 " + warehouse.getColCount());
        }
        if (layer > warehouse.getLayerCount()) {
            throw new RuntimeException("第 " + layer + " 层超出库区层数上限 " + warehouse.getLayerCount());
        }
    }

    /**
     * 造一条库位。
     *
     * <p><b>id 必须显式生成</b>：{@code insertIgnoreBatch} 是手写 XML，
     * MyBatis-Plus 的 {@code IdType.ASSIGN_ID} 不会生效，不设就是 null，
     * 而 null 主键会被 {@code INSERT IGNORE} 静默吞掉。
     */
    private WmsLocation buildLocation(WmsWarehouse warehouse, int row, int col, int layer,
                                      String username, Date now) {
        WmsLocation location = new WmsLocation();
        location.setId(IdWorker.getIdStr());
        location.setLocationCode(WmsLocationCodeGenerator.locationCode(
                warehouse.getWarehouseCode(), row, col, layer));
        location.setWarehouseId(warehouse.getId());
        location.setRowNo(row);
        location.setColNo(col);
        location.setLayerNo(layer);
        location.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        // 删除时间戳方案下 0 = 未删除（自定义 XML 插入不会自动填，必须显式给）
        location.setDeletedAt(0L);
        location.setCreateBy(username);
        location.setCreateTime(now);
        location.setUpdateBy(username);
        location.setUpdateTime(now);
        return location;
    }
}