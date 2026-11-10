package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;

/**
 * @Description: 库存台账查询（KC-004）。
 *
 * <p>只读业务，不涉及库存变动，所以不继承 {@code JeecgService}（没有实体）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
public interface IWmsInventoryLedgerService {

    /**
     * 按条件分页查询库存台账。
     *
     * @param query 查询条件，内部会先做归一化（空串转 null、页码/页大小兜底、pageSize 夹到上限）
     */
    IPage<WmsInventoryLedgerVO> queryPage(WmsInventoryLedgerQueryDTO query);
}
