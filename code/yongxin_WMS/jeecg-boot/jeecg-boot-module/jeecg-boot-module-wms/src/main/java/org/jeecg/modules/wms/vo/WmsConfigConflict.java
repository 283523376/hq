package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 单条配置冲突（供前端在固定提示下方列出明细）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置冲突明细")
public class WmsConfigConflict implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "冲突的配置键")
    private String key;

    @Schema(description = "配置项中文名")
    private String name;

    @Schema(description = "冲突原因")
    private String reason;

    public WmsConfigConflict() {
    }

    public WmsConfigConflict(String key, String name, String reason) {
        this.key = key;
        this.name = name;
        this.reason = reason;
    }
}
