package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 金蝶物料分组 → WMS 分类类型映射（方案 A，2026-11-05）
 *
 * <p><b>为什么独立成表</b>：原实现把映射塞在系统配置
 * {@code kingdee.material-group-map} 的字符串里，而
 * {@code wms_system_config.config_value} 只有 {@code varchar(500)}，约 20 条就到顶；
 * 金蝶有 2039 个分组、需要标 631 组才能覆盖 80% 物料。配置串既装不下也无法维护。
 *
 * <p>语义：一行 = 一个金蝶分组的分类归属。同步物料分组时按
 * {@code group_number} 查本表，把 {@code category_type} 写到
 * {@code wms_material_category.category_type}；表里没有的分组留 NULL，绝不猜。
 *
 * <p>唯一索引 {@code uk_wms_mgm_group(group_number, deleted_at)} 把删除标记一起纳入，
 * 用时间戳而非固定值，保证「删除 → 重建 → 再删除」可无限次进行（与
 * {@link WmsCustomer#deletedAt} 同一方案）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "金蝶物料分组→WMS分类类型映射")
@TableName("wms_material_group_map")
public class WmsMaterialGroupMap extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 金蝶物料分组编码（BD_MATERIAL.FMaterialGroup.FNumber） */
    @Excel(name = "分组编码", width = 30)
    @Schema(description = "金蝶物料分组编码")
    private String groupNumber;

    /** 金蝶物料分组名称（冗余保存，便于人工核对；同步时自动回填） */
    @Excel(name = "分组名称", width = 30)
    @Schema(description = "金蝶物料分组名称（冗余）")
    private String groupName;

    /** 分类类型：原材料/半成品/成品/辅料（常量见 WmsConstants.CATEGORY_TYPE_*） */
    @Excel(name = "分类类型", width = 15)
    @Schema(description = "分类类型：原材料/半成品/成品/辅料")
    private String categoryType;

    /** 备注 */
    @Excel(name = "备注", width = 30)
    @Schema(description = "备注")
    private String remark;

    /** 删除时间戳（epoch 毫秒）：0 = 未删除；由 @TableLogic 自动过滤。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
