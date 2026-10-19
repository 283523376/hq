package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.service.IWmsInspectionService;
import org.jeecg.modules.wms.vo.WmsInspectionTaskVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 来料检验任务服务（RK-001 step3-4）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsInspectionServiceImpl extends ServiceImpl<WmsInspectionTaskMapper, WmsInspectionTask>
        implements IWmsInspectionService {

    @Autowired
    private IWmsInboundService inboundService;

    @Override
    public IPage<WmsInspectionTaskVO> listPage(int pageNo, int pageSize, String inspectStatus) {
        QueryWrapper<WmsInspectionTask> qw = new QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(inspectStatus)) {
            qw.eq("inspect_status", inspectStatus);
        }
        qw.orderByDesc("create_time");
        IPage<WmsInspectionTask> page = page(new Page<>(pageNo, pageSize), qw);
        List<WmsInspectionTaskVO> vos = new ArrayList<>();
        for (WmsInspectionTask task : page.getRecords()) {
            WmsInspectionTaskVO vo = new WmsInspectionTaskVO();
            vo.setTask(task);
            WmsInboundOrder order = inboundService.getById(task.getInboundId());
            if (order != null) {
                vo.setOrderNo(order.getOrderNo());
                vo.setPurchaseOrderNo(order.getPurchaseOrderNo());
                vo.setSupplierName(order.getSupplierName());
            }
            vos.add(vo);
        }
        Page<WmsInspectionTaskVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(vos);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(String taskId, String result, String remark) {
        boolean passed = WmsConstants.INSPECT_STATUS_PASSED.equals(result);
        if (!passed && !WmsConstants.INSPECT_STATUS_FAILED.equals(result)) {
            throw new RuntimeException("检验结果必须是合格或不合格");
        }
        if (!passed && oConvertUtils.isEmpty(remark == null ? null : remark.trim())) {
            throw new RuntimeException("不合格必须填写原因");
        }
        WmsInspectionTask task = getById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if (!WmsConstants.INSPECT_STATUS_PENDING.equals(task.getInspectStatus())) {
            throw new RuntimeException("该任务已处理");
        }
        task.setInspectStatus(result);
        task.setResultRemark(oConvertUtils.isEmpty(remark) ? null : remark.trim());
        task.setInspector(currentUser());
        task.setInspectTime(new java.util.Date());
        updateById(task);
        inboundService.onInspectResult(task.getInboundId(), passed);
        log.info("检验完成：任务={}，结果={}，单据={}", task.getTaskNo(), result, task.getInboundId());
    }

    /** 当前操作人；无 Shiro 上下文（定时任务/单测）时记「系统」。 */
    private String currentUser() {
        try {
            Object principal = org.apache.shiro.SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof org.jeecg.common.system.vo.LoginUser) {
                return ((org.jeecg.common.system.vo.LoginUser) principal).getUsername();
            }
            return principal == null ? "系统" : String.valueOf(principal);
        } catch (Exception e) {
            return "系统";
        }
    }
}
