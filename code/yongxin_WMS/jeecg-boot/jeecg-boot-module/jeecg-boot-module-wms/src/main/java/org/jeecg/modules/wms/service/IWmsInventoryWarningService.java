package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.dto.WmsWarningHandleDTO;
import org.jeecg.modules.wms.vo.WmsWarningScanResultVO;

/**
 * @Description: 库存预警扫描与处理（KC-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface IWmsInventoryWarningService {

    /**
     * 按启用规则扫描一遍库存，命中的生成/合并预警。
     */
    WmsWarningScanResultVO scan();

    /**
     * 处理并解除一条待处理预警（记录处理方式与说明，不改库存）。
     *
     * @param dto      处理入参
     * @param operator 操作人（登录用户）
     */
    void handle(WmsWarningHandleDTO dto, String operator);
}
