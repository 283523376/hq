package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;

/**
 * @Description: 物料分组 → 物料分类映射同步（BD_MATERIAL → wms_material_category）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsKingdeeCategorySyncService {

    /**
     * 按 {@code category-map} 规则（格式「金蝶分组前缀=WMS 分类编码」）把命中的分组盖到分类行上。
     *
     * <p>未配置规则时直接空转——金蝶侧的分类来源字段尚未定义（外部依赖 E2），
     * 此时不给物料硬塞分类，避免产生脏数据。
     */
    WmsKingdeeStockSyncResultVO syncCategory();

    /**
     * 按金蝶物料分组（BD_MATERIAL.FMaterialGroup）在 {@code wms_material_category} 中建行。
     *
     * <p>分组编码 = {@code category_code} = {@code erp_group_number}，分组名称 = 分类名称；
     * {@code category_type} 取映射表 {@code wms_material_group_map}（方案 A，2026-11-05），
     * 表里没有的分组留空。
     * 按分组编码幂等 upsert：已存在只更新名称，不重复插入。
     *
     * <p><b>同步开关关闭 / 金蝶未配置时抛异常</b>，不返回空结果——否则页面会显示
     * 「同步完成，新建 0 个」，把配置问题伪装成业务正常。
     */
    WmsKingdeeStockSyncResultVO syncMaterialGroups();
}
