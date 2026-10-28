package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 批次流转记录（入库/生产入库/出库/调拨/盘点 统一视图）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "批次流转记录")
public class WmsBatchFlowVO {

    public static final String TYPE_INBOUND = "INBOUND";
    public static final String TYPE_PROD_INBOUND = "PROD_INBOUND";
    public static final String TYPE_OUTBOUND = "OUTBOUND";
    public static final String TYPE_TRANSFER = "TRANSFER";
    public static final String TYPE_STOCKTAKE = "STOCKTAKE";

    @Schema(description = "发生时间")
    private Date eventTime;

    @Schema(description = "事件类型：INBOUND / PROD_INBOUND / OUTBOUND / TRANSFER / STOCKTAKE")
    private String eventType;

    @Schema(description = "单据号")
    private String billNo;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "容器/料箱编码")
    private String containerCode;

    @Schema(description = "数量（正=入库/盘盈，负=出库/盘亏）")
    private BigDecimal qty;

    @Schema(description = "往来方：供应商/客户/目标库位/差异原因")
    private String counterparty;

    @Schema(description = "操作人")
    private String operator;

    @Schema(description = "备注")
    private String remark;
}
