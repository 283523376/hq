package org.jeecg.modules.wms.vo;

import lombok.Data;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInspectionTask;

import java.util.List;

/**
 * 入库单详情出参（单据 + 明细 + 检验任务）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsInboundDetailVO {

    /** 单据 */
    private WmsInboundOrder order;

    /** 明细 */
    private List<WmsInboundDetail> details;

    /** 检验任务 */
    private WmsInspectionTask inspection;
}
