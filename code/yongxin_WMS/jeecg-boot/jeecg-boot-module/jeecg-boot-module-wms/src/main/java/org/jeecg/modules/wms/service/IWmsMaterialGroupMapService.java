package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsMaterialGroupMap;
import org.jeecg.modules.wms.vo.WmsMaterialGroupMapSaveResultVO;
import org.jeecg.modules.wms.vo.WmsUnmappedGroupVO;

import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶物料分组 → WMS 分类类型映射（方案 A：独立映射表）。
 *
 * <p>映射表是**唯一事实源**：{@code WmsKingdeeCategorySyncServiceImpl} 建分类行时
 * 从本表读类型，不再读系统配置 {@code kingdee.material-group-map}（该配置项保留但废弃）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
public interface IWmsMaterialGroupMapService extends JeecgService<WmsMaterialGroupMap> {

    /**
     * 批量 upsert：按 {@code group_number} 幂等，存在则更新类型/备注，不存在则新增。
     *
     * <p><b>整批校验</b>：任一分类类型不在
     * 原材料/半成品/成品/辅料 之内（或分组编码为空/超长），整批拒绝并抛
     * {@link RuntimeException}，绝不部分落库。同批重复分组编码按「后者覆盖前者」处理
     * （与旧配置串的解析语义一致）。
     *
     * @param maps 入参只使用 groupNumber / groupName / categoryType / remark
     * @return 新增/更新/无变化计数
     */
    WmsMaterialGroupMapSaveResultVO saveBatchUpsert(List<WmsMaterialGroupMap> maps);

    /** 取全部分组映射（按分组编码升序），供下拉/导出用。 */
    List<WmsMaterialGroupMap> listAll();

    /**
     * 取「分组编码 → 分类类型」映射，供分类同步批量查用。
     *
     * <p>表为空时返回空 Map（分类类型全部留 NULL），不报错——这是合法初始态。
     */
    Map<String, String> typeByGroupNumber();

    /**
     * 已同步成分类、但分类类型仍为 NULL 的金蝶分组，按物料数从多到少排序。
     *
     * <p>数据源 {@code wms_material_category}（{@code category_type IS NULL} 且
     * {@code erp_group_number} 非空），物料数取 {@code wms_material} 中未删除行。
     * 表为空或没有未映射分组时返回空列表。
     */
    List<WmsUnmappedGroupVO> listUnmappedGroups();
}
