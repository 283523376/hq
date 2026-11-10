package org.jeecg.modules.wms.dto;

import lombok.Data;

/**
 * 检验结果入参（RK-001 step4）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsInspectCompleteDTO {

    /** 检验结果：合格/不合格 */
    private String result;

    /** 检验结果说明（不合格时必填原因） */
    private String remark;
}
