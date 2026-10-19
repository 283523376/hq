package org.jeecg.modules.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 上架确认入参（RK-001 step5-6：分配结果允许调整库位、录入实收数量）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsPutawayDTO {

    /** 上架明细行 */
    private List<Item> details;

    /** 上架明细行 */
    @Data
    public static class Item {
        /** 入库明细ID */
        private String detailId;
        /** 实收数量（0=短装跳过，不入库） */
        private BigDecimal actualQuantity;
        /** 库位ID */
        private String locationId;
    }
}
