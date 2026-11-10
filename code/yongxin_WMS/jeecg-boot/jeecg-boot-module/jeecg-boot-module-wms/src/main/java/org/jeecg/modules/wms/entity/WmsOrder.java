package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "订单")
@TableName("wms_order")
public class WmsOrder extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    @Excel(name = "订单号", width = 25)
    @Schema(description = "订单号")
    private String orderNo;

    /** 订单类型：公司订单/采购订单/委外订单 */
    @Excel(name = "订单类型", width = 15)
    @Schema(description = "订单类型：公司订单/采购订单/委外订单")
    private String orderType;

    /** 供应商ID（wms_supplier.id） */
    @Schema(description = "供应商ID")
    private String supplierId;

    /**
     * 供应商名称（非持久化）：列表展示与同步报文用，查询后回填。
     */
    @TableField(exist = false)
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 供应商编码（非持久化）：随 supplierName 一并回填，供同步至 ERP/MES 的报文使用。
     */
    @TableField(exist = false)
    @Schema(description = "供应商编码")
    private String supplierCode;

    /** 订单状态：待审核/已审核/已下达/已驳回 */
    @Excel(name = "订单状态", width = 12)
    @Schema(description = "订单状态：待审核/已审核/已下达/已驳回")
    private String orderStatus;

    /** 审核人 */
    @Schema(description = "审核人")
    private String auditBy;

    /** 审核时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "审核时间")
    private Date auditTime;

    /** 审核意见（驳回时必填） */
    @Excel(name = "审核意见", width = 20)
    @Schema(description = "审核意见（驳回时必填）")
    private String auditRemark;

    /** 下达人 */
    @Schema(description = "下达人")
    private String dispatchBy;

    /** 下达时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "下达时间")
    private Date dispatchTime;

    /** 同步状态：待同步/同步中/同步成功/同步失败 */
    @Excel(name = "同步状态", width = 12)
    @Schema(description = "同步状态：待同步/同步中/同步成功/同步失败")
    private String syncStatus;

    /** 最近一次同步尝试中实际发生的重试次数 */
    @Excel(name = "重试次数", width = 10)
    @Schema(description = "最近一次同步尝试中实际发生的重试次数")
    private Integer retryCount;

    /** 最后一次同步失败原因 */
    @Schema(description = "最后一次同步失败原因")
    private String lastSyncError;

    /** 最后一次同步尝试时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后一次同步尝试时间")
    private Date lastSyncTime;

    /** 备注 */
    @Excel(name = "备注", width = 20)
    @Schema(description = "备注")
    private String remark;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不是 0/1 的 del_flag</b>：{@code uk_wms_order_no} 必须把删除标记
     * 一起纳入唯一索引，否则被删除的订单号会**永久占用**它 —— 而「重号再建」
     * 是常见操作，用户会直接撞上原始 SQL 异常。
     * 用**时间戳**而非固定值（如 1），是因为固定值会让同一订单号**只能删一次**；
     * 时间戳每次不同，「删除 → 重建 → 再删除」可无限次进行。
     *
     * <p>索引已相应改为 {@code (order_no, deleted_at)}；查询过滤由
     * {@code @TableLogic} 自动追加 {@code deleted_at = 0}。
     * {@code WmsOrderMapper.xml} 的认领/扫描 SQL 里是**显式**写的条件，已同步改为
     * {@code deleted_at = 0}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
