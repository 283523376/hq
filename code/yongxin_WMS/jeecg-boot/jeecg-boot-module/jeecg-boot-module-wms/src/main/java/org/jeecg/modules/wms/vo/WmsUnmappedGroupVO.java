package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 未映射的金蝶物料分组（已同步成分类、但分类类型仍为 NULL）。
 *
 * <p>数据源是 {@code wms_material_category}：分组同步（
 * {@code POST /wms/materialCategory/sync-from-kingdee}）会为每个金蝶分组建一行分类，
 * 未在 {@code wms_material_group_map} 里认领的分组 {@code category_type} 为 NULL。
 * 本 VO 供业务按物料数从多到少认领——631 组覆盖 80% 物料，先标高频分组收益最大。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Data
@Schema(description = "未映射的金蝶物料分组")
public class WmsUnmappedGroupVO {

    /** 对应的 wms_material_category.id（认领后可用它回查分类） */
    @Schema(description = "分类ID")
    private String categoryId;

    /** 金蝶物料分组编码 */
    @Schema(description = "分组编码")
    private String groupNumber;

    /** 金蝶物料分组名称（取分类名称） */
    @Schema(description = "分组名称")
    private String groupName;

    /** 该分组下未删除的物料数，用于排优先级 */
    @Schema(description = "该分组下的物料数")
    private long materialCount;
}
