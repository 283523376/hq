package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * 改制单明细：投入/产出方向的行级记录。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "改制单明细")
@TableName("wms_conversion_detail")
public class WmsConversionDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 改制单ID（wms_conversion_order.id）。 */
    @Schema(description = "改制单ID")
    private String conversionId;

    /** 方向：IN 投入 / OUT 产出。 */
    @Schema(description = "方向：IN 投入 / OUT 产出")
    private String direction;

    /** 物料编码。 */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 批次号。 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 数量。 */
    @Schema(description = "数量")
    private java.math.BigDecimal qty;
}
