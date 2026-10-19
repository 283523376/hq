package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsInboundOrder;

import java.util.Date;
import java.util.List;

/**
 * 采购入库单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInboundOrderMapper extends BaseMapper<WmsInboundOrder> {

    /**
     * 乐观认领（补偿任务用）：仅「已审核」且 待同步/同步失败/同步中已僵死 的单据可被认领。
     *
     * @return 受影响行数；0 表示未抢到
     */
    int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore);

    /** 取待补偿单据（每轮限量，最久未动的优先）。 */
    List<WmsInboundOrder> selectSyncCandidates(@Param("staleBefore") Date staleBefore,
                                               @Param("limit") int limit);
}
