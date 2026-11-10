package org.jeecg.modules.wms.service;

/**
 * @Description: 调拨单 ERP 同步（CK-004 Phase B）。
 *
 * <p>两个方向：
 * <ul>
 *   <li>提交：实物完成 + 待同步/同步失败 → 金蝶 Save + Submit（停在 B）；</li>
 *   <li>回读：已提交待审核 → 查 `FDocumentStatus` → C 置「已完成」、D 置「异常/ERP驳回」、A 退回待同步、B 检查审批超时。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsTransferSyncService {

    /**
     * 提交待同步/需重试的调拨单。
     *
     * @return 本次尝试提交的单数
     */
    int submitPending();

    /**
     * 回读已提交单据的金蝶状态（含审批超时告警）。
     *
     * @return 本次处理的单数
     */
    int pollStatus();
}
