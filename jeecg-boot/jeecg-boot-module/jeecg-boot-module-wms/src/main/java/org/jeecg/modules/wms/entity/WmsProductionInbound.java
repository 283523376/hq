package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 生产入库记录（YX-WMS-RK-002）。
 *
 * <p>一次提交 = 一个载具（成品料箱 / 半成品周转盘）= 一个标识码。
 * 状态机：待上架 →(设备回执 RUNNING) 执行中 →(FINISHED) 已入库；
 * 下发失败/设备故障 → 异常待人工 →(转人工完成) 已入库 或 (关单) 已关闭。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "生产入库记录")
@TableName("wms_production_inbound")
public class WmsProductionInbound extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 入库单号（SC 前缀） */
    @Schema(description = "入库单号")
    private String inboundNo;

    /** 工单号 */
    @Schema(description = "工单号")
    private String workOrderNo;

    /** 产品类型：半成品/成品 */
    @Schema(description = "产品类型")
    private String productType;

    /** 物料ID */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码（冗余） */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称（冗余） */
    @Schema(description = "物料名称")
    private String materialName;

    /** 批次号 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 本次入库总量 = goodQty + badQty */
    @Schema(description = "本次入库总量")
    private BigDecimal quantity;

    /** 良品数量 */
    @Schema(description = "良品数量")
    private BigDecimal goodQty;

    /** 不良品数量 */
    @Schema(description = "不良品数量")
    private BigDecimal badQty;

    /** 状态：待上架/执行中/已入库/异常待人工/已关闭 */
    @Schema(description = "状态")
    private String status;

    /** 载具码（成品料箱码 / 半成品周转盘 RFID 号） */
    @Schema(description = "载具码")
    private String carrierCode;

    /** 标识类型：二维码/RFID */
    @Schema(description = "标识类型")
    private String identType;

    /** 良品目标库位 */
    @Schema(description = "良品目标库位")
    private String locationId;

    /** 不良品写入的废品仓库位 */
    @Schema(description = "废品仓库位")
    private String scrapLocationId;

    /** RMS 回执的料箱库位编码 */
    @Schema(description = "RMS回执库位编码")
    private String stackCode;

    /** 下发任务号（提交时生成并固定） */
    @Schema(description = "下发任务号")
    private String wcsTaskNo;

    /** 任务状态：未下发/已受理/执行中/已完成/失败/已关闭 */
    @Schema(description = "任务状态")
    private String taskStatus;

    /** 下发/回执失败原因 */
    @Schema(description = "任务失败原因")
    private String taskError;

    /** 不良原因 */
    @Schema(description = "不良原因")
    private String defectRemark;

    /** 关单原因 */
    @Schema(description = "关单原因")
    private String closeReason;

    /** 关单人 */
    @Schema(description = "关单人")
    private String closeBy;

    /** 关单时间 */
    @Schema(description = "关单时间")
    private Date closeTime;

    /** 同步状态：待同步/同步中/同步成功/同步失败/无需同步 */
    @Schema(description = "同步状态")
    private String syncStatus;

    /** 金蝶单号（纯不良品=其他入库单 QTRK…；良品入库=生产入库单 SCRK…）。ERP 是单号唯一来源，回存展示与幂等用 */
    @Schema(description = "金蝶单号")
    private String erpBillNo;

    /**
     * 不良品回写的金蝶单号（其他入库单 QTRK…）。
     *
     * <p>混合单（良品与不良品都有）专用：良品走 {@code erpBillNo}（SCRK…），
     * 不良品走本列。纯不良品仍是 {@code erpBillNo}（历史兼容）。
     */
    @Schema(description = "不良品回写的金蝶单号（混合单专用）")
    private String defectErpBillNo;

    /**
     * 工序汇报分录幂等键：`金蝶单号#工序序列#工序号`。
     *
     * <p>一张工序汇报单有多个分录行（每道工序一行），只用单号去重会把它们并成一条。
     */
    @Schema(description = "工序汇报分录幂等键")
    private String erpEntryKey;

    /** 最近一次同步尝试中的重试次数 */
    @Schema(description = "重试次数")
    private Integer retryCount;

    /** 最后一次同步失败原因 */
    @Schema(description = "最后同步失败原因")
    private String lastSyncError;

    /** 最后一次同步尝试时间 */
    @Schema(description = "最后同步时间")
    private Date lastSyncTime;

    /** 后台同步累计失败次数（用于超限告警） */
    @Schema(description = "同步累计失败次数")
    private Integer syncFailCount;

    /** 转人工完成说明 */
    @Schema(description = "转人工完成说明")
    private String manualRemark;

    /** 备注（改制单来源等溯源信息，如「改制单:GZ…」，V3.9.5_48 起有列） */
    @Schema(description = "备注")
    private String remark;

    /** 改制单 ID（改制产出关联，wms_conversion_order.id；V3.9.5_59，技术债 §5-23） */
    @Schema(description = "改制单ID")
    private String conversionOrderId;

    /** 来源物料编码（改制投入物料；V3.9.5_59，技术债 §5-23） */
    @Schema(description = "来源物料编码")
    private String sourceMaterialCode;

    /** 目标库位编码（非持久化，列表/详情展示用） */
    @TableField(exist = false)
    @Schema(description = "目标库位编码")
    private String locationCode;

    /** 操作员 */
    @Schema(description = "操作员")
    private String operator;

    /** 提交时间 */
    @Schema(description = "提交时间")
    private Date submitTime;

    /** 入库完成时间 */
    @Schema(description = "入库完成时间")
    private Date completeTime;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
