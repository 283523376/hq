package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 一条审计事件（写入口的入参）。
 *
 * <p>{@code beforeValue}/{@code afterValue} 收的是**对象**（Map 或实体），
 * 由服务统一序列化成 JSON，调用方不必自己拼字符串。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Accessors(chain = true)
@Schema(description = "审计事件")
public class WmsAuditEvent {

    @Schema(description = "业务类型，取 WmsConstants.AUDIT_BIZ_*")
    private String bizType;

    /** 反查入口，必填。按行同步的场景用「单号#序列#工序号」。 */
    @Schema(description = "业务单号（必填）")
    private String bizNo;

    @Schema(description = "关联单号，用于串起链路")
    private String bizRef;

    @Schema(description = "动作，取 WmsConstants.AUDIT_ACTION_*")
    private String action;

    @Schema(description = "通道，取 WmsConstants.AUDIT_CHANNEL_*")
    private String channel;

    @Schema(description = "变更前值（对象，服务内部转 JSON）")
    private Object beforeValue;

    @Schema(description = "变更后值（对象，服务内部转 JSON）")
    private Object afterValue;

    @Schema(description = "操作人；留空则记为 system")
    private String operator;

    @Schema(description = "来源 IP")
    private String ip;

    @Schema(description = "同一次请求/同一批同步的串联 id")
    private String requestId;

    @Schema(description = "结果，取 WmsConstants.AUDIT_RESULT_*；留空按成功")
    private String result;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "耗时毫秒")
    private Integer costMs;
}
