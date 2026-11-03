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
 * @Description: SB-001 库存快照 / 待推送包（spec 2026-11-03 §2.5）。
 *
 * <p>WMS 侧按 仓库+物料+批次 聚合（库位/容器聚合掉），汇总阶段先落 {@code wmsQty}，
 * 比对后回填 {@code erpQty/diffQty/matchStatus}。快照表本身即「待推送包」，
 * 可导出 xlsx/json，将来接 BOS 接收端后可回放。
 *
 * <p>{@code lockedQty}（出库锁定）只附带展示，不计入对比——金蝶账上没有「WMS 锁定」概念。
 * 仓库编码与金蝶 1:1（COALESCE(erp_stock_code, warehouse_code)，本期不做映射表）。
 *
 * <p>无逻辑删除：快照随批次按保留期物理清理（180 天，见 §2.9.1）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "SB-001 库存快照（待推送包）")
@TableName("wms_sync_snapshot")
public class WmsSyncSnapshot extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 批次ID（wms_sync_run.id） */
    @Schema(description = "批次ID")
    private String runId;

    /** 仓库编码（金蝶 FStockId.FNumber，WMS 与金蝶 1:1） */
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

    /** 单位 */
    @Schema(description = "单位")
    @Excel(name = "单位", width = 8)
    private String unit;

    /** 批次号（金蝶 FLot.FNumber 为空时归一化为空串） */
    @Schema(description = "批次号")
    @Excel(name = "批次号", width = 18)
    private String batchNo;

    /** WMS 侧数量（仓库+物料+批次聚合） */
    @Schema(description = "WMS 侧数量")
    @Excel(name = "WMS数量", width = 14)
    private BigDecimal wmsQty;

    /** 金蝶即时库存数量（FBaseQty；仅WMS 记 0，未比对为 NULL） */
    @Schema(description = "金蝶数量")
    @Excel(name = "金蝶数量", width = 14)
    private BigDecimal erpQty;

    /** 差异 = WMS - ERP（多账为正、少账为负） */
    @Schema(description = "差异数量（WMS-ERP）")
    @Excel(name = "差异数量", width = 14)
    private BigDecimal diffQty;

    /** 匹配状态：一致 / 数量差异 / 仅WMS / 仅ERP */
    @Schema(description = "匹配状态：一致/数量差异/仅WMS/仅ERP")
    @Excel(name = "匹配状态", width = 12)
    private String matchStatus;

    /** 出库锁定数量（仅附带展示，不计入对比） */
    @Schema(description = "出库锁定数量")
    @Excel(name = "锁定数量", width = 14)
    private BigDecimal lockedQty;

    /** 快照时点 */
    @Schema(description = "快照时点")
    @Excel(name = "快照时点", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date snapshotTime;
}
