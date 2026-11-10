package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * @Description: 上位系统同步日志（BD-002 引入，SB-001 共用底座）。
 *
 * <p>报文脱敏后落库：令牌/密钥不落库，单条超 8KB 截断，保留 90 天（清理归 SB-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "上位系统同步日志")
@TableName("wms_sync_log")
public class WmsSyncLog extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 方向：ERP_TO_WMS/WMS_TO_ERP/WMS_TO_MES */
    @Schema(description = "方向")
    private String direction;

    /** 对象类型：WORK_ORDER/SALES_ORDER/INBOUND/OUTBOUND/TRANSFER/INVENTORY */
    @Schema(description = "对象类型")
    private String objectType;

    /** 业务单号 */
    @Schema(description = "业务单号")
    private String objectNo;

    /** 对端：KINGDEE/MES/WCS/RMS */
    @Schema(description = "对端")
    private String target;

    /** 结果：同步成功/同步失败 */
    @Schema(description = "结果")
    private String status;

    /** 本单当前连续失败次数 */
    @Schema(description = "连续失败次数")
    private Integer retryCount;

    /** 请求报文（脱敏，超 8KB 截断） */
    @Schema(description = "请求报文")
    private String requestPayload;

    /** 响应报文（脱敏，超 8KB 截断） */
    @Schema(description = "响应报文")
    private String responsePayload;

    /** 失败原因 */
    @Schema(description = "失败原因")
    private String errorMessage;

    /** 耗时毫秒 */
    @Schema(description = "耗时毫秒")
    private Integer costMs;
}
