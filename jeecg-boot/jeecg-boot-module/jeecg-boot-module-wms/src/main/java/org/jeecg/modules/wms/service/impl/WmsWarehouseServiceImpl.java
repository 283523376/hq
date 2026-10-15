package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsWarehouseServiceImpl extends ServiceImpl<WmsWarehouseMapper, WmsWarehouse>
        implements IWmsWarehouseService {

    @Autowired
    private WmsLocationMapper wmsLocationMapper;

    @Autowired
    private WmsDeviceMapper wmsDeviceMapper;

    @Override
    public List<SelectTreeModel> queryTreeList() {
        List<WmsWarehouse> all = this.list(new QueryWrapper<WmsWarehouse>()
                .orderByAsc("warehouse_type")
                .orderByAsc("warehouse_code"));
        Map<String, List<WmsWarehouse>> byType = new LinkedHashMap<>();
        for (WmsWarehouse warehouse : all) {
            byType.computeIfAbsent(warehouse.getWarehouseType(), key -> new ArrayList<>()).add(warehouse);
        }
        List<SelectTreeModel> tree = new ArrayList<>();
        for (Map.Entry<String, List<WmsWarehouse>> entry : byType.entrySet()) {
            String typeKey = WmsConstants.WAREHOUSE_TYPE_NODE_PREFIX + entry.getKey();
            SelectTreeModel typeNode = new SelectTreeModel();
            typeNode.setKey(typeKey);
            typeNode.setValue(typeKey);
            typeNode.setTitle(entry.getKey());
            typeNode.setParentId(WmsConstants.ROOT_PID_VALUE);
            typeNode.setLeaf(false);
            List<SelectTreeModel> children = new ArrayList<>();
            for (WmsWarehouse warehouse : entry.getValue()) {
                SelectTreeModel node = new SelectTreeModel();
                node.setKey(warehouse.getId());
                node.setValue(warehouse.getId());
                node.setTitle(warehouse.getWarehouseName());
                node.setParentId(typeKey);
                node.setChildren(new ArrayList<>());
                node.setLeaf(true);
                children.add(node);
            }
            typeNode.setChildren(children);
            tree.add(typeNode);
        }
        return tree;
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsWarehouse> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("warehouse_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addWarehouse(WmsWarehouse warehouse) {
        if (checkCodeExists(warehouse.getWarehouseCode(), null)) {
            throw new RuntimeException("库区编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(warehouse.getStatus())) {
            warehouse.setStatus(WmsConstants.STATUS_ENABLE);
        }
        WmsUtils.fillCreate(warehouse);
        this.save(warehouse);
    }

    /**
     * 编辑库区。
     *
     * <p><b>刻意不校验维度变更</b>：{@code row_count/col_count/layer_count} 只用于限制
     * 批量生成的范围，不参与库位编码计算（见 {@code WmsLocationCodeGenerator}），
     * 因此可自由增减。需求方明确要求「所有维度可以自由调节」。
     * 缩维后若已有库位落在新维度之外，系统**不拦截**，只在库区详情回填越界数量供人查看。
     */
    @Override
    public void editWarehouse(WmsWarehouse warehouse) {
        if (checkCodeExists(warehouse.getWarehouseCode(), warehouse.getId())) {
            throw new RuntimeException("库区编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(warehouse);
        this.updateById(warehouse);
    }

    @Override
    public void disableWarehouse(String id) {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId(id);
        warehouse.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(warehouse);
        this.updateById(warehouse);
    }

    @Override
    public void removeWarehouse(String id) {
        long locationCount = wmsLocationMapper.selectCount(
                new QueryWrapper<WmsLocation>().eq("warehouse_id", id));
        if (locationCount > 0) {
            throw new RuntimeException("该库区下存在库位，不允许删除");
        }
        long deviceCount = wmsDeviceMapper.selectCount(
                new QueryWrapper<WmsDevice>().eq("warehouse_id", id));
        if (deviceCount > 0) {
            throw new RuntimeException("该库区已绑定设备，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public List<String> queryIdsByType(String warehouseType) {
        if (oConvertUtils.isEmpty(warehouseType)) {
            return Collections.emptyList();
        }
        return this.list(new QueryWrapper<WmsWarehouse>()
                        .select("id")
                        .eq("warehouse_type", warehouseType))
                .stream()
                .map(WmsWarehouse::getId)
                .collect(Collectors.toList());
    }

    @Override
    public WmsWarehouse requireEnabledWarehouse(String warehouseId) {
        WmsWarehouse warehouse = oConvertUtils.isEmpty(warehouseId) ? null : this.getById(warehouseId);
        if (warehouse == null || !WmsConstants.STATUS_ENABLE.equals(warehouse.getStatus())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        return warehouse;
    }
}