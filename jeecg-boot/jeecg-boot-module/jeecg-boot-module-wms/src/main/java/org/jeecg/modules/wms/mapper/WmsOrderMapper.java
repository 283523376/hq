package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsOrder;

import java.util.Date;
import java.util.List;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsOrderMapper extends BaseMapper<WmsOrder> {

    /**
     * 乐观认领：把可同步的订单置为「同步中」，用于互斥「用户下达」与「定时补偿」两个触发源，
     * 防止同一张订单被重复投递给 ERP/MES。
     *
     * @param id          订单ID
     * @param staleBefore 「同步中」的僵死阈值；last_sync_time 早于此值才允许被回收
     * @return 受影响行数；1 表示认领成功，0 表示已被他人持有或状态不允许
     */
    int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore);

    /**
     * 取待补偿的订单：已下达且同步失败 / 待同步 / 「同步中」但已僵死。
     *
     * @param staleBefore 「同步中」的僵死阈值
     * @param limit       每轮上限
     */
    List<WmsOrder> selectSyncCandidates(@Param("staleBefore") Date staleBefore, @Param("limit") int limit);
}
