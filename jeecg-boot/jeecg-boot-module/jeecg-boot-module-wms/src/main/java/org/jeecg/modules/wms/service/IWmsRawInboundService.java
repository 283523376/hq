package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsPutawayDTO;
import org.jeecg.modules.wms.dto.WmsRawInboundDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 外购/受托原材料到货登记与段差棒直入（RK-001 V2.4，spec §3.2）。
 *
 * <p>与 {@link IWmsInboundService}（采购入库检验链路，已停用）的区别：**跳过检验**。
 * 到货登记后直接「待分配」，上架时一步写库存 + 置「已审核」并回写金蝶，
 * 不调用 {@code IWmsInboundService.putaway}（那条链路绑定检验任务与旧 ERP 网关）。
 *
 * <p>大改小（CONVERT）只登记到货，不写库存：原材料直送车间，产出半成品走生产入库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
public interface IWmsRawInboundService extends JeecgService<WmsInboundOrder> {

    /**
     * 到货登记：校验 ERP 订单（金蝶 Reader）与物料，生成入库单。
     *
     * <ul>
     *   <li>{@code DIRECT}（段差棒直入）→ 状态「待分配」，等 {@link #putawayRaw} 上架入账；</li>
     *   <li>{@code CONVERT}（大改小）→ 状态直接「已审核」（登记即完成），不产生库存行，
     *       同步状态「无需同步」，单据返回给改制单引用。</li>
     * </ul>
     *
     * @param dto      到货入参
     * @param operator 操作人（无登录上下文时显式传入）
     * @return 生成的入库单（含明细）
     * @throws RuntimeException 校验失败；金蝶未启用或未配置 formId 同样显式失败
     */
    WmsInboundOrder receive(WmsRawInboundDTO dto, String operator);

    /**
     * 无检验上架：写库存（显式 {@code ownership_type}/{@code consignor_id}）→ 占用库位 →
     * 单据直接「已审核」→ 回写并提交金蝶入库单（采购入库 / 受托加工材料入库）。
     *
     * <p>回写失败**不回滚库存**：库存与单据状态已经生效，失败只落
     * {@code sync_status=同步失败} 与 {@code last_sync_error}；
     * 但金蝶同步开关关闭或未配置 formId 属于「配置未就绪」，在写入任何库存之前直接抛错。
     *
     * @param inboundId 入库单ID
     * @param dto       上架明细（实收数量、库位）
     * @param operator  操作人
     * @throws RuntimeException 单据/状态/库位/物料校验失败，或金蝶配置未就绪
     */
    void putawayRaw(String inboundId, WmsPutawayDTO dto, String operator);

    /**
     * 原材料入库单分页：<b>只含 {@code source_type} 为 PURCHASE/CONSIGNED 的单据</b>。
     *
     * <p>不复用 {@code /wms/inbound/list}：那是全量入库单（含生产入库、历史采购收料），
     * 混在一起既分不清口径，也会把 {@code source_type=NULL} 的旧单渲染成「大改小/直入」。
     *
     * @param sourceType    来源类型（PURCHASE/CONSIGNED），空=不限
     * @param inboundStatus 单据状态，空=不限
     * @param orderNo       入库单号/ERP 单号，任意连续子串模糊
     */
    IPage<WmsInboundOrder> listPage(int pageNo, int pageSize, String sourceType,
                                    String inboundStatus, String orderNo);
}
