package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 盘点单（spec 2026-10-26 §5.3）。状态只能经 WmsStocktakeStatusResolver 白名单转移。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "盘点单")
@TableName("wms_stocktake_order")
public class WmsStocktakeOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "盘点单号")
    private String stocktakeNo;

    @Schema(description = "盘点类型：定期/周期")
    private String stocktakeType;

    @Schema(description = "盘点范围说明")
    private String scopeDesc;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "整单复核阈值（百分比）")
    private BigDecimal diffReviewThreshold;

    @Schema(description = "账面合计数量")
    private BigDecimal bookTotal;

    @Schema(description = "盘盈合计")
    private BigDecimal gainTotal;

    @Schema(description = "盘亏合计")
    private BigDecimal lossTotal;

    @Schema(description = "盘点发起人")
    private String initiator;

    @Schema(description = "复核人")
    private String reviewer;

    @Schema(description = "复核时间")
    private Date reviewTime;

    @Schema(description = "调整时间")
    private Date adjustTime;

    @Schema(description = "归档时间")
    private Date archiveTime;

    @Schema(description = "ERP 回写状态")
    private String erpSyncStatus;

    @Schema(description = "ERP 回写单据号")
    private String erpBillNo;

    @Schema(description = "备注")
    private String remark;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
