package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.math.BigDecimal;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库位")
@TableName("wms_location")
public class WmsLocation extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库位编码，如 LB-R01-C06-L24。全局唯一。 */
    @Excel(name = "库位编码", width = 24)
    @Schema(description = "库位编码")
    private String locationCode;

    /** 所属库区ID。 */
    @Schema(description = "所属库区ID")
    private String warehouseId;

    /** 排。 */
    @Excel(name = "排", width = 8)
    @Schema(description = "排")
    private Integer rowNo;

    /** 列。 */
    @Excel(name = "列", width = 8)
    @Schema(description = "列")
    private Integer colNo;

    /** 层。 */
    @Excel(name = "层", width = 8)
    @Schema(description = "层")
    private Integer layerNo;

    /** 承重（kg）。 */
    @Excel(name = "承重", width = 12)
    @Schema(description = "承重")
    private BigDecimal maxWeight;

    /** 状态：空闲 / 占用 / 故障。{@code 占用} 视为库位内有物料，禁止修改属性。 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：空闲/占用/故障")
    private String status;

    /** 所属库区名称（非持久化，列表展示用，交给 QueryGenerator 前必须先置空）。 */
    @TableField(exist = false)
    @Schema(description = "所属库区名称")
    private String warehouseName;

    /**
     * 删除状态。**始终为 0** —— 本表刻意<b>不用逻辑删除</b>。
     *
     * <p>原因最要命的一条：{@code uk_wms_location_grid} 只建在
     * {@code (warehouse_id, row_no, col_no, layer_no)} 上、不含 {@code del_flag}。
     * 若用逻辑删除，删掉几个库位后重跑生成，那几行会被 {@code INSERT IGNORE}
     * <b>静默跳过</b>（计入 skipped）却**不会被恢复**，列表里也看不见 ——
     * 直接破坏「重跑同范围可安全续做」这条承诺，且不留任何痕迹。
     */
    @Schema(description = "删除状态，恒为 0（本表不使用逻辑删除）")
    private Integer delFlag;
}