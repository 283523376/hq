package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * @Description: 金蝶物料分组 → 物料分类映射（分类合并后多对一）
 *
 * <p>合并前分类与金蝶分组是 1:1（一个分组建一条同名分类）；合并后同一名称+类型的分类只有一条，
 * 多个分组指向同一个 {@code category_id}，因此需要这张独立映射表承载 1:N 关系。
 * {@code wms_material_category.erp_group_number} 只保留"代表分组"，不再用于反查。
 *
 * <p>为什么不用 {@link org.jeecg.common.system.base.entity.JeecgEntity}：表只有
 * {@code create_by/create_time}，没有 {@code update_by/update_time/sys_org_code}，
 * 继承会让 MyBatis-Plus 生成的 SQL 引用不存在的列。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "金蝶物料分组→分类映射")
@TableName("wms_material_category_group")
public class WmsMaterialCategoryGroup {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    /**
     * 金蝶物料分组编码；分类没有组号时用原 {@code category_code} 兜底。
     */
    @Schema(description = "金蝶物料分组编码")
    private String groupNumber;

    /**
     * 分组名称（冗余，便于人工核对）。
     */
    @Schema(description = "分组名称")
    private String groupName;

    /**
     * 合并后归属的分类 ID。
     */
    @Schema(description = "归属分类ID")
    private String categoryId;

    /**
     * 删除时间戳（epoch 毫秒）：0 = 未删除。口径与 {@code wms_material_category} 一致，
     * 唯一键 {@code (group_number, deleted_at)} 才能支持「删除 → 重建」。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;

    @Schema(description = "创建人")
    private String createBy;

    @Schema(description = "创建时间")
    private Date createTime;
}
