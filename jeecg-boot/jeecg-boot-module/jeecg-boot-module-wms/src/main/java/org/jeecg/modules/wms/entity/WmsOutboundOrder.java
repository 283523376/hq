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
 * @Description: 出库单（CK-001 生产领料 / CK-002 销售出库）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "出库单")
@TableName("wms_outbound_order")
public class WmsOutboundOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 出库单号（LL/S 前缀 + 雪花 ID）。 */
    @Excel(name = "出库单号", width = 24)
    @Schema(description = "出库单号")
    private String outboundNo;

    /** 出库类型：生产领料/销售出库。 */
    @Excel(name = "出库类型", width = 12)
    @Schema(description = "出库类型：生产领料/销售出库")
    private String outboundType;

    /** 生产工单号（CK-001）。 */
    @Excel(name = "生产工单", width = 20)
    @Schema(description = "生产工单号")
    private String workOrderNo;

    /** 销售订单号（CK-002）。 */
    @Excel(name = "销售订单", width = 20)
    @Schema(description = "销售订单号")
    private String salesOrderNo;

    /** 客户编码（CK-002）。 */
    @Schema(description = "客户编码")
    private String customerCode;

    /** 客户名称（CK-002）。 */
    @Excel(name = "客户", width = 24)
    @Schema(description = "客户名称")
    private String customerName;

    /** 要货日期（CK-002，回写金蝶销售订单 FDeliveryDate）。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "要货日期")
    private Date deliveryDate;

    /** 需求来源：金蝶用料清单/手工。 */
    @Schema(description = "需求来源：金蝶用料清单/手工")
    private String requirementSource;

    /** 金蝶委外订单号（外协出库草稿与 ERP 的关联键）。 */
    @Schema(description = "金蝶委外订单号")
    private String erpSubOrderNo;

    /** 回写生成的金蝶单据号（样品出库等，E5 就位后使用）。 */
    @Schema(description = "回写生成的金蝶单据号")
    private String erpMisBillNo;

    /** 单据状态。 */
    @Excel(name = "状态", width = 12)
    @Schema(description = "单据状态：待分配/已分配/执行中/待复核/复核异常/异常待人工/已完成/已关闭")
    private String status;

    /** 出库单同步状态（PRD_PickMtrl / SAL_OUTSTOCK）。 */
    @Excel(name = "同步状态", width = 12)
    @Schema(description = "出库单同步状态")
    private String syncStatus;

    /** 最近一次同步尝试中实际发生的重试次数。 */
    @Excel(name = "重试次数", width = 10)
    @Schema(description = "最近一次同步尝试中实际发生的重试次数")
    private Integer retryCount;

    /** 最后一次同步失败原因。 */
    @Schema(description = "最后一次同步失败原因")
    private String lastSyncError;

    /** 最后一次同步尝试时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后一次同步尝试时间")
    private Date lastSyncTime;

    /** 后台同步累计失败次数（用于超限告警）。 */
    @Schema(description = "后台同步累计失败次数")
    private Integer syncFailCount;

    /** 金蝶单号（领料 SOUT…/销售出库 XSCK…）。ERP 是单号唯一来源，回存展示与幂等用 */
    @Schema(description = "金蝶单号")
    private String erpBillNo;

    /** ERP 来源单号（销售出库=金蝶发货通知单 FHTZD…）；出库单反向生成时写入，用于幂等与分批扣减 */
    @Schema(description = "ERP来源单号")
    private String sourceBillNo;

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

    /** 创建人。 */
    @Schema(description = "创建人")
    private String operator;

    /** 创建时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date submitTime;

    /** 完成时间。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "完成时间")
    private Date completeTime;

    /** 领料人ID（CK-001，取当前登录账号）。 */
    @Schema(description = "领料人ID")
    private String pickerId;

    /** 领料人姓名。 */
    @Excel(name = "领料人", width = 16)
    @Schema(description = "领料人姓名")
    private String pickerName;

    /** 领料设备ID（wms_device.id）。 */
    @Schema(description = "领料设备ID")
    private String deviceId;

    /** 取消原因（领料物料不符/库存不足等）。 */
    @Schema(description = "取消原因")
    private String cancelReason;

    /** 备注。 */
    @Excel(name = "备注", width = 20)
    @Schema(description = "备注")
    private String remark;

    /**
     * 删除时间戳（epoch 毫秒）：0 = 未删除。
     *
     * <p>唯一索引 {@code uk_wms_outbound_no(outbound_no, deleted_at)} 必须纳入删除标记，
     * 否则删除后的单号会永久占用，无法重建。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
