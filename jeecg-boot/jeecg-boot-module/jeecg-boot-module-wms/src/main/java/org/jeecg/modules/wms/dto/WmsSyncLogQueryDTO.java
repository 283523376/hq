package org.jeecg.modules.wms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description: SB-001 同步日志查询条件（spec 2026-11-03 §2.9）。
 *
 * <p>时间字段必须带 {@code @DateTimeFormat}：前端传的是 {@code yyyy-MM-dd HH:mm:ss}
 * 字符串，缺注解会在参数绑定阶段直接 400（XT-002 评审 Critical 的同类问题）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@Schema(description = "SB-001 同步日志查询条件")
public class WmsSyncLogQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "方向：ERP_TO_WMS/WMS_TO_ERP/WMS_TO_MES")
    private String direction;

    @Schema(description = "对象类型：WORK_ORDER/INVENTORY_RECONCILE/...")
    private String objectType;

    @Schema(description = "业务单号（模糊）")
    private String objectNo;

    @Schema(description = "对端：KINGDEE/MES/WCS/RMS")
    private String target;

    @Schema(description = "结果：同步成功/同步失败")
    private String status;

    @Schema(description = "发生时间起")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @Schema(description = "发生时间止")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
}
