package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 金蝶调拨单状态查询结果（A/B/C/D）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "金蝶调拨单状态")
public class WmsKingdeeTransferStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "查询是否成功")
    private boolean success;

    @Schema(description = "金蝶单据状态 A=暂存/B=审核中/C=已审核/D=作废；未查到为 null")
    private String documentStatus;

    @Schema(description = "失败原因")
    private String message;

    @Schema(description = "是否为模拟查询（金蝶未启用）")
    private boolean mocked;

    public static WmsKingdeeTransferStatus ok(String documentStatus) {
        WmsKingdeeTransferStatus result = new WmsKingdeeTransferStatus();
        result.setSuccess(true);
        result.setDocumentStatus(documentStatus);
        return result;
    }

    public static WmsKingdeeTransferStatus mock() {
        WmsKingdeeTransferStatus result = new WmsKingdeeTransferStatus();
        result.setSuccess(true);
        result.setMocked(true);
        return result;
    }

    public static WmsKingdeeTransferStatus fail(String message) {
        WmsKingdeeTransferStatus result = new WmsKingdeeTransferStatus();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
