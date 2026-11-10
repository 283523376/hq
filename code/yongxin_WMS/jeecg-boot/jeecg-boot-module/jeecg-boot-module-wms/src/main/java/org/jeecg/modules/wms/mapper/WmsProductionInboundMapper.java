package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsProductionInbound;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 生产入库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsProductionInboundMapper extends BaseMapper<WmsProductionInbound> {

    /** 待对账的工序汇报入库单：已入库且 erp_bill_no 仍是 GXHB 单号（尚未回填 ERP 生产入库单号）。 */
    List<WmsProductionInbound> selectReportSourcedPendingReconcile(@Param("limit") int limit);

    /**
     * 查该载具当前未完结的记录（待上架/执行中/异常待人工）。
     */
    WmsProductionInbound selectActiveByCarrier(@Param("carrierCode") String carrierCode);

    /**
     * 工单已占用数量：未删除且状态非「已关闭」的生产入库记录 quantity 之和。
     */
    BigDecimal sumInboundedQty(@Param("workOrderNo") String workOrderNo);

    /**
     * 认领入库完成：待上架/执行中/异常待人工 → 已入库。
     *
     * @return 影响行数；0 表示已被处理过（重复回执）
     */
    int claimFinished(@Param("taskNo") String taskNo, @Param("stackCode") String stackCode);

    /**
     * 取待同步记录（已入库 + 待同步/同步失败/同步中已僵死）。
     */
    List<WmsProductionInbound> selectSyncCandidates(@Param("staleBefore") Date staleBefore,
                                                    @Param("limit") int limit);

    /**
     * 乐观认领同步：只有待同步/同步失败/同步中已僵死的已入库记录可被认领。
     *
     * @return 影响行数；0 表示已被他人持有
     */
    int claimForSync(@Param("id") String id, @Param("staleBefore") Date staleBefore);

    /**
     * 取超过阈值仍「未下发」的记录（JVM 崩溃或下发前中断的补偿）。
     */
    List<WmsProductionInbound> selectUndispatchedBefore(@Param("before") Date before,
                                                         @Param("limit") int limit);
}
