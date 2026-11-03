package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: SB-001 同步流水（spec 2026-11-03 §2.5，仅展示、不参与比对）。
 *
 * <p>对账时间窗内的出入库记录：生产入库 / 出库 / 调拨 / 盘点；调拨按调出、调入拆两行，
 * 盘点按盘盈 IN、盘亏 OUT 拆行。采购入库已随范围收窄停用，不纳入。
 * 时间字段统一取单据 {@code update_time}（盘点取 {@code adjust_time}）。
 *
 * <p>已按单号落库，将来可加「按 erp_bill_no 逐单核对」（§8 扩展点）。
 * 无逻辑删除：随批次按保留期物理清理（180 天，见 §2.9.1）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "SB-001 同步流水（仅展示）")
@TableName("wms_sync_flow")
public class WmsSyncFlow extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 批次ID（wms_sync_run.id） */
    @Schema(description = "批次ID")
    private String runId;

    /** 类型：入库 / 出库 / 调拨 / 盘点 */
    @Schema(description = "类型：入库/出库/调拨/盘点")
    @Excel(name = "类型", width = 10)
    private String flowType;

    /** 方向：IN / OUT（调拨按调出/调入拆两行） */
    @Schema(description = "方向：IN/OUT")
    @Excel(name = "方向", width = 8)
    private String direction;

    /** 业务单号 */
    @Schema(description = "业务单号")
    @Excel(name = "业务单号", width = 24)
    private String billNo;

    /** 仓库编码 */
    @Schema(description = "仓库编码")
    @Excel(name = "仓库编码", width = 16)
    private String warehouseCode;

    /** 物料编码 */
    @Schema(description = "物料编码")
    @Excel(name = "物料编码", width = 20)
    private String materialCode;

    /** 物料名称 */
    @Schema(description = "物料名称")
    @Excel(name = "物料名称", width = 24)
    private String materialName;

    /** 批次号 */
    @Schema(description = "批次号")
    @Excel(name = "批次号", width = 18)
    private String batchNo;

    /** 数量 */
    @Schema(description = "数量")
    @Excel(name = "数量", width = 14)
    private BigDecimal qty;

    /** 业务时间（单据 update_time，盘点取 adjust_time） */
    @Schema(description = "业务时间")
    @Excel(name = "业务时间", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date bizTime;
}
