package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 主数据（仓库 / 物料分类）同步结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@Schema(description = "主数据同步结果")
public class WmsKingdeeStockSyncResultVO {

    /** 金蝶返回的行（或分组）数。 */
    @Schema(description = "从金蝶拉取的行数")
    private int fetched;

    /** 命中既有映射、仅改名的仓库数（分类同步中表示更新的分类数）。 */
    @Schema(description = "更新数")
    private int renamed;

    /** 新建的仓库数（分类同步恒为 0）。 */
    @Schema(description = "新建数")
    private int created;

    @Schema(description = "结果说明")
    private String message;
}
