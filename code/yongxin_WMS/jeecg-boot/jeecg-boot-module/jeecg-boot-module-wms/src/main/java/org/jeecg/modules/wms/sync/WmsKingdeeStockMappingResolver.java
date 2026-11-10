package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.springframework.stereotype.Component;

/**
 * @Description: 金蝶出货仓库 → WMS 库区 的映射解析器（**预留扩展接口**）。
 *
 * <p>背景（2026-10-28 用户明确）：金蝶 101 组织的**仓库与车间主数据尚未建立**，
 * 映射关系现在配不出来。因此本类只做两件事：
 * <ol>
 *   <li>按配置 {@code sales-delivery.stock-map}（金蝶仓编码 → WMS 库区编码）解析；</li>
 *   <li>解析结果必须能在 WMS 库区表里找到且启用，否则视为「未映射」返回 null。</li>
 * </ol>
 *
 * <p>调用方（销售出库待办）遇到 null 时必须**跳过该行并记录可读原因**，
 * 不得退回其它仓库或凭猜测取值。等金蝶仓库建好、配置补齐，无需改代码即可生效；
 * 将来若映射改为落库（映射表 + 页面维护），只需替换本类实现，调用方不动。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
public class WmsKingdeeStockMappingResolver {

    private final WmsKingdeeProperties properties;
    private final WmsWarehouseMapper warehouseMapper;

    public WmsKingdeeStockMappingResolver(WmsKingdeeProperties properties, WmsWarehouseMapper warehouseMapper) {
        this.properties = properties;
        this.warehouseMapper = warehouseMapper;
    }

    /**
     * 解析 WMS 库区编码。
     *
     * @param kingdeeStockNumber 金蝶出货仓库编码（如 S.05）
     * @return WMS 库区编码；未配置映射或映射目标不存在/停用时返回 null
     */
    public String resolveWmsWarehouse(String kingdeeStockNumber) {
        if (oConvertUtils.isEmpty(kingdeeStockNumber)) {
            return null;
        }
        String wmsWarehouseCode = properties.getSalesDelivery().getStockMap().get(kingdeeStockNumber.trim());
        if (oConvertUtils.isEmpty(wmsWarehouseCode)) {
            return null;
        }
        try {
            WmsWarehouse warehouse = warehouseMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsWarehouse>()
                            .eq(WmsWarehouse::getWarehouseCode, wmsWarehouseCode)
                            .last("LIMIT 1"));
            if (warehouse == null || !"1".equals(warehouse.getStatus())) {
                log.warn("仓库映射指向的 WMS 库区[{}]不存在或已停用（金蝶仓 {}）", wmsWarehouseCode, kingdeeStockNumber);
                return null;
            }
            return warehouse.getWarehouseCode();
        } catch (Exception e) {
            log.warn("校验 WMS 库区[{}]失败：{}", wmsWarehouseCode, e.getMessage());
            return null;
        }
    }

    /** 是否配置了任何映射（用于给出「映射整体未配置」这一类原因）。 */
    public boolean hasAnyMapping() {
        return !properties.getSalesDelivery().getStockMap().isEmpty();
    }
}
