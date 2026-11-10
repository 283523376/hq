package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 不良品记录查询条件（{@code GET /wms/defect/records}）。
 *
 * <p>端点不再直接绑 {@code WmsDefectRecord} 实体：实体的 {@code createTime} 是
 * {@code java.util.Date}，GET 参数一传日期字符串就绑定失败（500）。查询窗口
 * 时间筛选用 {@code createTimeBegin/createTimeEnd}（按天，闭区间），由服务层展开成
 * {@code create_time >= begin 00:00:00 AND <= end 23:59:59}。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Data
@Schema(description = "不良品记录查询条件")
public class WmsDefectRecordQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "来源类型：PRODUCTION 生产 / CONSIGNED 受托")
    private String sourceType;

    @Schema(description = "来源单号（模糊）")
    private String sourceBillNo;

    @Schema(description = "物料编码（模糊）")
    private String materialCode;

    @Schema(description = "处置方式：SCRAP 入废品仓 / RETURN 退货")
    private String disposalType;

    @Schema(description = "状态：待处理 / 已处置 / 已退货")
    private String status;

    @Schema(description = "登记日期起（yyyy-MM-dd，含当天）")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date createTimeBegin;

    @Schema(description = "登记日期止（yyyy-MM-dd，含当天）")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date createTimeEnd;
}
