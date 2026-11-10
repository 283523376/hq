package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 受托不良退货入参（RK-005）：对「待处理 + RETURN」的不良品记录发起退货。
 *
 * <p>退货单的物料/数量/受托方全部取自记录与来源入库单，入参只带记录 ID；
 * {@code remark} 预留接口层备注，不参与金蝶报文（备注是幂等定位符，不能拼可变内容）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@Accessors(chain = true)
@Schema(description = "受托不良退货入参")
public class WmsDefectReturnDTO {

    /** 不良品记录ID（wms_defect_record.id）。 */
    @Schema(description = "不良品记录ID")
    private String recordId;

    /** 备注（仅接口层留存，不写金蝶）。 */
    @Schema(description = "备注")
    private String remark;
}
