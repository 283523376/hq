package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * 库存（material + location + batch 聚合数量；不做逻辑删除，见 spec 口径 8）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存")
@TableName("wms_inventory")
public class WmsInventory extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 物料ID（wms_material.id） */
    @Schema(description = "物料ID")
    private String materialId;

    /** 库位ID（wms_location.id） */
    @Schema(description = "库位ID")
    private String locationId;

    /** 批次号 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 库存数量 */
    @Schema(description = "库存数量")
    private java.math.BigDecimal quantity;
}
