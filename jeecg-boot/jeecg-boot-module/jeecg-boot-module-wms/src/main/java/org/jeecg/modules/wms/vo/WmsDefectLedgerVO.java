package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 不良品台账行（XT-001）。
 *
 * <p>数据来自废品仓的实时库存行，**不存快照**；来源与处置历史按需关联查询。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Accessors(chain = true)
@Schema(description = "不良品台账行")
public class WmsDefectLedgerVO {

    @Schema(description = "库存行 id（报废时回传）")
    private String inventoryId;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "规格")
    private String spec;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "仓库编码")
    private String warehouseCode;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "当前数量")
    private BigDecimal quantity;

    @Schema(description = "锁定数量")
    private BigDecimal lockedQty;

    @Schema(description = "可报废数量 = 数量 - 锁定")
    private BigDecimal availableQty;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "批次首次入库时间（货龄起算点）")
    private Date firstInboundTime;

    @Schema(description = "来源工单号（该批次最近一次不良品入库的工单）")
    private String sourceWorkOrderNo;

    @Schema(description = "最近一次报废时间")
    private Date lastScrapTime;
}
