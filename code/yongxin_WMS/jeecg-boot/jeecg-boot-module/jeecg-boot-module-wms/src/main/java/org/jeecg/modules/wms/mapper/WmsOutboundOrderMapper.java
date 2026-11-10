package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.Date;
import java.util.List;

/**
 * 出库单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface WmsOutboundOrderMapper extends BaseMapper<WmsOutboundOrder> {

    /** 乐观认领出库单同步。 */
    int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore);

    /** 出库单同步候选。 */
    List<WmsOutboundOrder> selectSyncCandidates(@Param("staleBefore") Date staleBefore,
                                                @Param("limit") int limit);

    /** 已分配但超过阈值仍未下发的单据。 */
    List<WmsOutboundOrder> selectUndispatchedBefore(@Param("before") Date before,
                                                    @Param("limit") int limit);

    /** 回写出库单同步结果。 */
    int persistSyncResult(@Param("id") String id, @Param("status") String status,
                          @Param("retryCount") Integer retryCount, @Param("error") String error);
}
