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

import java.util.Date;

/**
 * @Description: 销售订单快照（ERP → WMS，CK-002 方向反转）。
 *
 * <p>WMS 不再创建/回写 `SAL_SaleOrder`，只按单号拉取「已审核(C)」订单；出库单由本快照生成。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "销售订单快照")
@TableName("wms_sales_order")
public class WmsSalesOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 金蝶销售订单号（SAL_SaleOrder.FBillNo）。 */
    @Excel(name = "销售订单号", width = 24)
    @Schema(description = "销售订单号")
    private String salesOrderNo;

    /** 客户编码（FCustId.FNumber）。 */
    @Schema(description = "客户编码")
    private String customerCode;

    /** 客户名称（FCustId.FName）。 */
    @Excel(name = "客户", width = 24)
    @Schema(description = "客户名称")
    private String customerName;

    /** 销售组织编码（FSaleOrgId.FNumber）。 */
    @Schema(description = "销售组织编码")
    private String saleOrgNumber;

    /** 订单日期（FDate）。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "订单日期")
    private Date orderDate;

    /** 金蝶单据状态 A/B/C/D/Z。 */
    @Schema(description = "金蝶单据状态 A/B/C/D/Z")
    private String erpDocumentStatus;

    /** 最早要货日期（分录 FDeliveryDate 最小值）。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "最早要货日期")
    private Date deliveryDate;

    /** 拉取状态：待拉取/成功/失败。 */
    @Schema(description = "拉取状态")
    private String fetchStatus;

    /** 最近拉取失败原因。 */
    @Schema(description = "最近拉取失败原因")
    private String fetchError;

    /** 连续拉取失败次数（≥3 告警）。 */
    @Schema(description = "连续拉取失败次数")
    private Integer fetchFailCount;

    /** 最近拉取时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最近拉取时间")
    private Date fetchTime;

    /** 备注（FNote）。 */
    @Schema(description = "备注")
    private String remark;

    /** 逻辑删除时间戳。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
