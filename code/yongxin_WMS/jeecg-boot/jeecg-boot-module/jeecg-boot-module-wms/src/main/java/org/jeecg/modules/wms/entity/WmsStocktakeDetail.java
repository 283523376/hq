package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.math.BigDecimal;

/**
 * @Description: 盘点明细。粒度对齐 wms_inventory 唯一键 (material, location, batch, container)。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "盘点明细")
@TableName("wms_stocktake_detail")
public class WmsStocktakeDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "盘点单ID")
    private String stocktakeId;

    @Schema(description = "库位ID")
    private String locationId;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "容器/料箱编码（立库为空）")
    private String containerCode;

    @Schema(description = "账面数量快照")
    private BigDecimal bookQty;

    @Schema(description = "实盘数量")
    private BigDecimal actualQty;

    @Schema(description = "差异数量（实盘-账面）")
    private BigDecimal diffQty;

    @Schema(description = "差异原因")
    private String diffReason;

    @Schema(description = "是否需重盘：1是 0否")
    private String recheckFlag;

    @Schema(description = "是否已调整：1是 0否")
    private String adjusted;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
