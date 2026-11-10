package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 物料分组映射批量保存结果（新增/更新/无变化）。
 *
 * <p>三项之和 = 本次提交的去重分组数；「无变化」说明幂等保存不会产生无意义的
 * {@code update_time} 抖动。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Data
@Schema(description = "物料分组映射批量保存结果")
public class WmsMaterialGroupMapSaveResultVO {

    @Schema(description = "新增数")
    private int created;

    @Schema(description = "更新数")
    private int updated;

    @Schema(description = "无变化数")
    private int unchanged;

    @Schema(description = "结果说明")
    private String message;
}
