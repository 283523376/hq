package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;

import java.util.List;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsOrderService extends JeecgService<WmsOrder> {

    /** 新增订单（订单号 trim + 查重，状态置为「待审核」）。 */
    void addOrder(WmsOrder order);

    /** 编辑订单（仅「待审核」或「已驳回」可编辑）。 */
    void editOrder(WmsOrder order);

    /** 审核订单：通过 → 已审核；驳回 → 已驳回（原因必填）。 */
    void auditOrder(WmsOrderAuditDTO dto);

    /** 删除订单（逻辑删除，仅「待审核」或「已驳回」可删）。 */
    void removeOrder(String id);

    /** 批量删除订单（逻辑删除）。 */
    void removeBatch(List<String> ids);

    /** 判断订单号是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkOrderNoExists(String orderNo, String id);

    /** 回填供应商名称（非持久化字段，供列表使用）。 */
    void fillSupplierName(List<WmsOrder> list);
}
