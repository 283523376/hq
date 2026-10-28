package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsInventoryWarning;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.jeecg.modules.wms.vo.WmsMaterialAvailableVO;

import java.util.List;

/**
 * 库存预警单 + 扫描用的库存聚合查询。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface WmsInventoryWarningMapper extends BaseMapper<WmsInventoryWarning> {

    /**
     * 按物料汇总可用量（quantity - locked_qty）。作用域参数为空即不限定。
     */
    List<WmsMaterialAvailableVO> selectAvailableByMaterial(@Param("materialId") String materialId,
                                                           @Param("categoryId") String categoryId,
                                                           @Param("warehouseId") String warehouseId);

    /**
     * 超期库存行：quantity &gt; 0 且首次入库时间早于 N 天前。
     */
    List<WmsInventoryStockRowVO> selectOverdueRows(@Param("overdueDays") int overdueDays,
                                                   @Param("materialId") String materialId,
                                                   @Param("categoryId") String categoryId,
                                                   @Param("warehouseId") String warehouseId,
                                                   @Param("limit") int limit);

    /**
     * 单物料主数据快照：库存为 0 时也要能报下限预警，此时聚合查询没有行可返回。
     */
    WmsMaterialAvailableVO selectMaterialSnapshot(@Param("materialId") String materialId);
}
