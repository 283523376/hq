package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 采购入库单（收料通知单与采购入库单合一，RK-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsInboundService extends JeecgService<WmsInboundOrder> {

    /**
     * 扫码/录入采购订单号收料：校验采购订单与物料，生成入库单（待检验）与检验任务；
     * 外包装损坏时直接生成「已拒收」单据，不建检验任务。
     *
     * @param dto 收料入参
     * @return 生成的入库单
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    WmsInboundOrder receive(WmsReceiveDTO dto);

    /**
     * 检验结果回写单据状态（检验服务唯一的状态入口，避免跨域直改他人表）。
     *
     * @param inboundId 入库单ID
     * @param passed    true=合格（转待分配）；false=不合格（转检验不合格，终态）
     * @throws RuntimeException 单据不存在或状态不是待检验
     */
    void onInspectResult(String inboundId, boolean passed);

    /**
     * 自动分配库位：按明细物料的分类类型映射库区（半成品→立库、辅料→线边柜，
     * 其他→不自动分配；口径 9/10）。库位不足时已分配部分生效并返回告警。
     *
     * @param inboundId 入库单ID
     * @return 分配结果（含告警与未分配明细）
     * @throws RuntimeException 单据不存在或状态不允许分配
     */
    org.jeecg.modules.wms.vo.WmsAllocateResultVO autoAllocate(String inboundId);

    /**
     * 上架确认（同一事务内）：写实收/库位 → 库存聚合 upsert → 库位翻「占用」→
     * 单据转已入库 → 自动审核（system）→ 触发 ERP 同步（失败不回滚审核，落同步失败）。
     *
     * @param inboundId 入库单ID
     * @param dto       上架明细（实收数量、库位）
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    void putaway(String inboundId, org.jeecg.modules.wms.dto.WmsPutawayDTO dto);

    /**
     * 「同步中」僵死阈值，供补偿任务复用同一口径。
     */
    java.util.Date syncStaleBefore();

    /**
     * 乐观认领：仅已审核且 待同步/同步失败/同步中已僵死 的单据可被认领。
     *
     * @return 受影响行数；0 表示已被他人持有
     */
    int claimForSync(String id, java.util.Date staleBefore);

    /**
     * 取待补偿单据（定时任务用），避免定时任务直接操作 Mapper。
     */
    java.util.List<WmsInboundOrder> selectSyncCandidates(java.util.Date staleBefore, int limit);

    /**
     * 写回同步结果。集中在一处，避免服务与定时任务各写一遍。
     */
    void persistSyncResult(String id, org.jeecg.modules.wms.sync.WmsOrderSyncAttempt attempt);
}
