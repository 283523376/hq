package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.vo.WmsSyncFlowRowVO;

import java.util.Date;
import java.util.List;

/**
 * @Description: SB-001 同步流水（仅展示）
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
public interface WmsSyncFlowMapper extends BaseMapper<WmsSyncFlow> {

    /** 生产入库流水（良品 + 不良品两段 UNION ALL）。 */
    List<WmsSyncFlowRowVO> selectProductionInboundRows(@Param("windowStart") Date windowStart,
                                                        @Param("windowEnd") Date windowEnd);

    /** 出库流水（销售/领料/报废/样品/外协统一走 wms_outbound_order）。 */
    List<WmsSyncFlowRowVO> selectOutboundRows(@Param("windowStart") Date windowStart,
                                               @Param("windowEnd") Date windowEnd);

    /** 调拨流水（按调出/调入拆两行）。 */
    List<WmsSyncFlowRowVO> selectTransferRows(@Param("windowStart") Date windowStart,
                                               @Param("windowEnd") Date windowEnd);

    /** 盘点流水（盘盈 IN / 盘亏 OUT）。 */
    List<WmsSyncFlowRowVO> selectStocktakeRows(@Param("windowStart") Date windowStart,
                                                @Param("windowEnd") Date windowEnd);
}
