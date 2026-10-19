package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
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
    private org.jeecg.modules.wms.service.IWmsLocationService locationService;
    @Autowired
    private WmsInboundDetailMapper detailMapper;
    @Autowired
    private WmsInspectionTaskMapper inspectionTaskMapper;
    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;
    @Autowired
    private org.jeecg.modules.wms.mapper.WmsLocationMapper locationMapper;
    @Autowired
    private org.jeecg.modules.wms.service.IWmsWarehouseService warehouseService;
    @Autowired
    private org.jeecg.modules.wms.service.IWmsInboundSyncService syncService;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public org.jeecg.modules.wms.vo.WmsAllocateResultVO autoAllocate(String inboundId) {
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        if (!WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE.equals(order.getInboundStatus())
                && !WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY.equals(order.getInboundStatus())) {
            throw new RuntimeException("当前状态不允许自动分配");
        }
        java.util.List<WmsInboundDetail> details = detailMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInboundDetail>query()
                        .eq("inbound_id", inboundId).orderByAsc("id"));
        org.jeecg.modules.wms.vo.WmsAllocateResultVO vo = new org.jeecg.modules.wms.vo.WmsAllocateResultVO();
        // 每个库区类型只查一次空闲库位，分配一行消费一个候选（口径 9）
        java.util.Map<String, java.util.Deque<WmsLocation>> freeByType = new java.util.HashMap<>();
        int allocated = 0;
        for (WmsInboundDetail detail : details) {
            if (oConvertUtils.isNotEmpty(detail.getLocationId())) {
                allocated++;
                continue;
            }
            WmsMaterial material = materialService.getById(detail.getMaterialId());
            String warehouseType = material == null ? null : toWarehouseType(resolveCategoryType(material));
            if (warehouseType == null) {
                addUnallocated(vo, detail, material == null ? "物料不存在" : "该物料类型不支持自动分配");
                continue;
            }
            WmsLocation free = freeByType
                    .computeIfAbsent(warehouseType, k -> new java.util.ArrayDeque<>(locationService.listFreeByWarehouseType(k)))
                    .poll();
            if (free == null) {
                addUnallocated(vo, detail, "库位不足");
                if (!vo.getWarnings().contains("库位不足，请人工介入")) {
                    vo.getWarnings().add("库位不足，请人工介入");
                }
                continue;
            }
            detail.setLocationId(free.getId());
            detailMapper.updateById(detail);
            allocated++;
        }
        vo.setAllocatedCount(allocated);
        boolean allAllocated = !details.isEmpty() && allocated == details.size();
        if (allAllocated && WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE.equals(order.getInboundStatus())) {
            order.setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY);
            updateById(order);
        }
        log.info("自动分配：单号={}，分配 {} / {} 行，告警 {}", order.getOrderNo(), allocated, details.size(), vo.getWarnings().size());
        return vo;
    }

    private void addUnallocated(org.jeecg.modules.wms.vo.WmsAllocateResultVO vo, WmsInboundDetail detail, String reason) {
        org.jeecg.modules.wms.vo.WmsAllocateResultVO.Unallocated u = new org.jeecg.modules.wms.vo.WmsAllocateResultVO.Unallocated();
        u.setDetailId(detail.getId());
        u.setMaterialName(detail.getMaterialName());
        u.setReason(reason);
        vo.getUnallocated().add(u);
    }

    // ------------------------------------------------------------ ERP 同步补偿支撑（Task 7）

    @Override
    public java.util.Date syncStaleBefore() {
        return new java.util.Date(System.currentTimeMillis()
                - WmsConstants.INBOUND_SYNC_STALE_MINUTES * 60_000L);
    }

    @Override
    public int claimForSync(String id, java.util.Date staleBefore) {
        return baseMapper.claimForSync(id, staleBefore);
    }

    @Override
    public java.util.List<WmsInboundOrder> selectSyncCandidates(java.util.Date staleBefore, int limit) {
        return baseMapper.selectSyncCandidates(staleBefore, limit);
    }

    @Override
    public void persistSyncResult(String id, org.jeecg.modules.wms.sync.WmsOrderSyncAttempt attempt) {
        WmsInboundOrder result = new WmsInboundOrder();
        result.setId(id);
        result.setSyncStatus(attempt.isSuccess()
                ? WmsConstants.SYNC_STATUS_SUCCESS
                : WmsConstants.SYNC_STATUS_FAILED);
        result.setRetryCount(attempt.getRetryCount());
        result.setLastSyncError(attempt.isSuccess() ? null : attempt.getErrorMessage());
        result.setLastSyncTime(new java.util.Date());
        updateById(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(String inboundId, String reason) {
        if (oConvertUtils.isEmpty(reason == null ? null : reason.trim())) {
            throw new RuntimeException("作废原因不能为空");
        }
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        String status = order.getInboundStatus();
        boolean preStock = WmsConstants.INBOUND_STATUS_PENDING_INSPECT.equals(status)
                || WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE.equals(status)
                || WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY.equals(status);
        if (!preStock) {
            throw new RuntimeException("当前状态不可作废");
        }
        order.setInboundStatus(WmsConstants.INBOUND_STATUS_VOIDED);
        order.setRejectReason("作废：" + reason.trim());
        updateById(order);
        log.info("单据作废：单号={}，原因={}", order.getOrderNo(), reason.trim());
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<WmsInboundOrder> listPage(
            int pageNo, int pageSize, String inboundStatus, String orderNo, String supplierName) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsInboundOrder> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(inboundStatus)) {
            qw.eq("inbound_status", inboundStatus);
        }
        if (oConvertUtils.isNotEmpty(orderNo)) {
            qw.like("order_no", orderNo.trim());
        }
        if (oConvertUtils.isNotEmpty(supplierName)) {
            qw.like("supplier_name", supplierName.trim());
        }
        qw.orderByDesc("create_time");
        return page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize), qw);
    }

    @Override
    public org.jeecg.modules.wms.vo.WmsInboundDetailVO detail(String id) {
        WmsInboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        org.jeecg.modules.wms.vo.WmsInboundDetailVO vo = new org.jeecg.modules.wms.vo.WmsInboundDetailVO();
        vo.setOrder(order);
        vo.setDetails(detailMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInboundDetail>query()
                        .eq("inbound_id", id).orderByAsc("id")));
        java.util.List<WmsInspectionTask> tasks = inspectionTaskMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInspectionTask>query()
                        .eq("inbound_id", id).last("LIMIT 1"));
        vo.setInspection(tasks == null || tasks.isEmpty() ? null : tasks.get(0));
        return vo;
    }

    /** 分类类型 → 库区类型（半成品→立库、辅料→线边柜、其他→null，口径 10）。 */
    private String toWarehouseType(String categoryType) {
        if (WmsConstants.CATEGORY_TYPE_HALF_FINISHED.equals(categoryType)) {
            return WmsConstants.WAREHOUSE_TYPE_STEREO;
        }
        if (WmsConstants.CATEGORY_TYPE_AUXILIARY.equals(categoryType)) {
            return WmsConstants.WAREHOUSE_TYPE_LINE_SIDE;
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void putaway(String inboundId, org.jeecg.modules.wms.dto.WmsPutawayDTO dto) {
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        if (!WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE.equals(order.getInboundStatus())
                && !WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY.equals(order.getInboundStatus())) {
            throw new RuntimeException("当前状态不允许上架");
        }
        if (dto == null || dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("上架明细不能为空");
        }
        java.util.List<WmsInboundDetail> rows = detailMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsInboundDetail>query()
                        .eq("inbound_id", inboundId));
        java.util.Map<String, WmsInboundDetail> byId = new java.util.HashMap<>();
        for (WmsInboundDetail d : rows) {
            byId.put(d.getId(), d);
        }
        // 预校验（不做任何写操作）：明细存在、实收非负、整单至少一行 > 0
        for (org.jeecg.modules.wms.dto.WmsPutawayDTO.Item item : dto.getDetails()) {
            if (byId.get(item.getDetailId()) == null) {
                throw new RuntimeException("上架明细与单据不匹配");
            }
            if (item.getActualQuantity() == null || item.getActualQuantity().signum() < 0) {
                throw new RuntimeException("实收数量不能为负");
            }
        }
        int positiveRows = 0;
        for (org.jeecg.modules.wms.dto.WmsPutawayDTO.Item item : dto.getDetails()) {
            if (item.getActualQuantity().signum() > 0) {
                positiveRows++;
            }
        }
        if (positiveRows == 0) {
            throw new RuntimeException("整单实收不能全为 0");
        }

        for (org.jeecg.modules.wms.dto.WmsPutawayDTO.Item item : dto.getDetails()) {
            WmsInboundDetail d = byId.get(item.getDetailId());
            if (item.getActualQuantity().signum() > 0) {
                if (oConvertUtils.isEmpty(item.getLocationId())) {
                    throw new RuntimeException("实收大于 0 的行必须指定库位");
                }
                WmsLocation loc = locationMapper.selectById(item.getLocationId());
                if (loc == null || !WmsConstants.LOCATION_STATUS_IDLE.equals(loc.getStatus())) {
                    throw new RuntimeException("库位不空闲或不存在");
                }
                org.jeecg.modules.wms.entity.WmsWarehouse warehouse = warehouseService.getById(loc.getWarehouseId());
                String expectedType = toWarehouseType(resolveCategoryType(materialService.getById(d.getMaterialId())));
                if (warehouse == null || expectedType == null || !expectedType.equals(warehouse.getWarehouseType())) {
                    throw new RuntimeException("库位类型与物料不匹配");
                }
                int affected = locationMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsLocation>()
                                .set("status", WmsConstants.LOCATION_STATUS_OCCUPIED)
                                .eq("id", loc.getId())
                                .eq("status", WmsConstants.LOCATION_STATUS_IDLE));
                if (affected == 0) {
                    throw new RuntimeException("库位已被占用，请重新分配");
                }
                inventoryMapper.upsertInventory(new WmsInventory()
                        .setMaterialId(d.getMaterialId())
                        .setLocationId(loc.getId())
                        .setBatchNo(d.getBatchNo())
                        .setQuantity(item.getActualQuantity()));
            }
            d.setActualQuantity(item.getActualQuantity());
            detailMapper.updateById(d);
        }

        // 已入库（未审核）→ 自动审核为已审核：同一事务内的瞬时态，持久化终态为已审核
        order.setInboundStatus(WmsConstants.INBOUND_STATUS_AUDITED);
        order.setAuditBy("system");
        order.setAuditTime(new java.util.Date());
        try {
            org.jeecg.modules.wms.sync.WmsOrderSyncAttempt attempt = syncService.syncInbound(order, true);
            order.setSyncStatus(attempt.isSuccess() ? WmsConstants.SYNC_STATUS_SUCCESS : WmsConstants.SYNC_STATUS_FAILED);
            order.setLastSyncError(attempt.isSuccess() ? null : attempt.getErrorMessage());
        } catch (Exception e) {
            log.error("入库单[{}]同步 ERP 异常：{}", order.getOrderNo(), e.getMessage(), e);
            order.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED);
            order.setLastSyncError(e.getMessage());
        }
        order.setLastSyncTime(new java.util.Date());
        updateById(order);
        log.info("上架完成：单号={}，状态={}，同步={}", order.getOrderNo(), order.getInboundStatus(), order.getSyncStatus());
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
