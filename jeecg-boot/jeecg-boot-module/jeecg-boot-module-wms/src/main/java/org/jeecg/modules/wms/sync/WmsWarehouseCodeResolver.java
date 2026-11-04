package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @Description: 由 WMS 货位解析金蝶仓库编码（{@code FStockId.FNumber}）。
 *
 * <p>口径：本项目 WMS 仓库编码与金蝶仓库编码 **1:1**（2026-10-28 决策，见
 * V3.9.5_40），因此直接取 {@code wms_warehouse.warehouse_code}，不做映射表。
 *
 * <p>为什么必须由货位反查：{@code wms_inbound_order} 不存仓库，仓库只落在明细的
 * {@code location_id} 上（上架时写入）。解析不到时返回 {@code null}，由调用方
 * 显式失败——金蝶侧 FStockId 是必填项，静默省略只会把错误推迟到 ERP 保存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Component
public class WmsWarehouseCodeResolver {

    private final WmsLocationMapper locationMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsInboundDetailMapper detailMapper;

    public WmsWarehouseCodeResolver(WmsLocationMapper locationMapper,
                                    WmsWarehouseMapper warehouseMapper,
                                    WmsInboundDetailMapper detailMapper) {
        this.locationMapper = locationMapper;
        this.warehouseMapper = warehouseMapper;
        this.detailMapper = detailMapper;
    }

    /**
     * 货位 → 仓库编码。
     *
     * @param locationId 库位ID；为空、库位不存在或库区不存在时返回 null
     */
    public String byLocation(String locationId) {
        if (oConvertUtils.isEmpty(locationId)) {
            return null;
        }
        WmsLocation location = locationMapper.selectById(locationId);
        if (location == null || oConvertUtils.isEmpty(location.getWarehouseId())) {
            return null;
        }
        WmsWarehouse warehouse = warehouseMapper.selectById(location.getWarehouseId());
        if (warehouse == null || oConvertUtils.isEmpty(warehouse.getWarehouseCode())) {
            return null;
        }
        return warehouse.getWarehouseCode().trim();
    }

    /**
     * 入库单 → 仓库编码（按明细上的货位解析，取第一条能解析到的行）。
     *
     * <p>用于采购退货：{@code wms_defect_record} 没有货位字段，受托货的仓库只能
     * 由来源入库单的明细反查。同一张单的明细若落在不同仓库，记 WARN 并取第一条——
     * 金蝶退料单头只有一个 FStockId，拆单需要人工处理。
     *
     * @param inboundId 入库单ID；无明细或全部明细没有货位时返回 null
     */
    public String byInboundOrder(String inboundId) {
        if (oConvertUtils.isEmpty(inboundId)) {
            return null;
        }
        List<WmsInboundDetail> details = detailMapper.selectList(
                new QueryWrapper<WmsInboundDetail>().eq("inbound_id", inboundId).orderByAsc("id"));
        if (details == null || details.isEmpty()) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (WmsInboundDetail detail : details) {
            String code = byLocation(detail.getLocationId());
            if (oConvertUtils.isNotEmpty(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return null;
        }
        if (codes.size() > 1) {
            log.warn("入库单[{}]的明细分布在多个仓库{}，退货单只取第一个仓库；拆单请人工处理",
                    inboundId, codes);
        }
        return codes.iterator().next();
    }
}
