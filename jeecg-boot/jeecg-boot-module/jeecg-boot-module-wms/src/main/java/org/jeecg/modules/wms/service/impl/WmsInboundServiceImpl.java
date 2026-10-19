package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * @Description: 采购入库单服务（RK-001 状态机的收料段）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsInboundServiceImpl extends ServiceImpl<WmsInboundOrderMapper, WmsInboundOrder>
        implements IWmsInboundService {

    @Autowired
    private IWmsOrderService orderService;
    @Autowired
    private IWmsMaterialService materialService;
    @Autowired
    private IWmsMaterialCategoryService categoryService;
    @Autowired
    private WmsInboundDetailMapper detailMapper;
    @Autowired
    private WmsInspectionTaskMapper inspectionTaskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsInboundOrder receive(WmsReceiveDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getPurchaseOrderNo())) {
            throw new RuntimeException("采购订单号不能为空");
        }
        if (dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("收料明细不能为空");
        }
        WmsOrder po = orderService.getPurchaseOrder(dto.getPurchaseOrderNo().trim());
        if (po == null) {
            throw new RuntimeException("采购订单不存在");
        }

        boolean damaged = Boolean.TRUE.equals(dto.getPackageDamaged());
        WmsInboundOrder order = new WmsInboundOrder()
                .setOrderNo(nextOrderNo("SL"))
                .setInboundType(WmsConstants.INBOUND_TYPE_PURCHASE)
                .setPurchaseOrderNo(po.getOrderNo())
                .setSupplierId(po.getSupplierId())
                .setSupplierCode(po.getSupplierCode())
                .setSupplierName(po.getSupplierName())
                .setSyncStatus(WmsConstants.SYNC_STATUS_PENDING)
                .setRetryCount(0);
        if (damaged) {
            order.setInboundStatus(WmsConstants.INBOUND_STATUS_REJECTED);
            order.setRejectReason("外包装损坏，联系采购退货");
        } else {
            order.setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_INSPECT);
        }
        save(order);
        saveDetails(order.getId(), dto);

        if (!damaged) {
            WmsInspectionTask task = new WmsInspectionTask()
                    .setTaskNo(nextTaskNo("JY"))
                    .setInboundId(order.getId())
                    .setInspectStatus(WmsConstants.INSPECT_STATUS_PENDING);
            inspectionTaskMapper.insert(task);
        }
        log.info("收料完成：单号={}，状态={}，明细 {} 行", order.getOrderNo(), order.getInboundStatus(), dto.getDetails().size());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onInspectResult(String inboundId, boolean passed) {
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        if (!WmsConstants.INBOUND_STATUS_PENDING_INSPECT.equals(order.getInboundStatus())) {
            throw new RuntimeException("单据状态不允许检验回写");
        }
        order.setInboundStatus(passed
                ? WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE
                : WmsConstants.INBOUND_STATUS_INSPECT_FAILED);
        updateById(order);
        log.info("检验回写：单号={}，状态={}", order.getOrderNo(), order.getInboundStatus());
    }

    /** 保存明细行（校验物料与分类类型，带出冗余信息）。 */
    private void saveDetails(String inboundId, WmsReceiveDTO dto) {
        for (WmsReceiveDTO.Item item : dto.getDetails()) {
            if (item == null || oConvertUtils.isEmpty(item.getMaterialId())) {
                throw new RuntimeException("收料明细的物料不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity().signum() <= 0) {
                throw new RuntimeException("收料数量必须大于 0");
            }
            if (oConvertUtils.isEmpty(item.getBatchNo())) {
                throw new RuntimeException("批次号不能为空");
            }
            WmsMaterial material = materialService.getById(item.getMaterialId());
            if (material == null) {
                throw new RuntimeException("物料不存在");
            }
            String categoryType = resolveCategoryType(material);
            if (!WmsConstants.CATEGORY_TYPE_HALF_FINISHED.equals(categoryType)
                    && !WmsConstants.CATEGORY_TYPE_AUXILIARY.equals(categoryType)) {
                throw new RuntimeException(String.format("物料[%s]类型不支持采购入库", material.getMaterialName()));
            }
            WmsInboundDetail detail = new WmsInboundDetail()
                    .setInboundId(inboundId)
                    .setMaterialId(material.getId())
                    .setMaterialCode(material.getMaterialCode())
                    .setMaterialName(material.getMaterialName())
                    .setQuantity(item.getQuantity())
                    .setBatchNo(item.getBatchNo().trim());
            detailMapper.insert(detail);
        }
    }

    /** 取物料挂靠分类的类型（口径 10：不做向上继承；分类缺失视为空）。 */
    private String resolveCategoryType(WmsMaterial material) {
        if (oConvertUtils.isEmpty(material.getCategoryId())) {
            return null;
        }
        WmsMaterialCategory category = categoryService.getById(material.getCategoryId());
        return category == null ? null : category.getCategoryType();
    }

    /** 生成单号：SL + yyyyMMdd + 4 位当日序号（当日最大号 +1）。包级可见，便于单测打桩。 */
    String nextOrderNo(String prefix) {
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        List<WmsInboundOrder> latest = baseMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInboundOrder>query()
                        .likeRight("order_no", prefix + date + "%")
                        .orderByDesc("order_no")
                        .last("LIMIT 1"));
        int next = 1;
        if (latest != null && !latest.isEmpty() && latest.get(0).getOrderNo() != null) {
            next = Integer.parseInt(latest.get(0).getOrderNo().substring(prefix.length() + date.length())) + 1;
        }
        return prefix + date + String.format("%04d", next);
    }

    /** 生成检验任务号：JY + yyyyMMdd + 4 位当日序号。包级可见，便于单测打桩。 */
    String nextTaskNo(String prefix) {
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        List<WmsInspectionTask> latest = inspectionTaskMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInspectionTask>query()
                        .likeRight("task_no", prefix + date + "%")
                        .orderByDesc("task_no")
                        .last("LIMIT 1"));
        int next = 1;
        if (latest != null && !latest.isEmpty() && latest.get(0).getTaskNo() != null) {
            next = Integer.parseInt(latest.get(0).getTaskNo().substring(prefix.length() + date.length())) + 1;
        }
        return prefix + date + String.format("%04d", next);
    }
}
