package org.jeecg.modules.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 收料入参（RK-001：扫码/录入采购订单号收料）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsReceiveDTO {

    /** 采购订单号（wms_order.order_no） */
    private String purchaseOrderNo;

    /** 外包装是否损坏；true → 单据直接转「已拒收」，不建检验任务 */
    private Boolean packageDamaged;

    /** 收料明细 */
    private List<Item> details;

    /** 收料明细行 */
    @Data
    public static class Item {
        /** 物料ID */
        private String materialId;
        /** 收料数量（应收） */
        private BigDecimal quantity;
        /** 批次号 */
        private String batchNo;
    }
}
