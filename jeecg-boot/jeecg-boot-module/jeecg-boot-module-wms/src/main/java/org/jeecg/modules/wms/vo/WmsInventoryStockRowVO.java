package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 库存行 + 物料/库区/库位快照，超期预警与批次库存查询共用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "库存行（含主数据快照）")
public class WmsInventoryStockRowVO {

    @Schema(description = "库存行ID")
    private String id;

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "库区编码")
    private String warehouseCode;

    @Schema(description = "库区名称")
    private String warehouseName;

    @Schema(description = "库位ID")
    private String locationId;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "容器/料箱编码")
    private String containerCode;

    @Schema(description = "库存数量")
    private BigDecimal quantity;

    @Schema(description = "锁定数量")
    private BigDecimal lockedQty;

    @Schema(description = "可用数量")
    private BigDecimal availableQty;

    @Schema(description = "批次首次入库时间")
    private Date firstInboundTime;
}
