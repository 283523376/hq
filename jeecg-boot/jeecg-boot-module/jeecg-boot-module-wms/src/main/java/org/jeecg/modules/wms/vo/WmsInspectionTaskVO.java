package org.jeecg.modules.wms.vo;

import lombok.Data;
import org.jeecg.modules.wms.entity.WmsInspectionTask;

/**
 * 检验任务列表出参（任务 + 单据冗余展示字段）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsInspectionTaskVO {

    /** 任务本体 */
    private WmsInspectionTask task;

    /** 入库单号 */
    private String orderNo;

    /** 采购订单号 */
    private String purchaseOrderNo;

    /** 供应商名称 */
    private String supplierName;
}
