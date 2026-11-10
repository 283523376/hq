package org.jeecg.modules.wms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description: 销售订单拉取入参（CK-002 方向反转）。
 *
 * <p>两种用法：给 `no` 按单号拉；或给 `beginDate/endDate` 按窗口拉「已审核(C)」订单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "销售订单拉取入参")
public class WmsSalesOrderSyncDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "销售订单号（与日期窗口二选一）")
    private String no;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "起始日期")
    private Date beginDate;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "结束日期")
    private Date endDate;
}
