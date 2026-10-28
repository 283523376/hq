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

    /** 料箱/托盘编码（敏捷仓按箱记库存；立体库为空串）。 */
    @Schema(description = "料箱/托盘编码")
    private String containerCode;

    /** 库存数量 */
    @Schema(description = "库存数量")
    private java.math.BigDecimal quantity;

    /** 出库分配锁定数量：可用量 = quantity - lockedQty。 */
    @Schema(description = "出库分配锁定数量")
    private java.math.BigDecimal lockedQty;

    /**
     * 批次首次入库时间（超期预警起算点）。
     *
     * <p>不是行创建时间：调拨到新库位、盘盈新建行时都**继承/沿用**原批次首次入库时间，
     * 否则货龄会被重置。见 spec 2026-10-28 决策 D1/D3/D4。
     */
    @Schema(description = "批次首次入库时间")
    private java.util.Date firstInboundTime;
}
