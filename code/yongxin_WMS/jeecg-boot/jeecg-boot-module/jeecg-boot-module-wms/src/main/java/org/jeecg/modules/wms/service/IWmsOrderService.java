package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;

import java.util.Date;
import java.util.List;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsOrderService extends JeecgService<WmsOrder> {

    /** 新增订单（订单号 trim + 查重，状态置为「待审核」）。 */
    void addOrder(WmsOrder order);

    /** 编辑订单（仅「待审核」或「已驳回」可编辑）。 */
    void editOrder(WmsOrder order);

    /** 审核订单：通过 → 已审核；驳回 → 已驳回（原因必填）。 */
    void auditOrder(WmsOrderAuditDTO dto);

    /** 删除订单（逻辑删除，仅「待审核」或「已驳回」可删）。 */
    void removeOrder(String id);

    /** 批量删除订单（逻辑删除）。 */
    void removeBatch(List<String> ids);

    /** 判断订单号是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkOrderNoExists(String orderNo, String id);

    /** 回填供应商名称与编码（非持久化字段，供列表展示与同步报文使用）。 */
    void fillSupplierInfo(List<WmsOrder> list);

    /**
     * 下达订单：状态置「已下达」→ 认领 → 同步 ERP/MES。
     *
     * @return 同步结果；{@code synced=null} 表示未抢到认领、本次未执行同步
     */
    WmsOrderSyncOutcomeVO dispatchOrder(String id);

    /**
     * 重新同步（仅「已下达 且 同步失败/待同步」）。
     */
    WmsOrderSyncOutcomeVO resyncOrder(String id);

    /**
     * 乐观认领：把可同步的订单置为「同步中」。定时补偿任务经此入口，避免直接操作 Mapper。
     *
     * @return 受影响行数；1 表示认领成功，0 表示已被他人持有
     */
    int claimForSync(String id, Date staleBefore);

    /**
     * 取待补偿订单（定时任务用），避免定时任务直接操作 Mapper。
     */
    List<WmsOrder> selectSyncCandidates(Date staleBefore, int limit);

    /**
     * 写回同步结果。集中在一处，避免订单服务与定时任务各写一遍。
     */
    void persistSyncResult(String id, WmsOrderSyncAttempt attempt);

    /**
     * 「同步中」僵死阈值，供定时任务复用同一口径。
     */
    Date syncStaleBefore();

    /**
     * 按采购订单号取采购订单（RK-001 收料的存在性校验与供应商带出）。
     *
     * @param orderNo 采购订单号（wms_order.order_no）
     * @return 采购订单；不存在或类型不是「采购订单」时返回 null
     */
    WmsOrder getPurchaseOrder(String orderNo);
}
