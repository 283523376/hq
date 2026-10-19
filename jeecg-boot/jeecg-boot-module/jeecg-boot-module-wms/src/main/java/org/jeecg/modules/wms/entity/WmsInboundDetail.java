package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * 采购入库明细。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "采购入库明细")
@TableName("wms_inbound_detail")
public class WmsInboundDetail extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 入库单ID（wms_inbound_order.id） */
    @Schema(description = "入库单ID")
    private String inboundId;

    /** 物料ID（wms_material.id） */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码（冗余） */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称（冗余） */
    @Schema(description = "物料名称")
    private String materialName;

    /** 应收数量 */
    @Schema(description = "应收数量")
    private java.math.BigDecimal quantity;

    /** 实收数量（上架确认时录入；0=短装跳过） */
    @Schema(description = "实收数量")
    private java.math.BigDecimal actualQuantity;

    /** 库位ID（分配后写入，wms_location.id） */
    @Schema(description = "库位ID")
    private String locationId;

    /** 批次号 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 删除时间戳（epoch 毫秒）：0=未删除。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
