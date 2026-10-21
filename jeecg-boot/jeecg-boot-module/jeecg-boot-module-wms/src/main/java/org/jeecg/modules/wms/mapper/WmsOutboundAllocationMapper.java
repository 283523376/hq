package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;

/**
 * 出库分配明细。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface WmsOutboundAllocationMapper extends BaseMapper<WmsOutboundAllocation> {

    /** 按任务号取分配行（回执认领用）。 */
    WmsOutboundAllocation selectByTaskNo(@Param("taskNo") String taskNo);

    /**
     * 认领任务完成：只允许 已受理/执行中/失败 收敛为 已完成。
     * 影响行数 0 即重复回执，调用方不得再写库存。
     */
    int claimFinished(@Param("taskNo") String taskNo);

    /** 设备执行中。 */
    int markRunning(@Param("taskNo") String taskNo);

    /** 设备失败。 */
    int markFailed(@Param("taskNo") String taskNo, @Param("error") String error);
}
