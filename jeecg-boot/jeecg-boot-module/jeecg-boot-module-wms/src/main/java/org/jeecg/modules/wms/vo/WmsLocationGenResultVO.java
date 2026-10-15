package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 库位批量生成结果
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "库位批量生成结果")
public class WmsLocationGenResultVO {

    /** 本次实际插入的条数。 */
    @Schema(description = "本次实际插入的条数")
    private int generated;

    /** 因已存在而跳过的条数。{@code generated + skipped} 恒等于请求总数。 */
    @Schema(description = "因已存在而跳过的条数")
    private int skipped;
}