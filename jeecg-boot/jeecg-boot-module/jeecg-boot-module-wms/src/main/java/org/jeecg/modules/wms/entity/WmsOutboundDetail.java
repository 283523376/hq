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
 * @Description: 出库需求明细（一行 = 一个物料/批次需求）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "出库需求明细")
@TableName("wms_outbound_detail")
public class WmsOutboundDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 出库单ID。 */
    @Schema(description = "出库单ID")
    private String outboundId;

    /** 行号（从 1 开始）。 */
    @Schema(description = "行号")
    private Integer lineNo;

    /** 物料ID（wms_material.id）。 */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码。 */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称。 */
    @Schema(description = "物料名称")
    private String materialName;

    /** 批次号。 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 需求数量。 */
    @Schema(description = "需求数量")
    private BigDecimal requiredQty;

    /** 已分配数量。 */
    @Schema(description = "已分配数量")
    private BigDecimal allocatedQty;

    /** 实发/复核确认数量。 */
    @Schema(description = "实发/复核确认数量")
    private BigDecimal actualQty;

    /** 单位。 */
    @Schema(description = "单位")
    private String unit;

    /** 金蝶 PPBOM 子项来源内码。 */
    @Schema(description = "金蝶 PPBOM 子项来源内码")
    private String sourceEntryId;

    /** 删除时间戳（epoch 毫秒）：0 = 未删除。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
