package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Description: V2.4 差距落地常量与配置守卫。
 *
 * <p>受托库存、入库来源/处理方式、改制单状态、不良来源/处置方式这些取值会直接落库并参与
 * SQL 条件（{@code ownership_type='OWNED'} 等），写错一个字母就是「查不到库存」或
 * 「受托串自有账」；这里把设计文档 §3.1/§3.2/§3.3 的取值逐字钉死，
 * 任何引用必须走 {@link WmsConstants} 常量。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsGapConstantsTest {

    /** 库存所有权：spec §3.1 —— 受托必须带 consignor_id，自有为空串。 */
    @Test
    void ownershipConstants() {
        assertEquals("OWNED", WmsConstants.OWNERSHIP_OWNED);
        assertEquals("CONSIGNED", WmsConstants.OWNERSHIP_CONSIGNED);
    }

    /** 入库来源类型：spec §3.2 wms_inbound_order.source_type。 */
    @Test
    void inboundSourceConstants() {
        assertEquals("PURCHASE", WmsConstants.INBOUND_SOURCE_PURCHASE);
        assertEquals("CONSIGNED", WmsConstants.INBOUND_SOURCE_CONSIGNED);
    }

    /** 到货处理方式：spec §3.2 —— 大改小 / 段差棒直入。 */
    @Test
    void inboundHandlingConstants() {
        assertEquals("CONVERT", WmsConstants.INBOUND_HANDLING_CONVERT);
        assertEquals("DIRECT", WmsConstants.INBOUND_HANDLING_DIRECT);
    }

    /** 改制单四态：草稿 → 执行中 → 已完成（终态）；草稿 → 已取消（终态）。 */
    @Test
    void conversionStatusConstants() {
        assertEquals("DRAFT", WmsConstants.CONVERSION_STATUS_DRAFT);
        assertEquals("RUNNING", WmsConstants.CONVERSION_STATUS_RUNNING);
        assertEquals("DONE", WmsConstants.CONVERSION_STATUS_DONE);
        assertEquals("CANCELLED", WmsConstants.CONVERSION_STATUS_CANCELLED);
    }

    /** 不良来源：spec §3.3 —— 生产 / 受托两类。 */
    @Test
    void defectSourceConstants() {
        assertEquals("PRODUCTION", WmsConstants.DEFECT_SOURCE_PRODUCTION);
        assertEquals("CONSIGNED", WmsConstants.DEFECT_SOURCE_CONSIGNED);
    }

    /** 不良处置：spec §3.3 —— 入废品仓 / 退货。 */
    @Test
    void defectDisposalConstants() {
        assertEquals("SCRAP", WmsConstants.DEFECT_DISPOSAL_SCRAP);
        assertEquals("RETURN", WmsConstants.DEFECT_DISPOSAL_RETURN);
    }

    /** 物料分类类型：V2.4 恢复外购/受托原材料入库，分类值必须是「原材料」。 */
    @Test
    void categoryTypeRaw() {
        assertEquals("原材料", WmsConstants.CATEGORY_TYPE_RAW);
    }

    /**
     * 金蝶单据 formId 配置：键名钉死，默认空串（空 = 未配置）。
     *
     * <p>spec §6「formId 待控制台确认」——空串是**合法初始态**，由 Reader/Writer 在
     * 调用前显式报错；默认值不能写成猜测的 formId，否则会静默打到错误的单据。
     */
    @Test
    void kingdeeFormConfigKeys_defaultEmpty() {
        List<WmsConfigKey> keys = List.of(
                WmsConfigKey.KD_FORM_CONSIGNED_INBOUND,
                WmsConfigKey.KD_FORM_PURCHASE_INBOUND,
                WmsConfigKey.KD_FORM_CONSIGNED_RETURN,
                WmsConfigKey.KD_FORM_PURCHASE_ORDER,
                WmsConfigKey.KD_FORM_CONSIGNED_ORDER);
        List<String> expectedKeys = List.of(
                "kingdee.form.consigned-inbound",
                "kingdee.form.purchase-inbound",
                "kingdee.form.consigned-return",
                "kingdee.form.purchase-order",
                "kingdee.form.consigned-order");
        for (int i = 0; i < keys.size(); i++) {
            WmsConfigKey item = keys.get(i);
            assertEquals(expectedKeys.get(i), item.getKey());
            assertEquals("", item.getDefaultValue(), "formId 未确认前默认必须是空串：" + item.getKey());
            assertEquals(WmsConfigType.TEXT, item.getType(), "formId 是自由文本：" + item.getKey());
            assertEquals(WmsConfigGroup.KINGDEE, item.getGroup(), "formId 必须归入金蝶单据组：" + item.getKey());
        }
    }
}
