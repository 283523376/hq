package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /** 分页列表（按类型过滤，两个子菜单复用）。 */
    /**
     * @param stocktakeNo 盘点单号，**模糊匹配**（任意连续子串）；为空则不筛
     */
        IPage<WmsStocktakeOrder> listPage(int pageNo, int pageSize, String stocktakeType, String stocktakeNo,
                                      String keyword);

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

    /**
     * 重盘标记：在途设备任务落账时，若涉及正在盘点的库位，把对应明细标为需重盘；
     * 该库位不在任何进行中盘点单的明细里时插入一条快照重盘行。
     */
    void markRecheck(String locationId, String materialId, String batchNo, String containerCode);

    /** 差异确认：阈值内 → 差异确认；超阈值 → 复核中。 */
    void confirmDiff(String id, String operator);

    /** 复核通过：复核中 → 差异确认。复核人不得等于发起人（simulate-review 可跳过）。 */
    void review(String id, String reviewer);

    /** 记录 ERP 回写结果（成功/失败与说明），供界面与重试判断。 */
    void markErpWriteback(String id, boolean success, String message);

    /** 归档：已调整 → 已归档（终态）。 */
    void archive(String id);
}
