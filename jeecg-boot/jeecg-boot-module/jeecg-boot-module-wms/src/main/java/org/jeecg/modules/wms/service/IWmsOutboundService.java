package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSalesCreateDTO;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

/**
 * 出库单（CK-001 生产领料 / CK-002 销售出库）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsOutboundService extends IService<WmsOutboundOrder> {

    /** CK-001：扫码校验工单并调出需求明细。 */
    org.jeecg.modules.wms.vo.WmsOutboundScanVO scanPick(String workOrderNo);

    /** CK-001：创建领料出库单。 */
    WmsOutboundOrder createPick(WmsOutboundPickCreateDTO dto);

    /** CK-002：创建销售订单对应的出库单。 */
    WmsOutboundOrder createSales(WmsOutboundSalesCreateDTO dto);

    /** 分配库位/料箱并锁定库存。 */
    void allocate(String id);

    /** 下发设备任务。 */
    void dispatch(String id);

    /** 分页查询。 */
    IPage<WmsOutboundOrder> listPage(Integer pageNo, Integer pageSize,
                                     String outboundType, String status, String keyword);

    /** 任务号是否属于出库分配行（回调路由用）。 */
    boolean isOutboundTask(String taskNo);

    /** 设备回执：FINISHED/RUNNING/OUTSTATION/FAILED。返回是否已全部完成。 */
    boolean onTaskFeedback(String taskNo, String containerCode, String taskStatus,
                           String message, boolean rms);

    /** CK-002 复核。 */
    void review(String id, org.jeecg.modules.wms.dto.WmsOutboundReviewDTO dto);

    /** CK-001 取料确认。 */
    org.jeecg.modules.wms.vo.WmsOutboundConfirmVO confirmPick(
            String id, org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO dto);

    /** CK-002 确认出库。 */
    org.jeecg.modules.wms.vo.WmsOutboundConfirmVO confirm(
            String id, org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO dto);

    /** 关单。 */
    void close(String id, String reason);

    /** 详情：主表 + 需求明细 + 分配明细。 */
    org.jeecg.modules.wms.vo.WmsOutboundDetailVO detail(String id);
}
