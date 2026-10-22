package org.jeecg.modules.wms.entity;

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
 * @Description: 生产工单（YX-WMS-RK-002）。
 *
 * <p>产品类型是半成品/成品分流的唯一依据：半成品走立库+堆垛机+周转盘 RFID，
 * 成品走敏捷仓+SCR+料箱二维码。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "生产工单")
@TableName("wms_work_order")
public class WmsWorkOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 工单号（唯一） */
    @Schema(description = "工单号")
    private String workOrderNo;

    /** 物料ID */
    @Schema(description = "物料ID")
    private String materialId;

    /** 物料编码（冗余） */
    @Schema(description = "物料编码")
    private String materialCode;

    /** 物料名称（冗余） */
    @Schema(description = "物料名称")
    private String materialName;

    /** 产品类型：半成品/成品 */
    @Schema(description = "产品类型")
    private String productType;

    /** 计划数量 */
    @Schema(description = "计划数量")
    private BigDecimal planQty;

    /** 批次号 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 工单状态：待生产/已下达/生产中/已完成/已关闭 */
    @Schema(description = "工单状态")
    private String workOrderStatus;

    /** 来源：ERP/MES/手工（手工仅历史数据；新代码必须显式写来源） */
    @Schema(description = "来源")
    private String source;

    /** 金蝶 FDocumentStatus 原始值（A/B/C/D…），只读留痕 */
    @Schema(description = "金蝶单据状态原始值")
    private String erpDocumentStatus;

    /** 同步状态：待同步/同步中/同步成功/同步失败/无需同步 */
    @Schema(description = "同步状态")
    private String syncStatus;

    /** 连续失败次数（成功清零） */
    @Schema(description = "连续失败次数")
    private Integer retryCount;

    /** 累计失败次数（告警阈值用） */
    @Schema(description = "累计失败次数")
    private Integer syncFailCount;

    /** 最近一次同步失败原因 */
    @Schema(description = "最近同步失败原因")
    private String lastSyncError;

    /** 最近一次成功同步时间 */
    @Schema(description = "最近同步成功时间")
    private Date lastSyncTime;

    /** MES 侧单号 */
    @Schema(description = "MES侧单号")
    private String mesOrderNo;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
