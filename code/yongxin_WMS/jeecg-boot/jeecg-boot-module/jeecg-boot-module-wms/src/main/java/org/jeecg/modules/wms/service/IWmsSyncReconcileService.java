package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsSyncRun;

/**
 * @Description: SB-001 上位系统对账执行服务（spec 2026-11-03 §2.2-§2.7）。
 *
 * <p>职责：建批次 → 异步汇总 WMS 库存/流水 → 查金蝶即时库存（带重试）→ 比对 → 落差异 → 回填批次。
 * 只对账、不写账：不改 WMS 库存，也不在金蝶生成任何单据。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
public interface IWmsSyncReconcileService extends JeecgService<WmsSyncRun> {

    /**
     * 校验范围与时间窗后建批次，提交异步执行。
     *
     * @param dto      范围、仓库列表、可选水位线
     * @param operator 操作人（异步线程无 Shiro 上下文，必须显式传入并落库）
     * @return 批次 ID
     * @throws RuntimeException 范围非法 / WAREHOUSE 缺仓库 / 已有执行中批次
     * @throws IllegalArgumentException 非 TODAY 范围传了 windowStart（FULL/WAREHOUSE 不接受增量窗口）
     */
    String createRun(WmsSyncRunCreateDTO dto, String operator);

    /**
     * 同步执行一个批次（供定时任务重放/单测直调）。
     *
     * <p>所有异常在本方法内吞掉并置批次失败，不向调用方抛出。
     */
    void executeRun(String runId);

    /** 批次分页（status 可空）。 */
    IPage<WmsSyncRun> pageRuns(int pageNo, int pageSize, String status);

    /**
     * 取批次，不存在直接抛异常。
     *
     * @throws RuntimeException 批次不存在
     */
    WmsSyncRun requireRun(String id);
}
