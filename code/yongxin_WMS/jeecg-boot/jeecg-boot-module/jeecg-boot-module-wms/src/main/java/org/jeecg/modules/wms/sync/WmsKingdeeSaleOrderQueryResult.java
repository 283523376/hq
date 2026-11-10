package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 金蝶销售订单查询结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "金蝶销售订单查询结果")
public class WmsKingdeeSaleOrderQueryResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "失败原因")
    private String message;

    @Schema(description = "是否模拟（金蝶未启用）")
    private boolean mocked;

    @Schema(description = "订单列表")
    private List<WmsKingdeeSaleOrder> orders = new ArrayList<>();

    public static WmsKingdeeSaleOrderQueryResult ok(List<WmsKingdeeSaleOrder> orders) {
        WmsKingdeeSaleOrderQueryResult result = new WmsKingdeeSaleOrderQueryResult();
        result.setSuccess(true);
        result.setOrders(orders == null ? new ArrayList<>() : orders);
        return result;
    }

    public static WmsKingdeeSaleOrderQueryResult mock() {
        WmsKingdeeSaleOrderQueryResult result = ok(new ArrayList<>());
        result.setMocked(true);
        return result;
    }

    public static WmsKingdeeSaleOrderQueryResult fail(String message) {
        WmsKingdeeSaleOrderQueryResult result = new WmsKingdeeSaleOrderQueryResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
