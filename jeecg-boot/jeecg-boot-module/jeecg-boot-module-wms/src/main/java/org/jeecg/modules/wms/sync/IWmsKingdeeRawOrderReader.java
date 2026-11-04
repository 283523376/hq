package org.jeecg.modules.wms.sync;

/**
 * 金蝶采购管理单据读取（采购订单 / 受托加工订单）。
 *
 * <p>V2.4 §3.2 到货登记需要校验 ERP 侧的单据与物料数量；单据 formId 由系统配置
 * （{@code kingdee.form.purchase-order} / {@code kingdee.form.consigned-order}）提供，
 * 未配置即显式失败——不得返回空结果让到货登记误以为「ERP 没有这张单」。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeeRawOrderReader {

    /**
     * 查询采购订单（按单据编号）。
     *
     * @param billNo 采购订单号
     * @return 金蝶原始查询结果；字段为最小集（单号、物料编码、数量）
     * @throws IllegalStateException 未配置采购订单 formId
     */
    WmsKingdeeResult queryPurchaseOrder(String billNo);

    /**
     * 查询受托加工订单（按单据编号）。
     *
     * @param billNo 受托加工订单号
     * @return 金蝶原始查询结果；字段为最小集（单号、物料编码、数量）
     * @throws IllegalStateException 未配置受托加工订单 formId
     */
    WmsKingdeeResult queryConsignedOrder(String billNo);
}
