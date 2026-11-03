package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsSyncDiff;

/**
 * @Description: SB-001 对账差异清单处理（spec 2026-11-03 §2.8）。
 *
 * <p>差异是正常业务结果，页面亮红灯即可，不发告警。管理员可标记「已核对/已忽略」（必填备注），
 * 或由差异发起盘点；WMS 无账面（仅ERP / WMS=0）时拒绝发起，不绕过既有流程硬造库存。
 *
 * <p>两个写方法都在请求线程内调用，操作人由入参显式传入，不自行读取 Shiro。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
public interface IWmsSyncDiffService extends JeecgService<WmsSyncDiff> {

    /**
     * 差异分页（空筛选忽略；materialKeyword 同时模糊匹配物料编码/名称）。
     *
     * @param pageNo          页码（至少 1）
     * @param pageSize        每页条数（上限 200）
     * @param materialKeyword 物料编码或名称的关键词，任意连续子串
     */
    IPage<WmsSyncDiff> pageDiffs(int pageNo, int pageSize, String runId, String warehouseCode,
                                 String materialKeyword, String status, String diffType);

    /**
     * 标记处理：只允许「待核对 → 已核对/已忽略」。
     *
     * @param targetStatus 目标状态：已核对/已忽略
     * @param remark       处理备注（trim 后必填）
     * @param operator     操作人
     * @throws RuntimeException 差异不存在 / 状态非法 / 备注为空
     */
    void handle(String id, String targetStatus, String remark, String operator);

    /**
     * 由差异发起盘点：按 仓库+物料+批次 反查 WMS 库位，建**盘点草稿**（不冻结）。
     *
     * <p>盘点只按「WMS 账面 vs 实盘」调整；若实盘恰等于账面，多账/少账差异仍需在金蝶侧人工处理。
     *
     * @param operator 操作人（同时写入 handle_by）
     * @return 新建的盘点单
     * @throws RuntimeException 差异不存在 / 仅ERP / WMS 无账面 / 反查不到库位
     */
    WmsStocktakeOrder createStocktake(String id, String operator);
}
