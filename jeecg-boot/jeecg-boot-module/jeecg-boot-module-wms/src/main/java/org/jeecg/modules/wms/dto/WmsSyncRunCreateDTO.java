package org.jeecg.modules.wms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @Description: SB-001 建对账批次入参（spec 2026-11-03 §2.2）。
 *
 * <p>{@code scopeType} 取 {@code WmsConstants.SYNC_SCOPE_FULL/TODAY/WAREHOUSE}；
 * {@code WAREHOUSE} 必带 {@code warehouseCodes}（WMS 仓库编码，空项会被过滤）。
 *
 * <p>{@code windowStart} 可空：定时任务传「上次成功批次的 window_end」作为水位线；
 * 手工触发不传，走默认口径（TODAY = 当天 00:00 → 当前，FULL/WAREHOUSE = 当前时刻标记点查）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@Schema(description = "SB-001 建对账批次入参")
public class WmsSyncRunCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "范围：FULL 全量 / TODAY 今日变动 / WAREHOUSE 指定仓库")
    private String scopeType;

    @Schema(description = "指定仓库编码（scopeType=WAREHOUSE 时必填）")
    private List<String> warehouseCodes;

    /**
     * 时间窗起点（可空）：定时任务传水位线；手工触发不传。
     *
     * <p>DTO 的 Date 字段必须带 {@code @DateTimeFormat}，否则时间参数绑定失败（XT-002 评审 Critical）。
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "时间窗起点（可空；定时任务传水位线）")
    private Date windowStart;
}
