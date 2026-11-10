package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.math.BigDecimal;

/**
 * @Description: 库存调拨明细（CK-004）。
 *
 * <p>单据只到「仓库/区域」级，实际库位在执行时落库（同仓取位置回调 stackCode，跨仓取上架库位），不回写 ERP。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存调拨明细")
@TableName("wms_transfer_detail")
public class WmsTransferDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 调拨单ID。 */
    @Schema(description = "调拨单ID")
    private String transferId;

    /** 行号。 */
    @Schema(description = "行号")
    private Integer lineNo;

    /** 物料ID。 */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码。 */
    @Excel(name = "物料编码", width = 20)
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称。 */
    @Excel(name = "物料名称", width = 24)
    @Schema(description = "物料名称")
    private String materialName;

    /** 批次号（空=执行时按 FIFO 取批次）。 */
    @Schema(description = "批次号（空=执行时 FIFO）")
    private String batchNo;

    /** 料箱/托盘编码（立体库为空串）。 */
    @Schema(description = "料箱/托盘编码")
    private String containerCode;

    /** 单位。 */
    @Schema(description = "单位")
    private String unit;

    /** 计划调拨数量。 */
    @Excel(name = "调拨数量", width = 12)
    @Schema(description = "计划调拨数量")
    private BigDecimal transferQty;

    /** 实际调出库位ID。 */
    @Schema(description = "实际调出库位ID")
    private String outLocationId;

    /** 实际调出库位编码。 */
    @Schema(description = "实际调出库位编码")
    private String outLocationCode;

    /** 实际调入库位ID。 */
    @Schema(description = "实际调入库位ID")
    private String inLocationId;

    /** 实际调入库位编码（位置回调 stackCode）。 */
    @Schema(description = "实际调入库位编码")
    private String inLocationCode;

    /** 实发数量。 */
    @Schema(description = "实发数量")
    private BigDecimal actualQty;

    /** 备注。 */
    @Schema(description = "备注")
    private String remark;

    /** 逻辑删除时间戳。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
