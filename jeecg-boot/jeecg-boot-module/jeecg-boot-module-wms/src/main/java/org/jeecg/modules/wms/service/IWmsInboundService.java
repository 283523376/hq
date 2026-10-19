package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 采购入库单（收料通知单与采购入库单合一，RK-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsInboundService extends JeecgService<WmsInboundOrder> {

    /**
     * 扫码/录入采购订单号收料：校验采购订单与物料，生成入库单（待检验）与检验任务；
     * 外包装损坏时直接生成「已拒收」单据，不建检验任务。
     *
     * @param dto 收料入参
     * @return 生成的入库单
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    WmsInboundOrder receive(WmsReceiveDTO dto);
}
