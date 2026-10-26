package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.vo.WmsStocktakeDetailVO;

import java.util.List;

/**
 * @Description: 库存盘点（spec 2026-10-26 §5.3）。
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsStocktakeService {

    /** 建盘点单：按库位快照账面数量生成明细，并冻结范围内库位。 */
    WmsStocktakeOrder create(WmsStocktakeCreateDTO dto, String operator);

    /** 草稿 → 盘点中。 */
    void start(String id);

    WmsStocktakeOrder requireById(String id);

    List<WmsStocktakeDetail> listDetails(String stocktakeId);

    /** 录入实盘数量并计算差异。 */
    void recordActual(String id, List<WmsStocktakeDetailVO> actuals);

    /** 唯一允许改库存的盘点路径：按实盘覆盖库存。 */
    void applyAdjustment(String id, String operator);
}
