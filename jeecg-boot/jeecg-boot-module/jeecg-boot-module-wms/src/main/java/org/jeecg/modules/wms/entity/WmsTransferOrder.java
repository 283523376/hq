package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * @Description: 库存调拨单（CK-004）。
 *
 * <p>双时钟设计：{@code execStatus}（实物）+ {@code syncStatus}（ERP）+ {@code erpDocumentStatus}（金蝶原值留痕），
 * 单据总状态 {@code status} 只由 {@code WmsTransferStatusResolver.deriveStatus()} 派生，禁止直接赋值。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存调拨单")
@TableName("wms_transfer_order")
public class WmsTransferOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 调拨单号（DB 前缀 + 雪花 ID），同时作为金蝶 FBillNo。 */
    @Excel(name = "调拨单号", width = 24)
    @Schema(description = "调拨单号，同时作为金蝶 FBillNo")
    private String transferNo;

    /** 来源：WMS/ERP。 */
    @Schema(description = "来源：WMS/ERP")
    private String source;

    /** 金蝶单号（Save 成功后回填）。 */
    @Schema(description = "金蝶单号")
    private String erpBillNo;

    /** 金蝶原始单据状态 A/B/C/D（只读留痕）。 */
    @Schema(description = "金蝶原始状态 A/B/C/D（只读留痕）")
    private String erpDocumentStatus;

    /** 库存组织编码，默认 101。 */
    @Schema(description = "库存组织编码")
    private String erpOrgCode;

    /** 调出库区ID。 */
    @Schema(description = "调出库区ID")
    private String outWarehouseId;

    /** 调出库区编码。 */
    @Excel(name = "调出库区", width = 16)
    @Schema(description = "调出库区编码")
    private String outWarehouseCode;

    /** 调出区域（米塔 zoneCode，可空）。 */
    @Schema(description = "调出区域编码")
    private String outZoneCode;

    /** 调入库区ID。 */
    @Schema(description = "调入库区ID")
    private String inWarehouseId;

    /** 调入库区编码。 */
    @Excel(name = "调入库区", width = 16)
    @Schema(description = "调入库区编码")
    private String inWarehouseCode;

    /** 调入区域（同仓区域搬运时必填）。 */
    @Schema(description = "调入区域编码")
    private String inZoneCode;

    /** 调拨模式：跨仓/同仓区域。 */
    @Excel(name = "调拨模式", width = 12)
    @Schema(description = "调拨模式：跨仓/同仓区域")
    private String transferMode;

    /** 实物时钟状态：待执行/执行中/在途/待入库/实物完成/异常。 */
    @Schema(description = "实物时钟状态")
    private String execStatus;

    /** ERP 时钟状态：待同步/同步中/已提交待审核/已审核/ERP驳回/同步失败/无需同步。 */
    @Schema(description = "ERP 时钟状态")
    private String syncStatus;

    /** 单据总状态（派生态）：待执行/执行中/待ERP审核/已完成/异常/已关闭。 */
    @Excel(name = "状态", width = 12)
    @Schema(description = "单据总状态（由 deriveStatus 派生）")
    private String status;

    /** 跨仓在途暂存库位ID。 */
    @Schema(description = "跨仓在途暂存库位ID")
    private String transitLocationId;

    /** 审批超时告警已发时间（幂等用）。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "审批超时告警已发时间")
    private Date approvalAlertTime;

    /** ERP 驳回原因。 */
    @Schema(description = "ERP 驳回原因")
    private String rejectReason;

    /** ERP 驳回时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "ERP 驳回时间")
    private Date rejectTime;

    /** 驳回处置：待处置/已冲销/已人工关闭。 */
    @Schema(description = "驳回处置状态")
    private String rejectHandleStatus;

    /** 冲销（反向）调拨单号。 */
    @Schema(description = "反向调拨单号")
    private String reverseTransferNo;

    /** 同步退避重试次数。 */
    @Schema(description = "同步重试次数")
    private Integer retryCount;

    /** 连续同步失败次数（≥3 告警）。 */
    @Schema(description = "连续同步失败次数")
    private Integer syncFailCount;

    /** 最近同步失败原因。 */
    @Schema(description = "最近同步失败原因")
    private String lastSyncError;

    /** 最近同步时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最近同步时间")
    private Date lastSyncTime;

    /** 操作人。 */
    @Schema(description = "操作人")
    private String operator;

    /** 提交金蝶成功（进入 B）时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "提交金蝶时间")
    private Date submitTime;

    /** 实物完成时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "实物完成时间")
    private Date execFinishTime;

    /** 金蝶审批通过（C）时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "金蝶审批通过时间")
    private Date erpApproveTime;

    /** 单据完成时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "单据完成时间")
    private Date completeTime;

    /** 关单原因。 */
    @Schema(description = "关单原因")
    private String closeReason;

    /** 关单人。 */
    @Schema(description = "关单人")
    private String closeBy;

    /** 关单时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "关单时间")
    private Date closeTime;

    /** 备注。 */
    @Schema(description = "备注")
    private String remark;

    /** 逻辑删除时间戳。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
