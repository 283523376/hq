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
 * @Description: 物料分类
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "物料分类")
@TableName("wms_material_category")
public class WmsMaterialCategory extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 分类编码
     */
    @Excel(name = "分类编码", width = 20)
    @Schema(description = "分类编码")
    private String categoryCode;

    /**
     * 分类名称
     */
    @Excel(name = "分类名称", width = 20)
    @Schema(description = "分类名称")
    private String categoryName;

    /**
     * 父级ID，根节点为 0
     */
    @Schema(description = "父级ID，根节点为0")
    private String parentId;

    /**
     * 分类类型：半成品/成品/辅料
     */
    @Excel(name = "分类类型", width = 15)
    @Schema(description = "分类类型：半成品/成品/辅料")
    private String categoryType;

    /**
     * 状态：1启用 0停用
     */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 是否有子节点：1是 0否
     */
    @Schema(description = "是否有子节点：1是 0否")
    private String hasChild;

    /**
     * 删除状态：0正常 1已删除
     */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
