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

import java.util.Date;

/**
 * @Description: SB-001 对账批次（spec 2026-11-03 §2.5）。
 *
 * <p>一次对账执行一行：记录操作人、范围、时间窗与计数。汇总与比对异步执行，
 * 前端轮询 {@code status}（执行中/成功/失败）。{@code operator} 显式存储——
 * 异步线程没有 Shiro 上下文，不能依赖 {@code createBy}。
 *
 * <p>无逻辑删除：对账是时点快照，按保留期物理清理（180 天，见 §2.9.1）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "SB-001 对账批次")
@TableName("wms_sync_run")
public class WmsSyncRun extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 批次号：SB + yyyyMMddHHmmss + 3 位序号（uk_run_no 唯一） */
    @Schema(description = "批次号")
    @Excel(name = "批次号", width = 22)
    private String runNo;

    /** 范围：FULL 全量 / TODAY 今日变动 / WAREHOUSE 指定仓库 */
    @Schema(description = "范围：FULL/TODAY/WAREHOUSE")
    @Excel(name = "范围", width = 12)
    private String scopeType;

    /** 指定仓库编码，逗号分隔（scope_type=WAREHOUSE 时用） */
    @Schema(description = "指定仓库编码（逗号分隔）")
    @Excel(name = "指定仓库", width = 30)
    private String warehouseCodes;

    /** 时间窗起点 */
    @Schema(description = "时间窗起点")
    @Excel(name = "时间窗起点", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date windowStart;

    /** 时间窗终点 */
    @Schema(description = "时间窗终点")
    @Excel(name = "时间窗终点", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date windowEnd;

    /** 状态：执行中 / 成功 / 失败 */
    @Schema(description = "状态：执行中/成功/失败")
    @Excel(name = "状态", width = 10)
    private String status;

    /** 快照行数 */
    @Schema(description = "快照行数")
    @Excel(name = "快照行数", width = 10)
    private Integer snapshotCount;

    /** 差异行数 */
    @Schema(description = "差异行数")
    @Excel(name = "差异行数", width = 10)
    private Integer diffCount;

    /** 流水行数 */
    @Schema(description = "流水行数")
    @Excel(name = "流水行数", width = 10)
    private Integer flowCount;

    /** 本次执行的重试次数（0~3，金蝶查询调用） */
    @Schema(description = "重试次数")
    @Excel(name = "重试次数", width = 10)
    private Integer retryCount;

    /** 耗时毫秒 */
    @Schema(description = "耗时毫秒")
    @Excel(name = "耗时(ms)", width = 12)
    private Integer costMs;

    /** 失败原因 */
    @Schema(description = "失败原因")
    @Excel(name = "失败原因", width = 40)
    private String errorMessage;

    /** 操作人（异步线程无 Shiro 上下文，显式存储；定时任务写 system） */
    @Schema(description = "操作人")
    @Excel(name = "操作人", width = 14)
    private String operator;
}
