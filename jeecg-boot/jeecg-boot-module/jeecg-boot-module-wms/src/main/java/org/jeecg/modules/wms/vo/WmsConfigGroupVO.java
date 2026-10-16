package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 一个配置分组（含全部项）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置分组")
public class WmsConfigGroupVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分组 code")
    private String code;

    @Schema(description = "分组名称")
    private String name;

    @Schema(description = "分组内的配置项")
    private List<WmsConfigItemVO> items = new ArrayList<>();
}
