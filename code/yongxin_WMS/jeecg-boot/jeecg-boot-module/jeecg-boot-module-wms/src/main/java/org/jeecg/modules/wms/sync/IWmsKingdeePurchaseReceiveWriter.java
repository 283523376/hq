package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 采购收料单回写（V2.4 §3.2 段差棒直入）。
 *
 * <p><b>为什么回写收料单而不是采购入库单</b>：101 账套内全部原材料都启用了来料检验，
 * 采购入库单（{@code STK_InStock}）不允许由外部直接创建，只能由金蝶在质检通过后
 * 从采购收料单（{@code PUR_ReceiveBill}）下推生成（用户 2026-11-04 拍板，路线 A）。
 *
 * <p>金蝶 formId 由配置项 {@code kingdee.form.purchase-inbound} 提供，未配置即显式失败
 * （配置键名沿用历史命名，避免动已执行的 Flyway 与已保存的配置值）。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeePurchaseReceiveWriter {

    /**
     * 金蝶同步总开关（{@code wms.sync.erp.kingdee.enabled}）。
     *
     * <p>给调用方判断是否发起回写；Writer 不因开关关闭而返回 mock 成功。
     */
    boolean isEnabled();

    /**
     * 把段差棒直入的 WMS 采购入库单回写为金蝶采购收料单（Save）。
     *
     * @param order WMS 入库单（{@code source_type=PURCHASE}）
     * @return 金蝶保存结果；成功时 {@code number} 为 ERP 单号（{@code CGSL*}）
     * @throws IllegalStateException 未配置采购收料单 formId
     */
    WmsKingdeeResult write(WmsInboundOrder order);
}
