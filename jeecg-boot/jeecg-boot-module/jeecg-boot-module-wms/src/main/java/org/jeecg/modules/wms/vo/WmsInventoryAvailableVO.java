package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 出库可用库存行（物料 + 库位/容器 + 可用量）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库可用库存行")
public class WmsInventoryAvailableVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "库存行ID")
    private String id;

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "库位ID")
    private String locationId;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "料箱/托盘编码")
    private String containerCode;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "库存数量")
    private BigDecimal quantity;

    @Schema(description = "已锁定数量")
    private BigDecimal lockedQty;

    @Schema(description = "入库时间（FIFO 排序用）")
    private Date createTime;
}
