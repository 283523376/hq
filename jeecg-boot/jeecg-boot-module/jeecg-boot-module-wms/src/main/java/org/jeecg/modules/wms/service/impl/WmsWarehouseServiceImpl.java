package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     *
     * <p>**必须在一个事务里**：本方法由两条写语句组成（常规字段 + 三个维度），
     * 不加事务时第二条失败会留下「新编码/新名称 + 旧维度」的**半更新状态**，
     * 而接口返回的是错误 —— 用户不知道该以哪个为准（代码审查发现）。
     *
     * <p><b>调用契约</b>：表单必须提交全部三个维度字段；**漏传即视为「清空」**
     * （因为「维度必须能写 NULL」与「null 表示未提供」是两个冲突的要求，
     * 见本方法内注释）。未来的部分更新调用方需注意这一点。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editWarehouse(WmsWarehouse warehouse) {
        if (checkCodeExists(warehouse.getWarehouseCode(), warehouse.getId())) {
            throw new RuntimeException("库区编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(warehouse);
        // 常规字段走 updateById：它**跳过 null**，于是「表单漏传某字段」是安全的
        // （漏传 status 时不会把 NOT NULL 列写成 null）。
        this.updateById(warehouse);
        // 三个维度必须**显式** set：updateById 会跳过 null，而「清空维度」是明确要求
        // （维度可自由增减），否则清空会返回保存成功而库里纹丝不动 —— 与解绑、
        // 清空告警节流是同一类静默故障，端到端步骤 18e 抓到过。
        //
        // 「必须能写 null」（维度）与「null 表示未提供」（其它字段）是两个冲突的要求，
        // 故拆成两条语句而不是合并成一个大 set —— 合并后漏传 status 会直接
        // 抛 Column 'status' cannot be null（这是本修复第一版踩过的回归）。
        UpdateWrapper<WmsWarehouse> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", warehouse.getId())
                .set("row_count", warehouse.getRowCount())
                .set("col_count", warehouse.getColCount())
                .set("layer_count", warehouse.getLayerCount());
        this.update(null, wrapper);
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
        WmsDeleteCheckVO check = checkDelete(id);
        if (!check.isDeletable()) {
            // blocker 顺序即优先级：库位先于设备，与既有文案一致
            throw new RuntimeException(check.getBlockers().get(0).getMessage() + "，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public WmsDeleteCheckVO checkDelete(String id) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (oConvertUtils.isEmpty(id)) {
            return vo;
        }
        long locationCount = wmsLocationMapper.selectCount(
                new QueryWrapper<WmsLocation>().eq("warehouse_id", id));
        if (locationCount > 0) {
            vo.addBlocker("该库区下存在库位", locationCount, Collections.emptyList());
        }
        long deviceCount = wmsDeviceMapper.selectCount(
                new QueryWrapper<WmsDevice>().eq("warehouse_id", id));
        if (deviceCount > 0) {
            vo.addBlocker("该库区已绑定设备", deviceCount, Collections.emptyList());
        }
        return vo;
    }

    @Override
    public WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids) {
        WmsBatchDeleteCheckVO vo = new WmsBatchDeleteCheckVO();
        if (ids == null || ids.isEmpty()) {
            return vo;
        }
        for (String id : ids) {
            WmsDeleteCheckVO check = checkDelete(id);
            if (!check.isDeletable()) {
                WmsWarehouse warehouse = this.getById(id);
                vo.addBlocked(id,
                        warehouse == null ? id : warehouse.getWarehouseCode(),
                        check.getBlockers().stream()
                                .map(WmsDeleteCheckVO.Blocker::getMessage)
                                .collect(Collectors.joining("；")));
            }
        }
        return vo;
    }

    /** 批量删除整体在一个事务里（原 controller 循环逐条、无事务，遇守卫会部分成功）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                removeWarehouse(id);
            }
        }
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