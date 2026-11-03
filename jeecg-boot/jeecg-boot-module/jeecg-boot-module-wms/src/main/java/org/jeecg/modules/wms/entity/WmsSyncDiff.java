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
 * @Description: SB-001 对账差异清单（spec 2026-11-03 §2.5）。
 *
 * <p>比对产生的差异行：多账 / 少账 / 仅WMS / 仅ERP，初始「待核对」，
 * 管理员标记「已核对/已忽略」（必填备注）或发起盘点（回填 stocktake_id/no）。
 * 盘点只按「WMS 账面 vs 实盘」调整；若实盘恰等于 WMS 账面，多账/少账差异仍在，
 * 需在金蝶侧人工处理——页面的提示不得制造「点了就能平」的错觉（§2.8）。
 *
 * <p>对账证据，保留 365 天（§2.9.1）；无逻辑删除，按保留期物理清理。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "SB-001 对账差异清单")
@TableName("wms_sync_diff")
public class WmsSyncDiff extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 批次ID（wms_sync_run.id） */
    @Schema(description = "批次ID")
    private String runId;

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

    /** WMS 侧数量（仅ERP 差异为 NULL） */
    @Schema(description = "WMS 侧数量")
    @Excel(name = "WMS数量", width = 14)
    private BigDecimal wmsQty;

    /** 金蝶侧数量（仅WMS 差异为 NULL） */
    @Schema(description = "金蝶数量")
    @Excel(name = "金蝶数量", width = 14)
    private BigDecimal erpQty;

    /** 差异 = WMS - ERP */
    @Schema(description = "差异数量（WMS-ERP）")
    @Excel(name = "差异数量", width = 14)
    private BigDecimal diffQty;

    /** 差异类型：多账 / 少账 / 仅WMS / 仅ERP */
    @Schema(description = "差异类型：多账/少账/仅WMS/仅ERP")
    @Excel(name = "差异类型", width = 12)
    private String diffType;

    /** 状态：待核对 / 已核对 / 已忽略 */
    @Schema(description = "状态：待核对/已核对/已忽略")
    @Excel(name = "状态", width = 10)
    private String status;

    /** 处理人 */
    @Schema(description = "处理人")
    @Excel(name = "处理人", width = 14)
    private String handleBy;

    /** 处理时间 */
    @Schema(description = "处理时间")
    @Excel(name = "处理时间", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date handleTime;

    /** 处理备注（标记已核对/已忽略必填） */
    @Schema(description = "处理备注")
    @Excel(name = "处理备注", width = 30)
    private String handleRemark;

    /** 发起盘点回填：盘点单ID（wms_stocktake_order.id） */
    @Schema(description = "盘点单ID")
    @Excel(name = "盘点单ID", width = 22)
    private String stocktakeId;

    /** 发起盘点回填：盘点单号 */
    @Schema(description = "盘点单号")
    @Excel(name = "盘点单号", width = 22)
    private String stocktakeNo;
}
