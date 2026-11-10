package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsInboundDetail;

/**
 * 采购入库明细。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInboundDetailMapper extends BaseMapper<WmsInboundDetail> {

    /**
     * 引用该物料、且所属单据处于「未终结」状态（待检验/待分配/待上架）的明细行数。
     * 用于删除守卫：入库流程进行中的物料不可删，否则上架时会因物料不存在而卡单。
     */
    long countActiveByMaterial(@org.apache.ibatis.annotations.Param("materialId") String materialId);

    /**
     * 引用该库位、且所属单据处于「未终结」状态的明细行数（删除守卫）。
     */
    long countActiveByLocation(@org.apache.ibatis.annotations.Param("locationId") String locationId);
}
