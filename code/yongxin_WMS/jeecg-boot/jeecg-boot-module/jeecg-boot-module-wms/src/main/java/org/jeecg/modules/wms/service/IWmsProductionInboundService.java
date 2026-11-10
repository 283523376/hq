package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsProductionManualCompleteDTO;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

import java.util.Date;
import java.util.List;

/**
 * 生产入库（YX-WMS-RK-002）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsProductionInboundService extends JeecgService<WmsProductionInbound> {

    /**
     * 提交生产入库：校验工单与计划量 → 载具占用 → 分配库位/确定默认位 →
     * 同事务写主表与不良品库存。
     *
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    WmsProductionInbound submit(WmsProductionSubmitDTO dto);

    /**
     * 设备任务回执（RMS/WCS 共用）。
     *
     * @return true 表示本次调用完成了入库（调用方随后触发同步）；
     *         false 表示状态推进或重复回执，不写库存
     */
    boolean onTaskFeedback(String taskNo, String carrierCode, String taskStatus,
                           String stackCode, String message, boolean rms);

    /**
     * 料箱位置更新：按载具码更新最近记录的 stack_code。
     *
     * @return 影响行数
     */
    int onContainerLocation(String containerCode, String stackCode);

    /**
     * 按任务号查询（回调鉴权与幂等判断用）。
     */
    WmsProductionInbound findByTaskNo(String taskNo);

    /**
     * 转人工完成：仅「异常待人工」可用；写良品库存并把状态推进到「已入库」。
     */
    void manualComplete(String id, WmsProductionManualCompleteDTO dto);

    /**
     * 关单：仅「待上架/异常待人工」可用；写良品库存以外的状态，释放半成品库位。
     */
    void close(String id, String reason);

    /**
     * 工序汇报草稿推进入库（方案 B）：草稿 → 待上架，占用立库库位并下发设备任务。
     *
     * <p>不参与 ERP 回写：ERP 侧的生产入库单由**车间在 ERP 做工序汇报**时生成，
     * WMS 只记账本仓库存，之后按 FSrcBillNo 对账回填 ERP 单号。
     *
     * @param id          生产入库记录 ID（必须是工序汇报草稿）
     * @param carrierCode 载具码（必填，≤64）
     */
    void promote(String id, String carrierCode);

    /**
     * 待对账的工序汇报入库单（已入库、erp_bill_no 仍是 GXHB 单号）。
     *
     * @param limit 单轮上限
     */
    List<WmsProductionInbound> selectReportSourcedPendingReconcile(int limit);

    /**
     * 「同步中」僵死阈值，供补偿任务复用同一口径。
     */
    Date syncStaleBefore();

    /**
     * 乐观认领同步：仅已入库且 待同步/同步失败/同步中已僵死 的记录可被认领。
     *
     * @return 受影响行数；0 表示已被他人持有
     */
    int claimForSync(String id, Date staleBefore);

    /**
     * 取待同步记录（定时任务用）。
     */
    List<WmsProductionInbound> selectSyncCandidates(Date staleBefore, int limit);

    /**
     * 写回同步结果。
     */
    void persistSyncResult(String id, WmsOrderSyncAttempt attempt);

    /**
     * 取超过阈值仍「未下发」的记录（下发补偿）。
     */
    List<WmsProductionInbound> selectUndispatchedBefore(Date before, int limit);

    /**
     * 分页查询（入库单号模糊；其余字段精确）。
     */
        /**
     * @param keyword 全局关键词：跨 inbound_no / work_order_no / erp_bill_no 模糊
     */
    IPage<WmsProductionInbound> listPage(int pageNo, int pageSize, WmsProductionInbound query, String keyword);

    /**
     * 工单关联的生产入库记录（BD-002 V2.2：工单详情展示，按提交时间倒序，上限 100 条）。
     */
    List<WmsProductionInbound> listByWorkOrderNo(String workOrderNo);



    /**
     * 详情；不存在抛「生产入库记录不存在」。
     */
    WmsProductionInbound detail(String id);
}
