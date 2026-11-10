package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsBatchFlowVO;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;

import java.util.List;

/**
 * @Description: 批次追溯（KC-001 任务 5）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface IWmsBatchTraceService {

    /** 某批次的当前库存明细。 */
    List<WmsInventoryStockRowVO> batchStock(String batchNo);

    /** 某批次的流转记录，时间倒序。 */
    List<WmsBatchFlowVO> batchFlow(String batchNo, int limit);
}
