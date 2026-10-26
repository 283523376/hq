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
     * 金蝶物料分组编码（命中该分类的**代表**分组，便于人工核对；多对一关系在配置里）
     */
    @Schema(description = "金蝶物料分组编码（代表来源）")
    private String erpGroupNumber;

    /**
     * 金蝶物料分组名称（代表来源）
     */
    @Schema(description = "金蝶物料分组名称（代表来源）")
    private String erpGroupName;

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
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不是 0/1 的 del_flag</b>：{@code uk_wms_mat_cat_code} 必须把删除标记
     * 一起纳入唯一索引，否则被删除的分类编码会**永久占用**它 —— 表现为
     * 「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」。
     * 用**时间戳**而非固定值（如 1），是因为固定值会让同一编码**只能删一次**；
     * 时间戳每次不同，「删除 → 重建 → 再删除」可无限次进行。
     *
     * <p>另一个好处：{@code removeCategory} 的守卫用 {@code this.count(...)} 统计
     * 「有无物料 / 有无子分类」，过滤条件由 {@code @TableLogic} 统一追加
     * {@code deleted_at = 0} —— 与库内实际占用的口径一致。
     *
     * <p>索引已相应改为 {@code (category_code, deleted_at)}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
