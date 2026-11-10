package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 保存某分组全部配置的入参（**整组提交**：缺项即冲突，不做部分更新）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置保存入参")
public class WmsConfigSaveDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分组 code")
    private String group;

    @Schema(description = "该组的全部配置项")
    private List<WmsConfigItemDTO> items = new ArrayList<>();
}
