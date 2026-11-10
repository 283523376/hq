package org.jeecg.modules.wms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @Description: CK-002 销售订单/销售出库创建入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "销售订单创建入参")
public class WmsOutboundSalesCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "销售订单号")
    private String salesOrderNo;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "要货日期（可空，默认当天）")
    private Date deliveryDate;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "销售明细")
    private List<WmsOutboundDetailDTO> details;
}
