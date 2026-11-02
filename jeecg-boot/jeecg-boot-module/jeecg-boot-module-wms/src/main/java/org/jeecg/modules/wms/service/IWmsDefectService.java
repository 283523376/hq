package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.vo.WmsDefectLedgerVO;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;

/**
 * @Description: 不良品处置（XT-001）——查询废品仓台账、执行报废出库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
public interface IWmsDefectService {

    /**
     * 不良品台账：只返回**废品仓**的当前库存行（实时查询，不存快照）。
     *
     * @param materialKeyword 物料编码/名称模糊
     * @param batchNo         批次模糊
     * @param locationCode    库位编码模糊
     */
    IPage<WmsDefectLedgerVO> pageLedger(int pageNo, int pageSize, String materialKeyword,
                                        String batchNo, String locationCode);

    /**
     * 报废出库：扣减库存 + 建「报废出库」单（一步到位已完成）+ 审计留痕。
     *
     * @throws RuntimeException 数量非法 / 超可用量 / 非废品仓 / 并发被抢
     */
    WmsScrapResultVO scrap(WmsScrapDTO dto);
}
