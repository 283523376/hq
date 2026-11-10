package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * @Description: 采购入库单（收料通知单与采购入库单合一，走状态机，见 RK-001 设计文档 §5）。
 *
 * <p>状态机：待检验 →(合格) 待分配 →(分配) 待上架 →(上架) 已入库 →(自动审核) 已审核；
 * 分支终态：已拒收（收料即拒）、检验不合格（转 RK-005）、作废（录错单补救）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "采购入库单（兼收料通知单）")
@TableName("wms_inbound_order")
public class WmsInboundOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 入库单号（兼收料通知单号，SL 前缀） */
    @Schema(description = "入库单号")
    private String orderNo;

    /** 入库类型：采购入库 */
    @Schema(description = "入库类型：采购入库")
    private String inboundType;

    /** 采购订单号（wms_order.order_no，仅做存在性校验，允许多张收料单分批到货） */
    @Schema(description = "采购订单号")
    private String purchaseOrderNo;

    /** 供应商ID（仅外购；受托单为空） */
    @Schema(description = "供应商ID（仅外购）")
    private String supplierId;

    /** 供应商编码（自采购订单带出的冗余；仅外购） */
    @Schema(description = "供应商编码（仅外购）")
    private String supplierCode;

    /** 供应商名称（自采购订单带出的冗余；仅外购） */
    @Schema(description = "供应商名称（仅外购）")
    private String supplierName;

    /**
     * 受托方ID（wms_customer.id，仅受托）。
     *
     * <p>V2.4：受托方与金蝶保持一致——金蝶里受托方是「客户」，
     * 故受托单只写 consignor_* 三列，supplier_* 只给外购用，两者不混用。
     */
    @Schema(description = "受托方ID（wms_customer.id，仅受托）")
    private String consignorId;

    /** 受托方编码（冗余，回写金蝶 FCustId；仅受托） */
    @Schema(description = "受托方编码（仅受托）")
    private String consignorCode;

    /** 受托方名称（冗余，列表展示；仅受托） */
    @Schema(description = "受托方名称（仅受托）")
    private String consignorName;

    /** 单据状态：待检验/待分配/待上架/已入库/已审核/已拒收/检验不合格/作废 */
    @Schema(description = "单据状态")
    private String inboundStatus;

    /** 拒收/作废原因 */
    @Schema(description = "拒收/作废原因")
    private String rejectReason;

    /** 同步状态：待同步/同步中/同步成功/同步失败 */
    @Schema(description = "同步状态")
    private String syncStatus;

    /** 最近一次同步尝试中的重试次数 */
    @Schema(description = "重试次数")
    private Integer retryCount;

    /** 最后一次同步失败原因 */
    @Schema(description = "最后一次同步失败原因")
    private String lastSyncError;

    /** 最后一次同步尝试时间 */
    @Schema(description = "最后一次同步尝试时间")
    private java.util.Date lastSyncTime;

    /** 回写的金蝶单据号（ERP 为准，Save 成功后回存，用于幂等） */
    @Schema(description = "回写的金蝶单据号（ERP 为准）")
    private String erpBillNo;

    /** 最后一次回写金蝶成功时间 */
    @Schema(description = "最后一次回写金蝶成功时间")
    private java.util.Date erpSyncTime;

    /** 入库来源类型：PURCHASE 外购 / CONSIGNED 受托。 */
    @Schema(description = "入库来源类型：PURCHASE 外购 / CONSIGNED 受托")
    private String sourceType;

    /** 改制单ID（wms_conversion_order.id）：仅 V2.6 前历史改制单回填，V2.6 起不再生成。 */
    @Schema(description = "改制单ID")
    private String conversionOrderId;

    /** 审核人（自动审核为 system） */
    @Schema(description = "审核人")
    private String auditBy;

    /** 审核时间 */
    @Schema(description = "审核时间")
    private java.util.Date auditTime;

    /**
     * 删除时间戳（epoch 毫秒）：0=未删除。语义同 {@link WmsSupplier#deletedAt}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
