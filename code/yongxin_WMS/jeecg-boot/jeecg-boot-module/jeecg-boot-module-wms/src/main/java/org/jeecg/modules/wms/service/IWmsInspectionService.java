package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.vo.WmsInspectionTaskVO;

/**
 * 来料检验任务（RK-001，整单级）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsInspectionService extends JeecgService<WmsInspectionTask> {

    /**
     * 分页查询检验任务，带单据冗余信息。
     *
     * @param pageNo        页码
     * @param pageSize      页大小
     * @param inspectStatus 检验状态筛选，空=全部
     * @return 分页结果
     */
    IPage<WmsInspectionTaskVO> listPage(int pageNo, int pageSize, String inspectStatus);

    /**
     * 提交检验结果：合格 → 单据转待分配；不合格 → 单据转检验不合格（转 RK-005）。
     *
     * @param taskId 任务ID
     * @param result 合格/不合格
     * @param remark 结果说明（不合格必填）
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    void complete(String taskId, String result, String remark);
}
