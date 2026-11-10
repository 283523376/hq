package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 调拨搬运任务（CK-004，同仓区域搬运走米塔 RMS）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "调拨搬运任务")
@TableName("wms_transfer_task")
public class WmsTransferTask extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 调拨单ID。 */
    @Schema(description = "调拨单ID")
    private String transferId;

    /** 调拨明细ID。 */
    @Schema(description = "调拨明细ID")
    private String detailId;

    /** 锁定的库存行ID（wms_inventory.id）。 */
    @Schema(description = "锁定的库存行ID")
    private String inventoryId;

    /** 本行锁定量（= 本行分配的调拨数量）。 */
    @Schema(description = "本行锁定量")
    private BigDecimal allocatedQty;

    /** RMS 任务号（前缀 RMS）。 */
    @Schema(description = "RMS 任务号")
    private String taskNo;

    /** 容器编码。 */
    @Schema(description = "容器编码")
    private String containerCode;

    /** 目标区域编码（米塔 zoneCode）。 */
    @Schema(description = "目标区域编码")
    private String zoneCode;

    /** 任务状态：未下发/已受理/执行中/已完成/失败/已关闭。 */
    @Schema(description = "任务状态")
    private String taskStatus;

    /** 失败原因（受理成功不写）。 */
    @Schema(description = "失败原因")
    private String taskError;

    /** 是否模拟受理（RMS 地址未配置）。 */
    @Schema(description = "是否模拟受理")
    private Boolean mocked;

    /** 设备回执时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "设备回执时间")
    private Date feedbackTime;

    /** 逻辑删除时间戳。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
