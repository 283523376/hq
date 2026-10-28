package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.math.BigDecimal;

/**
 * @Description: 库存预警规则（KC-001）。
 *
 * <p>两类规则：`LOW_STOCK` 库存下限、`OVERDUE` 存放超期；作用域三选一：
 * `MATERIAL` 单物料 / `CATEGORY` 物料分类 / `ALL` 全部。
 * 校验口径见 spec 2026-10-28 第 4 节，非法一律拒绝保存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存预警规则")
@TableName("wms_warning_rule")
public class WmsWarningRule extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "规则名称")
    private String ruleName;

    /** 见 {@code WmsConstants.WARNING_TYPE_LOW_STOCK} / {@code ..._OVERDUE}。 */
    @Schema(description = "规则类型：LOW_STOCK 库存下限 / OVERDUE 存放超期")
    private String ruleType;

    /** 见 {@code WmsConstants.WARNING_SCOPE_*}。 */
    @Schema(description = "作用域：MATERIAL 物料 / CATEGORY 分类 / ALL 全部")
    private String scopeType;

    @Schema(description = "物料ID（scope=MATERIAL 时必填）")
    private String materialId;

    @Schema(description = "物料分类ID（scope=CATEGORY 时必填）")
    private String categoryId;

    @Schema(description = "限定库区ID（空=全部库区）")
    private String warehouseId;

    @Schema(description = "库存下限（LOW_STOCK 必填）")
    private BigDecimal thresholdQty;

    @Schema(description = "存放超期天数（OVERDUE 必填）")
    private Integer overdueDays;

    @Schema(description = "是否启用：1启用 0停用")
    private Integer enabled;

    @Schema(description = "备注")
    private String remark;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
