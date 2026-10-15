package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库区")
@TableName("wms_warehouse")
public class WmsWarehouse extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库区编码，如 LB / MJ / XB。 */
    @Excel(name = "库区编码", width = 20)
    @Schema(description = "库区编码")
    private String warehouseCode;

    /** 库区名称。 */
    @Excel(name = "库区名称", width = 20)
    @Schema(description = "库区名称")
    private String warehouseName;

    /** 库区类型：立库 / 敏捷仓 / 线边柜。 */
    @Excel(name = "库区类型", width = 15)
    @Schema(description = "库区类型：立库/敏捷仓/线边柜")
    private String warehouseType;

    /**
     * 生成上限-排数。
     *
     * <p><b>只用于限制批量生成能开到哪</b>，不参与库位编码计算、也不约束任何已有数据，
     * 故可随时自由增减。
     */
    @Schema(description = "生成上限-排数")
    private Integer rowCount;

    /** 生成上限-列数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-列数")
    private Integer colCount;

    /** 生成上限-层数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-层数")
    private Integer layerCount;

    /** 状态：1启用 0停用。 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 位于当前维度之外的库位数（非持久化，由控制器回填）。
     *
     * <p>缩维后已有库位可能落在新声明的包络之外。系统**不拦截**这种不一致，
     * 而是把它显示出来，由运维决定是补回维度还是删库位。
     */
    @TableField(exist = false)
    @Schema(description = "位于当前维度之外的库位数")
    private Long outOfEnvelopeCount;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不复用 0/1 的 del_flag</b>：{@code uk_wms_warehouse_code} 必须把删除标记
     * 一起纳入唯一索引，否则被删除的库区编码会**永久占用**它。
     * 用**时间戳**而非固定值（如 1），是因为固定值会让同一编码**只能删一次**；
     * 时间戳每次不同，「删除 → 重建 → 再删除」可无限次进行，且保留可恢复/可审计。
     *
     * <p>本表原先是「物理删除」，于 V3.9.5_9 并入统一方案（与 4 张既有表一致）。
     * 索引为 {@code (warehouse_code, deleted_at)}；过滤由 {@code @TableLogic} 自动追加
     * {@code deleted_at = 0}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}