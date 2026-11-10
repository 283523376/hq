package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsTransferCreateDTO;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.vo.WmsTransferVO;

import java.util.Map;

/**
 * @Description: 库存调拨（CK-004）服务。
 *
 * <p>双时钟：{@code execStatus}（实物）+ {@code syncStatus}（ERP），总状态由
 * {@code WmsTransferStatusResolver.deriveStatus()} 派生。Phase A 只做执行段与库存移动，
 * ERP 回写（{@code syncErp}）与驳回冲销（{@code reverse}）留待 Phase B 前置条件确认后补齐。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsTransferService extends JeecgService<WmsTransferOrder> {

    /** 新建调拨单（只到仓库/区域级，不预分配库位）。 */
    WmsTransferOrder create(WmsTransferCreateDTO dto);

    /** 详情：主表 + 明细 + 搬运任务 + ERP 等待时长。 */
    WmsTransferVO detail(String id);

    /** 分配并下发（同仓走米塔；跨仓仅锁定待出发）。 */
    WmsTransferOrder dispatch(String id);

    /** 跨仓出发确认：源仓扣减，进入在途。 */
    WmsTransferOrder depart(String id);

    /** 跨仓到达确认：落目标仓暂存库位，进入待入库。 */
    WmsTransferOrder arrive(String id);

    /**
     * 跨仓上架确认：暂存库位 → 实际库位，实物完成。
     *
     * @param locationByDetailId 明细ID → 目标库位ID；为空则保留暂存库位
     */
    WmsTransferOrder putaway(String id, Map<String, String> locationByDetailId);

    /** 关单：释放锁定、取消设备任务（尽力而为）。 */
    WmsTransferOrder close(String id, String reason);

    /** 是否为调拨搬运任务号（供 RMS 回执路由）。 */
    boolean isTransferTask(String taskNo);

    /**
     * 设备任务回执。
     *
     * @return 是否命中调拨任务
     */
    boolean onTaskFeedback(String taskNo, String containerCode, String taskStatus, String message);

    /**
     * 料箱位置回调：把 stackCode 落到明细的实际调入库位。
     *
     * @return 更新的明细行数
     */
    int onContainerLocation(String containerCode, String stackCode);

    /** 手工提交/刷新金蝶调拨单（Phase B）。 */
    WmsTransferOrder syncErp(String id);

    /** 生成反向调拨单（Phase B）。 */
    WmsTransferOrder reverse(String id);
}
