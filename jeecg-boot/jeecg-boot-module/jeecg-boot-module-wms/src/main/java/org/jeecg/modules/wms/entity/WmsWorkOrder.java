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
 * @Description: 生产工单（YX-WMS-RK-002）。
 *
 * <p>产品类型是半成品/成品分流的唯一依据：半成品走立库+堆垛机+周转盘 RFID，
 * 成品走敏捷仓+SCR+料箱二维码。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "生产工单")
@TableName("wms_work_order")
public class WmsWorkOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 工单号（唯一） */
    @Schema(description = "工单号")
    private String workOrderNo;

    /** 物料ID */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码（冗余） */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称（冗余） */
    @Schema(description = "物料名称")
    private String materialName;

    /** 产品类型：半成品/成品 */
    @Schema(description = "产品类型")
    private String productType;

    /** 计划数量 */
    @Schema(description = "计划数量")
    private BigDecimal planQty;

    /** 批次号 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 工单状态：待生产/生产中/已完成/已关闭 */
    @Schema(description = "工单状态")
    private String workOrderStatus;

    /** 来源：MES/手工 */
    @Schema(description = "来源")
    private String source;

    /** MES 侧单号 */
    @Schema(description = "MES侧单号")
    private String mesOrderNo;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
