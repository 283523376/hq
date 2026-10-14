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
     * 供应商名称（非持久化）：列表展示用，查询后回填。
     */
    @TableField(exist = false)
    @Schema(description = "供应商名称")
    private String supplierName;

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

    /** 删除状态：0正常 1已删除 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
