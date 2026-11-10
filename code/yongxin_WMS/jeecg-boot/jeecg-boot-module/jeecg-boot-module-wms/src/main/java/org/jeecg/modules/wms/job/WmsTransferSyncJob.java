package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsTransferSyncService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Description: 调拨单 ERP 同步任务（CK-004 Phase B）。
 *
 * <p>两件事：1) 把「实物完成 + 待同步/同步失败」的单提交金蝶（Save+Submit，停在 B）；
 * 2) 回读已提交单据的金蝶状态：C → 已完成、D → 异常/ERP驳回、A → 退回待同步、B → 检查审批超时。
 * 单条失败不中断整轮。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsTransferSyncJob implements Job {

    @Autowired
    private IWmsTransferSyncService syncService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsTransferSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsTransferSyncJob(IWmsTransferSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        runOnce();
    }

    void runOnce() {
        try {
            int submitted = syncService.submitPending();
            int polled = syncService.pollStatus();
            if (submitted > 0 || polled > 0) {
                log.info("调拨 ERP 同步：提交 {} 单，状态回读 {} 单", submitted, polled);
            }
        } catch (Exception e) {
            log.warn("调拨 ERP 同步任务异常（不影响下一轮）：{}", e.getMessage(), e);
        }
    }
}
