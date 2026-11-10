package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsPickDemandResultVO;

/**
 * 领料待办自动生成（出库单反向生成，2026-10-28 自动化优先 P1）。
 *
 * <p>按已同步工单的金蝶用料清单（PRD_PPBOM）自动生成生产领料出库单，
 * 免去「用户自己算要领什么、录多少」的手工环节；剩余量 = 用料清单需求 - 在途领料单已申请量。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
public interface IWmsPickDemandService {

    /**
     * 扫描工单并生成领料待办。
     *
     * @return 生成结果（扫描工单数、生成单数、跳过原因摘要）
     */
    WmsPickDemandResultVO generatePendingPicks();
}
