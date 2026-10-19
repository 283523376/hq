package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsWorkOrder;

/**
 * 生产工单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsWorkOrderMapper extends BaseMapper<WmsWorkOrder> {

    /**
     * 按工单号锁定工单行（提交事务内使用，防止并发提交同时越过计划量）。
     */
    WmsWorkOrder selectByNoForUpdate(@Param("workOrderNo") String workOrderNo);
}
