package org.jeecg.modules.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 外购/受托原材料到货登记入参（RK-001 V2.4，spec §3.2）。
 *
 * <p>两种来源 × 两种处理方式共四个组合：
 * <ul>
 *   <li>{@code sourceType=PURCHASE}（外购）/{@code CONSIGNED}（受托）；</li>
 *   <li>{@code handlingType=DIRECT}（段差棒直入，入库存并回写金蝶入库单）/
 *       {@code CONVERT}（大改小，只登记、不入库存，交改制单使用）。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
public class WmsRawInboundDTO {

    /** 来源类型：PURCHASE 外购 / CONSIGNED 受托 */
    private String sourceType;

    /** 处理方式：CONVERT 大改小（只登记）/ DIRECT 段差棒直入（上架入账） */
    private String handlingType;

    /**
     * ERP 订单号：外购填采购订单号，受托填受托加工订单号。
     *
     * <p>到货登记必须能在 ERP 查到这张单（Reader 校验），因此该字段必填。
     * 落库进 {@code wms_inbound_order.purchase_order_no}（受托单同列，回写备注区分）。
     */
    private String erpOrderNo;

    /**
     * 受托方ID（{@code wms_customer.id}，客户档案）：{@code sourceType=CONSIGNED} 时必填。
     *
     * <p>V2.4 起受托方与金蝶保持一致（金蝶里受托方是客户 BD_Customer），不再复用供应商档案。
     * 落库进入库单的 {@code consignor_*} 三列，同时作为受托库存行的 {@code consignor_id}
     * ——受托货权属该方，退货/报废都按这个维度找账。
     */
    private String consignorId;

    /**
     * 外购供应商ID（{@code wms_supplier.id}）：{@code sourceType=PURCHASE} 时<strong>必填</strong>。
     *
     * <p>金蝶采购订单 Reader 只返回单号/物料/数量，取不到供应商，必须由调用方补充；
     * 缺供应商时采购收料单回写会缺少 {@code FSupplierID}（金蝶报必填），
     * 因此服务层在建单前直接拒绝（V2.4 Task 9）。
     */
    private String supplierId;

    /** 到货明细 */
    private List<Item> details;

    /** 到货明细行 */
    @Data
    public static class Item {

        /** 物料ID（wms_material.id） */
        private String materialId;

        /** 到货数量 */
        private BigDecimal quantity;

        /** 批次号 */
        private String batchNo;

        /** 来源物料编码（改制投入物料；普通直入为空） */
        private String sourceMaterialCode;
    }
}
