package org.jeecg.modules.wms.mapper;

import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.vo.WmsBatchFlowVO;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;

import java.util.List;

/**
 * 批次追溯：当前库存明细 + 流转记录（跨 5 张明细表 UNION）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface WmsBatchTraceMapper {

    /** 某批次的当前库存明细（跨物料/库位/容器）。 */
    List<WmsInventoryStockRowVO> selectBatchStock(@Param("batchNo") String batchNo);

    /** 某批次的流转记录，按时间倒序。 */
    List<WmsBatchFlowVO> selectBatchFlow(@Param("batchNo") String batchNo,
                                         @Param("limit") int limit);
}
