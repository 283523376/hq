package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 回滚某分组到上一版本的入参
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置回滚入参")
public class WmsConfigRollbackDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分组 code")
    private String group;
}
