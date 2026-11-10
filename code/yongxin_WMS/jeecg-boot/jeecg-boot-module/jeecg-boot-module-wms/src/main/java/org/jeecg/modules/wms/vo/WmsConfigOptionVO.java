package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 配置项候选项（单选下拉用）：value 入库、label 给人看
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
@Data
@Schema(description = "配置项候选项")
public class WmsConfigOptionVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "实际存储值")
    private String value;

    @Schema(description = "展示文案")
    private String label;

    @Schema(description = "是否禁止选择（如库区缺少默认库位：选了也不会生效）")
    private Boolean disabled;

    public WmsConfigOptionVO() {
    }

    public WmsConfigOptionVO(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public WmsConfigOptionVO(String value, String label, Boolean disabled) {
        this.value = value;
        this.label = label;
        this.disabled = disabled;
    }
}
