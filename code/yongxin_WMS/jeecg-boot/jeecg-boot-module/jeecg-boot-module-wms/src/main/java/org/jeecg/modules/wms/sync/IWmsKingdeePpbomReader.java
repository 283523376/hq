package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;

import java.util.List;

/**
 * 金蝶生产用料清单（PRD_PPBOM）读取。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsKingdeePpbomReader {

    /**
     * 读取工单的领料需求：需求数量 = FNeedQty2 - FPickedQty，≤0 的行跳过。
     *
     * @param workOrderNo 生产订单号
     * @return 需求行；金蝶未启用或查询失败时返回空列表（调用方回落手工录入）
     */
    List<WmsOutboundRequirementVO> read(String workOrderNo);
}
