package org.jeecg.modules.wms.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: SB-001 同步流水原始行（spec 2026-11-03 §2.3「流水（仅展示）」）。
 *
 * <p>由 4 条聚合 SQL（生产入库/出库/调拨/盘点）产出，逐行落 {@code wms_sync_flow}，
 * <b>不参与比对</b>。仓库/物料列已按 ERP 匹配键口径取
 * COALESCE(erp_stock_code, warehouse_code) / COALESCE(kingdee_code, material_code)；
 * 物料名取 WMS 原值。调拨按调出/调入、盘点按盘盈/盘亏在 SQL 里拆成两行。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@Schema(description = "SB-001 同步流水原始行")
public class WmsSyncFlowRowVO {

    @Schema(description = "类型：生产入库/出库/调拨/盘点")
    private String flowType;

    @Schema(description = "方向：IN/OUT")
    private String direction;

    @Schema(description = "业务单号")
    private String billNo;

    @Schema(description = "仓库编码（ERP匹配键口径）")
    private String warehouseCode;

    @Schema(description = "物料编码（ERP匹配键口径）")
    private String materialCode;

    @Schema(description = "物料名称（WMS原值）")
    private String materialName;

    @Schema(description = "批次号（空批归一为空串）")
    private String batchNo;

    @Schema(description = "数量（均为正数，方向看 direction）")
    private BigDecimal qty;

    @Schema(description = "业务时间（单据 update_time，盘点取 adjust_time）")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date bizTime;
}
