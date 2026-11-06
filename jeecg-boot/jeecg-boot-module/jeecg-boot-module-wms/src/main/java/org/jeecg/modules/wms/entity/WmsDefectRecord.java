package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * 不良品记录（RK-005）：生产不良与受托原材料不良两类来源的统一台账。
 *
 * <p>受托不良扣减受托库存后按处置方式分流：入废品仓（仍为受托所有权）或退货。
 * 本表不做逻辑删除（状态机负责终态）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "不良品记录")
@TableName("wms_defect_record")
public class WmsDefectRecord extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 来源类型：PRODUCTION 生产 / CONSIGNED 受托。 */
    @Excel(name = "来源类型", width = 12)
    @Schema(description = "来源类型：PRODUCTION 生产 / CONSIGNED 受托")
    private String sourceType;

    /** 来源单号（生产入库单号 / 受托到货单号）。 */
    @Excel(name = "来源单号", width = 24)
    @Schema(description = "来源单号")
    private String sourceBillNo;

    /** 物料编码。 */
    @Excel(name = "物料编码", width = 20)
    @Schema(description = "物料编码")
    private String materialCode;

    /** 批次号。 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 不良数量。 */
    @Excel(name = "数量", width = 12)
    @Schema(description = "不良数量")
    private java.math.BigDecimal qty;

    /** 不良原因。 */
    @Excel(name = "原因", width = 24)
    @Schema(description = "不良原因")
    private String reason;

    /** 处置方式：SCRAP 入废品仓 / RETURN 退货。 */
    @Excel(name = "处置方式", width = 12)
    @Schema(description = "处置方式：SCRAP 入废品仓 / RETURN 退货")
    private String disposalType;

    /** 受托加工材料退料单号（处置方式=RETURN 时回填）。 */
    @Schema(description = "退货单号")
    private String returnBillNo;

    /** 状态：待处理 / 已处置 / 已退货。 */
    @Excel(name = "状态", width = 12)
    @Schema(description = "状态：待处理 / 已处置 / 已退货")
    private String status;
}
