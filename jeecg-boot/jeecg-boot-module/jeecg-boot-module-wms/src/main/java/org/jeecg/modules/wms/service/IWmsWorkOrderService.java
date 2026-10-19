package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;

/**
 * 生产工单（YX-WMS-RK-002）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsWorkOrderService extends JeecgService<WmsWorkOrder> {

    /**
     * 按工单号查询（不存在返回 null）。
     */
    WmsWorkOrder getByWorkOrderNo(String workOrderNo);

    /**
     * 扫码调出工单信息：本地查不到且配置了 MES 查询地址时回源 MES。
     *
     * @throws RuntimeException 工单不存在 → 「工单号无效」
     */
    WmsWorkOrderInfoVO scan(String workOrderNo);

    /**
     * MES 推送工单（按工单号 upsert）。
     *
     * @throws RuntimeException 校验失败时携带中文文案
     */
    WmsWorkOrder push(WmsWorkOrderPushDTO dto);

    /**
     * 手工新增工单。
     */
    void addWorkOrder(WmsWorkOrder order);

    /**
     * 手工编辑工单。
     */
    void editWorkOrder(WmsWorkOrder order);

    /**
     * 删除工单；已被生产入库引用时拒绝。
     */
    void removeWorkOrder(String id);

    /**
     * 分页查询。
     */
    IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo, String productType,
                                 String workOrderStatus);
}
