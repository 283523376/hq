package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.service.IWmsInventoryLedgerService;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.springframework.stereotype.Service;

/**
 * @Description: 库存台账查询（KC-004）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Service
public class WmsInventoryLedgerServiceImpl implements IWmsInventoryLedgerService {

    private final WmsInventoryMapper inventoryMapper;

    public WmsInventoryLedgerServiceImpl(WmsInventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    /**
     * 查询条件归一化：分页查询与导出共用，保证两条路径的口径完全一致。
     *
     * <p>空串/纯空白统一转 null——否则 `LIKE '%%'` 会走进 SQL、白耗一次扫表。
     * 导出侧拿到的是从 {@code query_params} 反序列化回来的旧快照，也必须过这一道。
     */
    public static WmsInventoryLedgerQueryDTO normalize(WmsInventoryLedgerQueryDTO query) {
        WmsInventoryLedgerQueryDTO source = query == null ? new WmsInventoryLedgerQueryDTO() : query;
        WmsInventoryLedgerQueryDTO n = new WmsInventoryLedgerQueryDTO();
        n.setMaterialKeyword(trimToNull(source.getMaterialKeyword()));
        n.setBatchNo(trimToNull(source.getBatchNo()));
        n.setLocationCode(trimToNull(source.getLocationCode()));
        n.setWarehouseId(trimToNull(source.getWarehouseId()));
        n.setOnlyPositive(source.getOnlyPositive() == null ? Boolean.TRUE : source.getOnlyPositive());

        Integer pageNo = source.getPageNo();
        n.setPageNo(pageNo == null || pageNo < 1 ? 1 : pageNo);

        Integer pageSize = source.getPageSize();
        if (pageSize == null || pageSize < 1) {
            pageSize = WmsConstants.INVENTORY_LEDGER_DEFAULT_PAGE_SIZE;
        }
        n.setPageSize(Math.min(pageSize, WmsConstants.INVENTORY_LEDGER_MAX_PAGE_SIZE));
        return n;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public IPage<WmsInventoryLedgerVO> queryPage(WmsInventoryLedgerQueryDTO query) {
        WmsInventoryLedgerQueryDTO q = normalize(query);
        return inventoryMapper.selectLedgerPage(new Page<>(q.getPageNo(), q.getPageSize()), q);
    }
}
