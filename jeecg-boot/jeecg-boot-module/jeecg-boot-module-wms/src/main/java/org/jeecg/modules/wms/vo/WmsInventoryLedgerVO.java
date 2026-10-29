package org.jeecg.modules.wms.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 库存台账明细行（KC-004）。
 *
 * <p>既是分页查询的出参，也是导出的行对象——字段上的 {@code @Excel} 同时决定 Excel 列名与列序，
 * 所以新增字段时别把 {@code orderNum} 撞车。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存台账明细行")
public class WmsInventoryLedgerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 库存行主键（前端行键用，不导出）。 */
    @Schema(description = "库存行ID")
    private String id;

    /** 游标推进用：导出分批按唯一键的四列排序，material_id 是游标第一列。 */
    @Schema(description = "物料ID（游标用）")
    private String materialId;

    /** 游标推进用：唯一键第二列。 */
    @Schema(description = "库位ID（游标用）")
    private String locationId;

    @Excel(name = "物料编码", width = 20, orderNum = "1")
    @Schema(description = "物料编码")
    private String materialCode;

    @Excel(name = "物料名称", width = 25, orderNum = "2")
    @Schema(description = "物料名称")
    private String materialName;

    @Excel(name = "规格", width = 20, orderNum = "3")
    @Schema(description = "规格")
    private String spec;

    @Excel(name = "单位", width = 8, orderNum = "4")
    @Schema(description = "单位")
    private String unit;

    @Excel(name = "批次号", width = 20, orderNum = "5")
    @Schema(description = "批次号")
    private String batchNo;

    @Excel(name = "仓库编码", width = 15, orderNum = "6")
    @Schema(description = "仓库编码")
    private String warehouseCode;

    @Excel(name = "仓库名称", width = 20, orderNum = "7")
    @Schema(description = "仓库名称")
    private String warehouseName;

    /** 仓库状态：停用仓的库存照样进台账，只在这里标出来（spec 2026-10-29 口径）。 */
    @Excel(name = "仓库状态", width = 10, orderNum = "8", replace = {"启用_1", "停用_0"})
    @Schema(description = "仓库状态：1启用 0停用")
    private String warehouseStatus;

    @Excel(name = "库位编码", width = 20, orderNum = "9")
    @Schema(description = "库位编码")
    private String locationCode;

    @Excel(name = "料箱/托盘", width = 18, orderNum = "10")
    @Schema(description = "料箱/托盘编码")
    private String containerCode;

    @Excel(name = "库存数量", width = 12, orderNum = "11")
    @Schema(description = "库存数量")
    private BigDecimal quantity;

    @Excel(name = "锁定数量", width = 12, orderNum = "12")
    @Schema(description = "出库分配锁定数量")
    private BigDecimal lockedQty;

    @Excel(name = "可用量", width = 12, orderNum = "13")
    @Schema(description = "可用量 = 库存数量 - 锁定数量")
    private BigDecimal availableQty;

    @Excel(name = "首次入库时间", width = 20, orderNum = "14", format = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "批次首次入库时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date firstInboundTime;

    @Excel(name = "库龄(天)", width = 10, orderNum = "15")
    @Schema(description = "库龄天数（自首次入库起算）")
    private Integer stockAgeDays;
}
