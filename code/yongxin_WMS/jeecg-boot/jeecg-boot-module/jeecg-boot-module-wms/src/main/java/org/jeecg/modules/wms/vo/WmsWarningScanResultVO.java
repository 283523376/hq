package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 预警扫描结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "预警扫描结果")
public class WmsWarningScanResultVO {

    @Schema(description = "参与扫描的启用规则数")
    private int rules;

    @Schema(description = "本次命中的预警事实数（含合并）")
    private int scanned;

    @Schema(description = "新建预警数")
    private int created;

    @Schema(description = "合并到既有待处理预警数")
    private int merged;

    @Schema(description = "说明")
    private String message;

    public void addScanned() {
        scanned++;
    }

    public void addCreated() {
        scanned++;
        created++;
    }

    public void addMerged() {
        scanned++;
        merged++;
    }
}
