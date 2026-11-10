package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description: 审计日志查询条件（XT-002）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Schema(description = "审计日志查询条件")
public class WmsAuditQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务类型")
    private String bizType;

    @Schema(description = "业务单号（模糊）")
    private String bizNo;

    @Schema(description = "操作人")
    private String operator;

    /**
     * 全局关键词：业务单号/操作人 任意连续子串，口径为「单号 OR 操作人」任一命中即返回。
     * 与 bizNo/operator 单独条件可叠加（关键词成组括号，不与其余 eq 条件串优先级）。
     */
    @Schema(description = "全局关键词：业务单号/操作人 任意连续子串")
    private String keyword;

    @Schema(description = "通道")
    private String channel;

    @Schema(description = "结果：成功/失败")
    private String result;

    @Schema(description = "发生时间起")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @Schema(description = "发生时间止")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    @Schema(description = "页码")
    private Integer pageNo = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
