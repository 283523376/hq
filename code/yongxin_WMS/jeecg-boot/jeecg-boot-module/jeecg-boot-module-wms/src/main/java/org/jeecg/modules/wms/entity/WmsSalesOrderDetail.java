package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 销售订单分录快照（CK-002 方向反转）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "销售订单分录快照")
@TableName("wms_sales_order_detail")
public class WmsSalesOrderDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 所属快照ID。 */
    @Schema(description = "所属快照ID")
    private String salesOrderId;

    /** 行号（按金蝶分录顺序）。 */
    @Schema(description = "行号")
    private Integer lineNo;

    /** WMS 物料ID（按编码反查，可空=未建档）。 */
    @Schema(description = "WMS 物料ID")
    private String materialId;

    /** 物料编码（FMaterialId.FNumber）。 */
    @Excel(name = "物料编码", width = 24)
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称（FMaterialId.FName）。 */
    @Excel(name = "物料名称", width = 24)
    @Schema(description = "物料名称")
    private String materialName;

    /** 单位编码（FUnitID.FNumber，如 zhi）。 */
    @Schema(description = "单位编码")
    private String unit;

    /** 订单数量（FQty）。 */
    @Excel(name = "订单数量", width = 14)
    @Schema(description = "订单数量")
    private BigDecimal orderQty;

    /** 分录要货日期（FDeliveryDate）。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "要货日期")
    private Date deliveryDate;

    /** WMS 已出库数量（确认出库后汇总）。 */
    @Schema(description = "WMS 已出库数量")
    private BigDecimal shippedQty;

    /** 逻辑删除时间戳。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
