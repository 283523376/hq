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
 * @Description: 出库分配明细（一行 = 一个库存行/库位或料箱的任务）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "出库分配明细")
@TableName("wms_outbound_allocation")
public class WmsOutboundAllocation extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 出库单ID。 */
    @Schema(description = "出库单ID")
    private String outboundId;

    /** 需求明细ID。 */
    @Schema(description = "需求明细ID")
    private String detailId;

    /** 库存行ID（wms_inventory.id）。 */
    @Schema(description = "库存行ID")
    private String inventoryId;

    /** 库位ID。 */
    @Schema(description = "库位ID")
    private String locationId;

    /** 库位编码快照。 */
    @Schema(description = "库位编码快照")
    private String locationCode;

    /** 料箱/托盘编码（敏捷仓）。 */
    @Schema(description = "料箱/托盘编码")
    private String containerCode;

    /** 分配数量。 */
    @Schema(description = "分配数量")
    private BigDecimal allocatedQty;

    /** 复核确认数量。 */
    @Schema(description = "复核确认数量")
    private BigDecimal actualQty;

    /** WCS/RMS 任务号。 */
    @Schema(description = "WCS/RMS 任务号")
    private String taskNo;

    /** 任务状态：未下发/已受理/执行中/已完成/失败/已关闭。 */
    @Schema(description = "任务状态")
    private String taskStatus;

    /** 任务失败原因。 */
    @Schema(description = "任务失败原因")
    private String taskError;

    /** 复核状态：待复核/一致/不一致/已放行。 */
    @Schema(description = "复核状态")
    private String reviewStatus;

    /** 复核扫描码。 */
    @Schema(description = "复核扫描码")
    private String scanCode;

    /** 复核说明/放行原因。 */
    @Schema(description = "复核说明/放行原因")
    private String reviewNote;

    /** 删除时间戳（epoch 毫秒）：0 = 未删除。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
