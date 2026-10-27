package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @Description: 单个配置项（元数据 + 当前值）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置项")
public class WmsConfigItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置键")
    private String key;

    @Schema(description = "中文名")
    private String label;

    @Schema(description = "类型：INT/BOOL/TEXT/MULTI")
    private String type;

    @Schema(description = "当前值（库里没有则为元数据默认值）")
    private String value;

    @Schema(description = "当前版本号；0 表示库里尚无该键")
    private int versionNo;

    @Schema(description = "是否存在可回滚的上一版本")
    private boolean canRollback;

    @Schema(description = "多选项的候选值；非多选为 null")
    private List<String> options;

    @Schema(description = "单选项的候选项（value 入库、label 展示）；非单选为 null")
    private List<WmsConfigOptionVO> optionItems;
}
