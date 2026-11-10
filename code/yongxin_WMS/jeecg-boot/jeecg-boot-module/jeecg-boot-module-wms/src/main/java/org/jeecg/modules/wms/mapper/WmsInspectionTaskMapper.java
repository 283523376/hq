package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsInspectionTask;

/**
 * 来料检验任务。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInspectionTaskMapper extends BaseMapper<WmsInspectionTask> {

    /**
     * 检验任务分页（联查入库单）：排除已作废/已拒收单据的任务——
     * 这两类终态的检验任务不应出现在「待检验」列表里，也不应可操作。
     *
     * @param page          分页
     * @param inspectStatus 检验状态筛选；null/空=全部
     */
    com.baomidou.mybatisplus.core.metadata.IPage<WmsInspectionTask> selectPageWithOrder(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsInspectionTask> page,
            @org.apache.ibatis.annotations.Param("inspectStatus") String inspectStatus);
}
