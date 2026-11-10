package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @Description: 盘点建单入参。
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@Schema(description = "盘点建单入参")
public class WmsStocktakeCreateDTO {

    @Schema(description = "盘点类型：定期/周期")
    private String stocktakeType;

    @Schema(description = "盘点范围：库位ID列表")
    private List<String> locationIds;

    @Schema(description = "备注")
    private String remark;
}
