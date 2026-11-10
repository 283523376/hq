package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * 改制单（大改小）：记录投入原材料与产出半成品，关联 ERP 生产订单。
 *
 * <p>原材料直送车间、不入 WMS 库存；完成时生成生产入库单，产出走 RK-002 通道。
 * 本表不做逻辑删除（项目约定：无 deletedAt 的表物理删除，状态机负责终态）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "改制单")
@TableName("wms_conversion_order")
public class WmsConversionOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 改制单号（GZ 前缀 + 雪花 ID）。 */
    @Excel(name = "改制单号", width = 24)
    @Schema(description = "改制单号")
    private String conversionNo;

    /** 关联生产订单号（wms_work_order.order_no，必须已审核并同步至 WMS）。 */
    @Excel(name = "生产订单", width = 20)
    @Schema(description = "生产订单号")
    private String productionOrderNo;

    /** 投入物料编码。 */
    @Excel(name = "投入物料", width = 20)
    @Schema(description = "投入物料编码")
    private String inputMaterialCode;

    /** 投入批次号。 */
    @Schema(description = "投入批次号")
    private String inputBatchNo;

    /** 投入数量。 */
    @Schema(description = "投入数量")
    private java.math.BigDecimal inputQty;

    /** 产出物料编码。 */
    @Excel(name = "产出物料", width = 20)
    @Schema(description = "产出物料编码")
    private String outputMaterialCode;

    /** 产出批次号。 */
    @Schema(description = "产出批次号")
    private String outputBatchNo;

    /** 产出计划数量。 */
    @Schema(description = "产出数量")
    private java.math.BigDecimal outputQty;

    /**
     * 单据状态四态：DRAFT 草稿 / RUNNING 执行中 / DONE 已完成 / CANCELLED 已取消。
     *
     * <p>取值见 {@link org.jeecg.modules.wms.constant.WmsConstants#CONVERSION_STATUS_DRAFT} 等四个常量，禁止写字面量；
     * 状态机与合法转移由 {@code WmsConversionServiceImpl} 负责。
     */
    @Excel(name = "状态", width = 12)
    @Schema(description = "单据状态：DRAFT 草稿 / RUNNING 执行中 / DONE 已完成 / CANCELLED 已取消")
    private String status;

    /** 操作人。 */
    @Schema(description = "操作人")
    private String operator;

    /** 回写的金蝶单据号（ERP 是单号唯一来源）。 */
    @Schema(description = "金蝶单号")
    private String erpBillNo;
}
