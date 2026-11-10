package org.jeecg.modules.wms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.mapper.WmsBatchTraceMapper;
import org.jeecg.modules.wms.service.IWmsBatchTraceService;
import org.jeecg.modules.wms.vo.WmsBatchFlowVO;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Description: 批次追溯查询。
 *
 * <p>流转记录直接联合现有 5 类单据明细（入库/生产入库/出库/调拨/盘点），
 * 不另立流水表——避免双写不一致（spec 2026-10-28 D11）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsBatchTraceServiceImpl implements IWmsBatchTraceService {

    private static final int MAX_FLOW_LIMIT = 500;
    private static final int DEFAULT_FLOW_LIMIT = 200;

    private final WmsBatchTraceMapper traceMapper;

    public WmsBatchTraceServiceImpl(WmsBatchTraceMapper traceMapper) {
        this.traceMapper = traceMapper;
    }

    @Override
    public List<WmsInventoryStockRowVO> batchStock(String batchNo) {
        return traceMapper.selectBatchStock(requireBatchNo(batchNo));
    }

    @Override
    public List<WmsBatchFlowVO> batchFlow(String batchNo, int limit) {
        int size = limit <= 0 ? DEFAULT_FLOW_LIMIT : Math.min(limit, MAX_FLOW_LIMIT);
        return traceMapper.selectBatchFlow(requireBatchNo(batchNo), size);
    }

    private String requireBatchNo(String batchNo) {
        if (!StringUtils.hasText(batchNo)) {
            throw new RuntimeException("批次号不能为空");
        }
        return batchNo.trim();
    }
}
