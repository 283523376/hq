package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 原材料上架 / 重试回写的结果（V2.4 C1）。
 *
 * <p><b>为什么要返回同步结果</b>：库存入账与回写金蝶是两个独立结果，回写失败**不回滚库存**
 * （既定口径）。若上架接口只返回 void，前端只能写死「已触发回写」——用户永远不知道实际失败了，
 * 也没有补偿入口。这里把落库后的同步状态原样带回，页面据此分支提示，列表再提供「重试回写」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@Accessors(chain = true)
@Schema(description = "原材料上架/重试回写结果")
public class WmsRawPutawayResultVO {

    @Schema(description = "同步状态：同步成功 / 同步失败 / 待同步 / 无需同步")
    private String syncStatus;

    @Schema(description = "ERP 单号（Save 成功即回存，Submit 失败时也有值）")
    private String erpBillNo;

    @Schema(description = "最近一次同步失败原因；成功时为 null")
    private String lastSyncError;
}
