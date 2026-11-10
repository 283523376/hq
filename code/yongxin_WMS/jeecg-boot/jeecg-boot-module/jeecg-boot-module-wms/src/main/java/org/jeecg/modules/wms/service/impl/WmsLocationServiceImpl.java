package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsLocationCodeGenerator;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

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

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInboundDetailMapper inboundDetailMapper;

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
        location.setLocationCode(checkedLocationCode(warehouse, row, col, layer));
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
        // RK-001：有库存不许释放（本期无出库流程，释放前必须先清库存）
        if (countInventory(id) > 0) {
            throw new RuntimeException("该库位已有库存，不能释放");
        }
        WmsLocation update = new WmsLocation();
        update.setId(id);
        update.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        WmsUtils.fillUpdate(update);
        this.updateById(update);
    }

    @Override
    public WmsDeleteCheckVO checkDelete(String id) {
        if (oConvertUtils.isEmpty(id)) {
            return new WmsDeleteCheckVO();
        }
        return checkLocation(this.getById(id));
    }

    @Override
    public WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids) {
        WmsBatchDeleteCheckVO vo = new WmsBatchDeleteCheckVO();
        if (ids == null || ids.isEmpty()) {
            return vo;
        }
        for (String id : ids) {
            // 查一次、复用同一实体：编码与可删判定必须取自同一行，且同一请求不重复 getById
            WmsLocation location = this.getById(id);
            WmsDeleteCheckVO check = checkLocation(location);
            if (!check.isDeletable()) {
                vo.addBlocked(id,
                        location == null ? id : location.getLocationCode(),
                        check.getBlockers().stream()
                                .map(WmsDeleteCheckVO.Blocker::getMessage)
                                .collect(Collectors.joining("；")));
            }
        }
        return vo;
    }

    @Override
    public void removeLocation(String id) {
        WmsLocation existing = requireExisting(id);
        WmsDeleteCheckVO check = checkLocation(existing);
        if (!check.isDeletable()) {
            // 逐字透出真实原因（库位状态 / 库存记录 / 进行中入库单），而不是固定文案
            String reason = check.getBlockers().stream()
                    .map(WmsDeleteCheckVO.Blocker::getMessage)
                    .collect(Collectors.joining("；"));
            throw new RuntimeException(reason + "，不允许删除");
        }
        this.removeById(id);
    }

    /**
     * 守则判定，入参为**已取到的行**。
     *
     * <p>抽成独立方法是为了让「单条删除 / 单条预检 / 批量预检」三条路径复用同一实体，
     * 而不是各自再 {@code getById} 一次（同一请求对同一行查两遍）。
     */
    private WmsDeleteCheckVO checkLocation(WmsLocation location) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (location == null) {
            // 查不到视为可删：删除路径的 requireExisting 负责报「未找到」，预检不越权替它说话
            return vo;
        }
        if (!WmsConstants.LOCATION_STATUS_IDLE.equals(location.getStatus())) {
            vo.addBlocker("库位已占用或有故障", 1,
                    Collections.singletonList(location.getLocationCode()));
        }
        // RK-001：有库存记录的库位不可删（防止「库存行还在、库位被删」的孤儿数据）
        long inventoryCount = countInventory(location.getId());
        if (inventoryCount > 0) {
            vo.addBlocker("该库位已存在库存记录", inventoryCount,
                    Collections.singletonList(location.getLocationCode()));
        }
        // 已分配但未上架的入库明细也引用该库位：删了会让用户在弹窗里选到不存在的库位
        long activeInbound = inboundDetailMapper == null ? 0L : inboundDetailMapper.countActiveByLocation(location.getId());
        if (activeInbound > 0) {
            vo.addBlocker("该库位存在进行中的入库单引用", activeInbound,
                    Collections.singletonList(location.getLocationCode()));
        }
        return vo;
    }

    /**
     * 该库位上的库存行数。
     *
     * <p>mapper 为 null 只会出现在单测裸构造场景（生产缺 bean 时 Spring 启动即失败）。
     */
    private long countInventory(String locationId) {
        if (inventoryMapper == null) {
            return 0L;
        }
        return inventoryMapper.selectCount(
                new QueryWrapper<WmsInventory>().eq("location_id", locationId));
    }

    /** 批量删除整体在一个事务里（原 controller 循环逐条、无事务，遇守卫会部分成功）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                removeLocation(id);
            }
        }
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
        // （口径与手工新增共用 checkedLocationCode；此处只校验，不取返回值）
        checkedLocationCode(warehouse, rowTo, colTo, layerTo);

        return total;
    }

    /**
     * 编码长度守卫：手工新增与批量生成共用同一口径（M4）。
     * 库位编码 = 库区编码 + 三段坐标，location_code 列宽 64；超长在生成前拦截，
     * 而不是让用户看到原始 SQL 异常。
     */
    private String checkedLocationCode(WmsWarehouse warehouse, int row, int col, int layer) {
        String code = WmsLocationCodeGenerator.locationCode(warehouse.getWarehouseCode(), row, col, layer);
        if (code.length() > WmsConstants.LOCATION_CODE_MAX_LENGTH) {
            // 文案须对两条调用路径都成立：手工补录没有「生成」也没有「范围」可缩，
            // 但两条路径都只能靠「缩短库区编码」解决 —— 故只给这一个出路。
            throw new RuntimeException("库区编码过长，库位编码将超过 "
                    + WmsConstants.LOCATION_CODE_MAX_LENGTH + " 字符（示例：" + code
                    + "），请缩短库区编码");
        }
        return code;
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

    @Override
    public List<WmsLocation> listFreeByWarehouseType(String warehouseType) {
        if (oConvertUtils.isEmpty(warehouseType)) {
            return Collections.emptyList();
        }
        List<WmsWarehouse> warehouses = wmsWarehouseService.list(
                new QueryWrapper<WmsWarehouse>().eq("warehouse_type", warehouseType));
        if (warehouses == null || warehouses.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (WmsWarehouse w : warehouses) {
            ids.add(w.getId());
        }
        return baseMapper.selectList(new QueryWrapper<WmsLocation>()
                .eq("status", WmsConstants.LOCATION_STATUS_IDLE)
                .in("warehouse_id", ids)
                .orderByAsc("location_code"));
    }
}