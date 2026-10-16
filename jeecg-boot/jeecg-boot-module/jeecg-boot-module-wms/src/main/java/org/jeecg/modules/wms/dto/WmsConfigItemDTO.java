package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 单个配置键值（保存入参）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置项键值")
public class WmsConfigItemDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置键")
    private String key;

    @Schema(description = "配置值")
    private String value;

    public WmsConfigItemDTO() {
    }

    public WmsConfigItemDTO(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
