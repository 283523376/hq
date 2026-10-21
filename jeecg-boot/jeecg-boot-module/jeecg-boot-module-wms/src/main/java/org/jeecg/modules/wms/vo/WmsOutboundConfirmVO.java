package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 出库确认结果（销售订单未同步时返回 needSaleOrderConfirm）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库确认结果")
public class WmsOutboundConfirmVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否已完成出库")
    private boolean confirmed;

    @Schema(description = "是否需要操作员确认继续出库（销售订单未同步）")
    private boolean needSaleOrderConfirm;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "出库单号")
    private String outboundNo;

    public static WmsOutboundConfirmVO ok(String outboundNo) {
        WmsOutboundConfirmVO vo = new WmsOutboundConfirmVO();
        vo.setConfirmed(true);
        vo.setOutboundNo(outboundNo);
        vo.setMessage("出库完成");
        return vo;
    }

    public static WmsOutboundConfirmVO needConfirm(String outboundNo, String message) {
        WmsOutboundConfirmVO vo = new WmsOutboundConfirmVO();
        vo.setConfirmed(false);
        vo.setNeedSaleOrderConfirm(true);
        vo.setOutboundNo(outboundNo);
        vo.setMessage(message);
        return vo;
    }
}
