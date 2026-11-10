package org.jeecg.modules.wms.service;

/**
 * 出库设备任务下发/取消（事务外调用）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsOutboundTaskService {

    /** 下发当前未下发/失败的分配任务。 */
    void dispatch(String id);

    /** 关单时取消设备任务，尽力而为。 */
    void cancel(String id);
}
