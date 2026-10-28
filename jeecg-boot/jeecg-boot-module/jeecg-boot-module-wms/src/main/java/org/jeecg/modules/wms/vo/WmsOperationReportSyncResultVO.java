package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 工序汇报落库结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "工序汇报落库结果")
public class WmsOperationReportSyncResultVO {

    @Schema(description = "拉取的分录行数")
    private int fetched;

    @Schema(description = "新建的入库草稿数")
    private int created;

    @Schema(description = "已存在而更新的数量")
    private int updated;

    @Schema(description = "跳过数量")
    private int skipped;

    @Schema(description = "跳过/失败原因（最多保留前 50 条）")
    private List<String> messages = new ArrayList<>();

    public void skip(String message) {
        skipped++;
        if (messages.size() < 50) {
            messages.add(message);
        }
    }
}
