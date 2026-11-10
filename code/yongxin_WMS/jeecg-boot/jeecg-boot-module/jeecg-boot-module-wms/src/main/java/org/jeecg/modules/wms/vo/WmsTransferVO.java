package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.entity.WmsTransferTask;

import java.io.Serializable;
import java.util.List;

/**
 * @Description: 调拨单详情（主表 + 明细 + 搬运任务 + ERP 等待时长）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "调拨单详情")
public class WmsTransferVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "调拨单主表")
    private WmsTransferOrder order;

    @Schema(description = "调拨明细")
    private List<WmsTransferDetail> details;

    @Schema(description = "搬运任务（仅同仓区域搬运）")
    private List<WmsTransferTask> tasks;

    @Schema(description = "处于「待ERP审核」时的已等待小时数；其它状态为 null")
    private Long waitingHours;
}
