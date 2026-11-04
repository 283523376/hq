package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 受托加工材料入库单回写（V2.4 §3.2 受托入库）。
 *
 * <p>金蝶 formId 由配置项 {@code kingdee.form.consigned-inbound} 提供，未配置即显式失败。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeeConsignedInboundWriter {

    /**
     * 金蝶同步总开关（{@code wms.sync.erp.kingdee.enabled}）。
     *
     * <p>给调用方判断是否发起回写；Writer 不因开关关闭而返回 mock 成功。
     */
    boolean isEnabled();

    /**
     * 把受托到货的 WMS 入库单回写为金蝶受托加工材料入库单（Push → Save → Submit）。
     *
     * <p>必须先「销售订单下推」再 Save：源单关联、货主/保管者等字段只能由转换规则带出，
     * 直接 Save 必然失败（2026-11-04 真账套实测）。因此本方法的成功结果表示单据已提交，
     * 调用方<strong>不应再次 Submit</strong>。
     *
     * @param order WMS 入库单（{@code source_type=CONSIGNED}）
     * @return 金蝶提交结果；成功时 {@code number} 为 ERP 单号
     * @throws IllegalStateException 未配置受托加工材料入库单 formId
     */
    WmsKingdeeResult write(WmsInboundOrder order);
}
