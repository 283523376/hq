package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @Description: RMS 料箱位置更新入参（RMS V1.6 §4.5）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "料箱位置更新")
public class WmsContainerLocationDTO {

    @Schema(description = "料箱明细")
    private List<Detail> details;

    @Data
    @Schema(description = "料箱位置明细")
    public static class Detail {

        @Schema(description = "料箱编码")
        private String containerCode;

        @Schema(description = "库位编码")
        private String stackCode;
    }
}
