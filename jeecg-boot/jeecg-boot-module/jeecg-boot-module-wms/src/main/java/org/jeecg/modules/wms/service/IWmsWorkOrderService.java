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
     * ERP 作废的最小落地通道（XT-002 方案 B + 评审 I3）。
     *
     * <p>只把状态改成「已作废」，**不做**物料/批次/数量校验——错单恰恰可能是
     * 多物料、物料已删、数量为 0 的单，走 push 会被门槛整单跳过，错单就永远停在「已下达」。
     *
     * @return true=状态发生迁移；false=单不存在 / 已是终态（幂等）
     */
    boolean markCancelledByErp(String workOrderNo);

    /**
     * 标记工单同步失败（BD-002 V2.2）：已存在则累加 retry/fail 计数并记录原因；
     * 不存在（新单未落库）静默跳过，由 wms_sync_log 留痕。
     */
    void markSyncFailed(String workOrderNo, String errorMessage);

    /**
     * 分页查询（支持来源/同步状态/创建时间窗口过滤）。
     */
    IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo, String productType,
                                 String workOrderStatus, String source, String syncStatus,
                                 String startDate, String endDate);
}
