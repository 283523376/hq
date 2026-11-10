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

    /** CK-001：创建领料出库单（领取人取登录账号，可指定设备与本次领料数量）。 */
    WmsOutboundOrder createPick(WmsOutboundPickCreateDTO dto);

    /**
     * CK-001：取消领料出库单并站内通知工单创建人。
     *
     * <p>仅「草稿/待分配/已分配」可取消；写入 {@code cancel_reason} 并置「已关闭」。
     * 通知收件人取工单 {@code create_by}，为空回退默认收件人；**通知失败不影响取消**。
     *
     * @param id       出库单 ID
     * @param reason   取消原因（不能为空）
     * @param operator 操作人登录账号（为空取当前登录账号）
     */
    void cancelPick(String id, String reason, String operator);

    /** CK-002：创建销售订单对应的出库单。 */
    WmsOutboundOrder createSales(WmsOutboundSalesCreateDTO dto);

    /**
     * 按金蝶发货通知单生成销售出库单（出库单反向生成）。
     *
     * <p>与 {@link #createSales} 的区别：**不依赖 WMS 销售订单快照**——发货通知单的源销售订单
     * 未必已同步（2026-10-28 探测结论），客户/数量直接取通知单。
     *
     * @param noticeNo      发货通知单号（写入 source_bill_no，用于幂等与分批扣减）
     * @param customerCode  客户编码
     * @param customerName  客户名称
     * @param deliveryDate  要货日期（可空）
     * @param remark        备注
     * @param details       明细（物料须已在 WMS 建档）
     */
    WmsOutboundOrder createSalesFromNotice(String noticeNo, String customerCode, String customerName,
                                           java.util.Date deliveryDate, String remark,
                                           java.util.List<org.jeecg.modules.wms.dto.WmsOutboundDetailDTO> details);

    /**
     * 建样品出库单：不绑定工单/销售订单，出库后按 E5 确认的形态回写金蝶。
     */
    WmsOutboundOrder createSample(org.jeecg.modules.wms.dto.WmsOutboundSampleCreateDTO dto);

    /** CK-002：预览 ERP 销售订单（含分录与剩余可出数量）。 */
    org.jeecg.modules.wms.vo.WmsSalesOrderVO previewSalesOrder(String salesOrderNo);

    /** CK-002：拉取 ERP 销售订单（按单号或日期窗口）。 */
    int syncSalesOrder(org.jeecg.modules.wms.dto.WmsSalesOrderSyncDTO dto);

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
