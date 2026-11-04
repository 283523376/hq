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
     * 库存所有权：OWNED 自有 / CONSIGNED 受托。
     *
     * <p>受托库存不得用于自有生产领料、销售出库，只能退货或报废处置；
     * 出库分配默认只取 OWNED（见 spec 2026-11-04 §3.1）。存量数据一律 OWNED。
     */
    @Schema(description = "库存所有权：OWNED 自有 / CONSIGNED 受托")
    private String ownershipType;

    /**
     * 受托方ID（**wms_customer.id**，V2.4 起为客户档案）：ownershipType=CONSIGNED 时必填；
     * 自有为空串（列类型保持 varchar(36) 不变）。
     */
    @Schema(description = "受托方ID（wms_customer.id）")
    private String consignorId;

    /**
     * 批次首次入库时间（超期预警起算点）。
     *
     * <p>不是行创建时间：调拨到新库位、盘盈新建行时都**继承/沿用**原批次首次入库时间，
     * 否则货龄会被重置。见 spec 2026-10-28 决策 D1/D3/D4。
     */
    @Schema(description = "批次首次入库时间")
    private java.util.Date firstInboundTime;
}
