package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 库存台账查询条件（KC-004）。
 *
 * <p>分页查询与导出共用：导出时整个对象序列化成 JSON 存进 {@code wms_export_task.query_params}，
 * 后台任务再反序列化回来重放同样的条件。**加字段时要考虑旧任务的反序列化兼容。**
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Data
@Schema(description = "库存台账查询条件")
public class WmsInventoryLedgerQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 物料关键字：同时模糊匹配物料编码与物料名称。 */
    @Schema(description = "物料关键字（编码或名称，模糊）")
    private String materialKeyword;

    /** 批次号，模糊匹配。 */
    @Schema(description = "批次号（模糊）")
    private String batchNo;

    /** 库位编码，模糊匹配。 */
    @Schema(description = "库位编码（模糊）")
    private String locationCode;

    /** 仓库ID，精确匹配。下拉包含停用仓。 */
    @Schema(description = "仓库ID")
    private String warehouseId;

    /** 只看有库存的行；默认 true，隐藏已扣到 0 的历史行。 */
    @Schema(description = "只看有库存（默认 true）")
    private Boolean onlyPositive = Boolean.TRUE;

    @Schema(description = "页码，默认 1")
    private Integer pageNo = 1;

    @Schema(description = "每页条数，默认 20，上限 200")
    private Integer pageSize = 20;

    /**
     * 库龄的计算基准日（导出专用）。
     *
     * <p>分页查询留空 → SQL 用 NOW()。导出必须传快照时间：几十万行要跑几分钟甚至跨午夜，
     * 用 NOW() 会让同一份文件里前后批次的库龄差一天。
     */
    @Schema(description = "库龄基准日（导出内部使用，留空取当前时间）")
    private java.util.Date asOfDate;
}
