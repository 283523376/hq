package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsTransferCreateDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.entity.WmsTransferTask;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsTransferDetailMapper;
import org.jeecg.modules.wms.mapper.WmsTransferOrderMapper;
import org.jeecg.modules.wms.mapper.WmsTransferTaskMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsTransferInventoryService;
import org.jeecg.modules.wms.service.IWmsTransferService;
import org.jeecg.modules.wms.service.IWmsTransferSyncService;
import org.jeecg.modules.wms.sync.IWmsRmsGateway;
import org.jeecg.modules.wms.sync.WmsRmsTransferTask;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.jeecg.modules.wms.vo.WmsTransferVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @Description: 库存调拨（CK-004）实现。
 *
 * <p>状态口径（spec §5）：
 * <ul>
 *   <li>总状态 {@code status} 一律由 {@link WmsTransferStatusResolver#deriveStatus} 派生后写入；</li>
 *   <li>每次写入都先 {@code assertTransition}，终态（已完成/已关闭）不再推进；</li>
 *   <li>实物完成即移动本地库存，但「已完成」必须等金蝶 C（Phase B）。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsTransferServiceImpl extends ServiceImpl<WmsTransferOrderMapper, WmsTransferOrder>
        implements IWmsTransferService {

    @Autowired
    private WmsTransferDetailMapper detailMapper;

    @Autowired
    private WmsTransferTaskMapper taskMapper;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Autowired
    private WmsLocationMapper locationMapper;

    @Autowired
    private WmsMaterialMapper materialMapper;

    @Autowired
    private WmsInventoryMapper inventoryMapper;

    @Autowired
    private IWmsTransferInventoryService inventoryService;

    @Autowired
    private IWmsRmsGateway rmsGateway;

    @Autowired
    private WmsTransferStatusResolver statusResolver;

    @Autowired
    private IWmsTransferSyncService transferSyncService;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Value("${wms.transfer.transit-location-id:}")
    private String transitLocationId;

    @Value("${wms.transfer.enable-transit:true}")
    private boolean enableTransit;

    /** 跨库调拨开关：业务已确认「跨库由金蝶 ERP 实现」，WMS 侧默认关闭（保留代码以备重启）。 */
    @Value("${wms.transfer.enable-cross-warehouse:false}")
    private boolean enableCrossWarehouse;

    @Value("${wms.transfer.org-code:101}")
    private String orgCode;

    @Value("${wms.sync.rms.warehouse:MITA}")
    private String rmsWarehouse;

    /** 同仓搬运的默认目标区域（业务确认：调拨不要求精准到库位，区域必填）。 */
    @Value("${wms.sync.rms.transfer-default-zone:PRE_ORDER}")
    private String transferDefaultZone;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser;

    // ==================== 建单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder create(WmsTransferCreateDTO dto) {
        WmsWarehouse out = requireWarehouse(dto.getOutWarehouseId(), "调出库区");
        WmsWarehouse in = requireWarehouse(dto.getInWarehouseId(), "调入库区");
        boolean sameWarehouse = out.getId().equals(in.getId());
        if (!sameWarehouse && !enableCrossWarehouse) {
            // 业务口径（2026-10-22）：跨库调拨由金蝶 ERP 负责，WMS 只做同一库区内的区域/库位移库
            throw new RuntimeException("跨库调拨由金蝶 ERP 实现，WMS 暂不支持（wms.transfer.enable-cross-warehouse=false）");
        }
        if (!sameWarehouse && oConvertUtils.isEmpty(transitLocationId)) {
            throw new RuntimeException("跨仓调拨未配置在途暂存库位（wms.transfer.transit-location-id），请先在系统配置中指定");
        }
        // 区域口径（业务确认）：调拨只到仓库/区域级，不要求精准到库位；同仓搬运区域可缺省，回落到配置默认值
        String inZone = oConvertUtils.isNotEmpty(dto.getInZoneCode())
                ? dto.getInZoneCode().trim()
                : (sameWarehouse ? trimToNull(transferDefaultZone) : null);
        if (sameWarehouse && oConvertUtils.isEmpty(inZone)) {
            throw new RuntimeException("同仓区域搬运需要目标区域编码（zoneCode），请填写或配置 wms.sync.rms.transfer-default-zone");
        }

        WmsTransferOrder order = new WmsTransferOrder()
                .setTransferNo(WmsConstants.TRANSFER_NO_PREFIX + IdWorker.getIdStr())
                .setSource(WmsConstants.TRANSFER_SOURCE_WMS)
                .setErpOrgCode(orgCode)
                .setOutWarehouseId(out.getId())
                .setOutWarehouseCode(out.getWarehouseCode())
                .setOutZoneCode(trimToNull(dto.getOutZoneCode()))
                .setInWarehouseId(in.getId())
                .setInWarehouseCode(in.getWarehouseCode())
                .setInZoneCode(inZone)
                .setTransferMode(sameWarehouse ? WmsConstants.TRANSFER_MODE_SAME : WmsConstants.TRANSFER_MODE_CROSS)
                .setExecStatus(WmsConstants.TRANSFER_EXEC_PENDING)
                // 同仓区域搬运是 WMS 内部库位移动，金蝶无法表达「同仓移库」→ 无需 ERP 同步；
                // 只有跨仓调拨才回写直接调拨单并等 C
                .setSyncStatus(sameWarehouse
                        ? WmsConstants.SYNC_STATUS_NOT_REQUIRED : WmsConstants.SYNC_STATUS_PENDING)
                .setStatus(WmsConstants.TRANSFER_STATUS_PENDING)
                .setTransitLocationId(sameWarehouse ? null : transitLocationId)
                .setRetryCount(0)
                .setSyncFailCount(0)
                .setRemark(dto.getRemark());
        save(order);

        int lineNo = 1;
        for (WmsTransferCreateDTO.Detail detailDTO : dto.getDetails()) {
            WmsMaterial material = materialMapper.selectById(detailDTO.getMaterialId());
            if (material == null) {
                throw new RuntimeException("物料不存在：" + detailDTO.getMaterialId());
            }
            WmsTransferDetail detail = new WmsTransferDetail()
                    .setTransferId(order.getId())
                    .setLineNo(lineNo++)
                    .setMaterialId(material.getId())
                    .setMaterialCode(material.getMaterialCode())
                    .setMaterialName(material.getMaterialName())
                    .setBatchNo(detailDTO.getBatchNo() == null ? "" : detailDTO.getBatchNo().trim())
                    .setContainerCode(detailDTO.getContainerCode() == null ? "" : detailDTO.getContainerCode().trim())
                    .setUnit(oConvertUtils.isEmpty(detailDTO.getUnit()) ? material.getUnit() : detailDTO.getUnit())
                    .setTransferQty(detailDTO.getTransferQty())
                    .setActualQty(BigDecimal.ZERO)
                    .setRemark(detailDTO.getRemark());
            detailMapper.insert(detail);
        }
        log.info("调拨单已创建：{}（{}，{}→{}）", order.getTransferNo(), order.getTransferMode(),
                order.getOutWarehouseCode(), order.getInWarehouseCode());
        return order;
    }

    // ==================== 查询 ====================

    @Override
    public WmsTransferVO detail(String id) {
        WmsTransferOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("调拨单不存在");
        }
        WmsTransferVO vo = new WmsTransferVO();
        vo.setOrder(order);
        vo.setDetails(listDetails(id));
        vo.setTasks(taskMapper.selectList(new LambdaQueryWrapper<WmsTransferTask>()
                .eq(WmsTransferTask::getTransferId, id)
                .orderByAsc(WmsTransferTask::getCreateTime)));
        if (WmsConstants.TRANSFER_STATUS_PENDING_ERP.equals(order.getStatus()) && order.getExecFinishTime() != null) {
            long millis = System.currentTimeMillis() - order.getExecFinishTime().getTime();
            vo.setWaitingHours(Math.max(0, TimeUnit.MILLISECONDS.toHours(millis)));
        }
        return vo;
    }

    // ==================== 执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder dispatch(String id) {
        WmsTransferOrder order = requireOrder(id);
        requireStatus(order, WmsConstants.TRANSFER_STATUS_PENDING, "只有「待执行」的调拨单可下发");

        List<WmsTransferDetail> details = listDetails(id);
        List<WmsTransferTask> tasks = new ArrayList<>();
        for (WmsTransferDetail detail : details) {
            tasks.addAll(inventoryService.allocate(order, detail));
            detail.setActualQty(detail.getTransferQty());
            detail.setUpdateTime(new Date());
            detailMapper.updateById(detail);
        }

        boolean sameWarehouse = WmsConstants.TRANSFER_MODE_SAME.equals(order.getTransferMode());
        String failure = null;
        if (sameWarehouse) {
            List<WmsRmsTransferTask> payload = new ArrayList<>();
            for (WmsTransferTask task : tasks) {
                WmsRmsTransferTask item = new WmsRmsTransferTask();
                item.setTaskNo(task.getTaskNo());
                item.setContainerCode(task.getContainerCode());
                item.setZoneCode(order.getInZoneCode());
                payload.add(item);
            }
            WmsTaskDispatchResult result = rmsGateway.dispatchTransfer(order.getInZoneCode(), rmsWarehouse, payload);
            if (result.isSuccess()) {
                for (WmsTransferTask task : tasks) {
                    task.setTaskStatus(WmsConstants.TASK_STATUS_ACCEPTED)
                            .setMocked(result.isMocked())
                            .setUpdateTime(new Date());
                    taskMapper.updateById(task);
                }
                if (result.isMocked()) {
                    log.info("调拨单[{}] RMS 任务为模拟受理（未配置地址）", order.getTransferNo());
                }
            } else {
                failure = result.getErrorMessage();
                for (WmsTransferTask task : tasks) {
                    task.setTaskStatus(WmsConstants.TASK_STATUS_FAILED)
                            .setTaskError(truncate(failure))
                            .setUpdateTime(new Date());
                    taskMapper.updateById(task);
                }
            }
        }

        if (failure != null) {
            applyExecStatus(order, WmsConstants.TRANSFER_EXEC_EXCEPTION);
            order.setLastSyncError(truncate(failure));
            updateById(order);
            sendAlert(WmsConstants.TRANSFER_DEVICE_ALERT_TITLE,
                    "调拨单 " + order.getTransferNo() + " 下发失败：" + failure);
            return order;
        }

        applyExecStatus(order, WmsConstants.TRANSFER_EXEC_RUNNING);
        order.setOperator(currentOperator());
        updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder depart(String id) {
        WmsTransferOrder order = requireOrder(id);
        requireSameMode(order, false);
        requireExec(order, WmsConstants.TRANSFER_EXEC_RUNNING, "只有在「执行中」的跨仓单可出发确认");
        for (WmsTransferTask task : listTasks(id)) {
            inventoryService.deductSource(task);
            task.setTaskStatus(WmsConstants.TASK_STATUS_EXECUTING).setUpdateTime(new Date());
            taskMapper.updateById(task);
        }
        if (enableTransit) {
            applyExecStatus(order, WmsConstants.TRANSFER_EXEC_IN_TRANSIT);
        } else {
            // 不启用两段式：出发即落暂存库位，直接进入「待入库」
            WmsLocation transit = requireTransitLocation(order);
            for (WmsTransferTask task : listTasks(id)) {
                WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
                inventoryService.addToLocation(task, detail, transit.getId(), transit.getLocationCode());
                detailMapper.updateById(detail);
            }
            applyExecStatus(order, WmsConstants.TRANSFER_EXEC_PENDING_INBOUND);
        }
        order.setOperator(currentOperator());
        updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder arrive(String id) {
        WmsTransferOrder order = requireOrder(id);
        requireSameMode(order, false);
        requireExec(order, WmsConstants.TRANSFER_EXEC_IN_TRANSIT, "只有在「在途」的跨仓单可到达确认");
        WmsLocation transit = requireTransitLocation(order);
        for (WmsTransferTask task : listTasks(id)) {
            WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
            inventoryService.addToLocation(task, detail, transit.getId(), transit.getLocationCode());
            detailMapper.updateById(detail);
        }
        applyExecStatus(order, WmsConstants.TRANSFER_EXEC_PENDING_INBOUND);
        updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder putaway(String id, Map<String, String> locationByDetailId) {
        WmsTransferOrder order = requireOrder(id);
        requireSameMode(order, false);
        requireExec(order, WmsConstants.TRANSFER_EXEC_PENDING_INBOUND, "只有在「待入库」的跨仓单可上架确认");
        WmsLocation transit = requireTransitLocation(order);
        for (WmsTransferTask task : listTasks(id)) {
            WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
            String targetId = locationByDetailId == null ? null : locationByDetailId.get(detail.getId());
            if (oConvertUtils.isEmpty(targetId) || targetId.equals(transit.getId())) {
                // 未指定目标库位：暂存库位即最终库位（后续人工移库）
                continue;
            }
            WmsLocation target = locationMapper.selectById(targetId);
            if (target == null) {
                throw new RuntimeException("目标库位不存在：" + targetId);
            }
            inventoryService.deductFromLocation(detail, transit.getId(), nvl(task.getAllocatedQty()));
            inventoryService.addToLocation(task, detail, target.getId(), target.getLocationCode());
            detailMapper.updateById(detail);
        }
        completePhysical(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder close(String id, String reason) {
        WmsTransferOrder order = requireOrder(id);
        if (statusResolver.isTerminal(order.getStatus())) {
            throw new RuntimeException("调拨单已是终态（" + order.getStatus() + "），不能关单");
        }
        if (oConvertUtils.isEmpty(reason)) {
            throw new RuntimeException("关单原因必填");
        }
        for (WmsTransferTask task : listTasks(id)) {
            if (!stockMoved(order)) {
                inventoryService.release(task);
            }
            rmsGateway.cancelTransfer(task.getTaskNo(), task.getContainerCode());
            task.setTaskStatus(WmsConstants.TASK_STATUS_CLOSED).setUpdateTime(new Date());
            taskMapper.updateById(task);
        }
        statusResolver.assertTransition(order.getStatus(), WmsConstants.TRANSFER_STATUS_CLOSED);
        order.setStatus(WmsConstants.TRANSFER_STATUS_CLOSED)
                .setCloseReason(reason)
                .setCloseBy(currentOperator())
                .setCloseTime(new Date());
        updateById(order);
        return order;
    }

    // ==================== 设备回执 ====================

    @Override
    public boolean isTransferTask(String taskNo) {
        return findTaskByTaskNo(taskNo) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean onTaskFeedback(String taskNo, String containerCode, String taskStatus, String message) {
        WmsTransferTask task = findTaskByTaskNo(taskNo);
        if (task == null) {
            return false;
        }
        WmsTransferOrder order = getById(task.getTransferId());
        if (order == null || statusResolver.isTerminal(order.getStatus())) {
            return true;
        }
        task.setFeedbackTime(new Date()).setUpdateTime(new Date());
        if (isDeviceFinished(taskStatus)) {
            task.setTaskStatus(WmsConstants.TASK_STATUS_FINISHED);
            taskMapper.updateById(task);
            tryCompleteSameWarehouse(order);
        } else if (isDeviceFailed(taskStatus)) {
            task.setTaskStatus(WmsConstants.TASK_STATUS_FAILED).setTaskError(truncate(message));
            taskMapper.updateById(task);
            applyExecStatus(order, WmsConstants.TRANSFER_EXEC_EXCEPTION);
            updateById(order);
            sendAlert(WmsConstants.TRANSFER_DEVICE_ALERT_TITLE,
                    "调拨单 " + order.getTransferNo() + " 设备任务失败：" + message);
        } else {
            task.setTaskStatus(WmsConstants.TASK_STATUS_EXECUTING);
            taskMapper.updateById(task);
        }
        return true;
    }

    /** 库存是否已按实际动作移动过（已扣减则锁定已释放，关单不能再释放）。 */
    private boolean stockMoved(WmsTransferOrder order) {
        String exec = order.getExecStatus();
        return WmsConstants.TRANSFER_EXEC_IN_TRANSIT.equals(exec)
                || WmsConstants.TRANSFER_EXEC_PENDING_INBOUND.equals(exec)
                || WmsConstants.TRANSFER_EXEC_COMPLETED.equals(exec);
    }

    /** 设备回执状态：兼容 RMS 原始值（FINISHED）与 WMS 内部值（已完成）。 */
    private boolean isDeviceFinished(String taskStatus) {
        return WmsConstants.DEVICE_STATUS_FINISHED.equalsIgnoreCase(taskStatus)
                || WmsConstants.TASK_STATUS_FINISHED.equals(taskStatus);
    }

    /** 设备失败回执。 */
    private boolean isDeviceFailed(String taskStatus) {
        return WmsConstants.DEVICE_STATUS_FAILED.equalsIgnoreCase(taskStatus)
                || WmsConstants.TASK_STATUS_FAILED.equals(taskStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int onContainerLocation(String containerCode, String stackCode) {
        if (oConvertUtils.isEmpty(containerCode) || oConvertUtils.isEmpty(stackCode)) {
            return 0;
        }
        List<WmsTransferTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<WmsTransferTask>()
                .eq(WmsTransferTask::getContainerCode, containerCode)
                .ne(WmsTransferTask::getTaskStatus, WmsConstants.TASK_STATUS_CLOSED));
        if (tasks.isEmpty()) {
            return 0;
        }
        WmsLocation location = findLocationByCode(stackCode);
        if (location == null) {
            log.warn("容器[{}]位置回调的库位编码不存在：{}", containerCode, stackCode);
            return 0;
        }
        int affected = 0;
        for (WmsTransferTask task : tasks) {
            WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
            if (detail == null) {
                continue;
            }
            detail.setInLocationId(location.getId())
                    .setInLocationCode(location.getLocationCode())
                    .setUpdateTime(new Date());
            detailMapper.updateById(detail);
            affected++;
        }
        return affected;
    }

    // ==================== Phase B 占位 ====================

    @Override
    public WmsTransferOrder syncErp(String id) {
        WmsTransferOrder order = requireOrder(id);
        if (WmsConstants.TRANSFER_MODE_SAME.equals(order.getTransferMode())) {
            throw new RuntimeException("同仓区域搬运是 WMS 内部库位移动，不生成金蝶调拨单（只有跨仓调拨回写 ERP）");
        }
        if (statusResolver.isTerminal(order.getStatus())) {
            throw new RuntimeException("调拨单已是终态（" + order.getStatus() + "），无需再同步");
        }
        if (!WmsConstants.TRANSFER_EXEC_COMPLETED.equals(order.getExecStatus())) {
            throw new RuntimeException("实物尚未完成，暂不能提交金蝶（当前实物状态：" + order.getExecStatus() + "）");
        }
        // 已提交过 -> 只回读状态；否则提交（含重试）
        if (WmsConstants.TRANSFER_SYNC_SUBMITTED.equals(order.getSyncStatus())) {
            transferSyncService.pollStatus();
        } else {
            transferSyncService.submitPending();
        }
        return requireOrder(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferOrder reverse(String id) {
        WmsTransferOrder source = requireOrder(id);
        if (!WmsConstants.TRANSFER_SYNC_REJECTED.equals(source.getSyncStatus())) {
            throw new RuntimeException("只有 ERP 驳回/作废的调拨单可生成反向调拨单（当前 ERP 状态："
                    + source.getSyncStatus() + "）");
        }
        if (oConvertUtils.isNotEmpty(source.getReverseTransferNo())) {
            throw new RuntimeException("该单已生成过反向调拨单：" + source.getReverseTransferNo());
        }
        // 反向单：调出/调入互换，明细数量取实发数量；执行流程与原单一致
        WmsTransferCreateDTO dto = new WmsTransferCreateDTO();
        dto.setOutWarehouseId(source.getInWarehouseId());
        dto.setOutZoneCode(source.getInZoneCode());
        dto.setInWarehouseId(source.getOutWarehouseId());
        dto.setInZoneCode(source.getOutZoneCode());
        dto.setRemark("反向冲销：" + source.getTransferNo());
        List<WmsTransferCreateDTO.Detail> detailDTOs = new ArrayList<>();
        for (WmsTransferDetail detail : listDetails(id)) {
            WmsTransferCreateDTO.Detail item = new WmsTransferCreateDTO.Detail();
            item.setMaterialId(detail.getMaterialId());
            item.setBatchNo(detail.getBatchNo());
            item.setContainerCode(detail.getContainerCode());
            item.setUnit(detail.getUnit());
            item.setTransferQty(nvl(detail.getActualQty()).signum() > 0
                    ? detail.getActualQty() : detail.getTransferQty());
            item.setRemark("冲销原单第 " + detail.getLineNo() + " 行");
            detailDTOs.add(item);
        }
        dto.setDetails(detailDTOs);
        WmsTransferOrder reverseOrder = create(dto);

        source.setReverseTransferNo(reverseOrder.getTransferNo())
                .setRejectHandleStatus(WmsConstants.TRANSFER_REJECT_REVERSED);
        updateById(source);
        log.info("调拨单[{}]已生成反向冲销单[{}]", source.getTransferNo(), reverseOrder.getTransferNo());
        return reverseOrder;
    }

    // ==================== 内部方法 ====================

    /** 同仓：全部任务完成后扣源库 + 落目标库位（位置回调给 in_location）。 */
    private void tryCompleteSameWarehouse(WmsTransferOrder order) {
        if (!WmsConstants.TRANSFER_MODE_SAME.equals(order.getTransferMode())) {
            return;
        }
        List<WmsTransferTask> tasks = listTasks(order.getId());
        boolean allFinished = tasks.stream()
                .allMatch(t -> WmsConstants.TASK_STATUS_FINISHED.equals(t.getTaskStatus()));
        if (!allFinished || tasks.isEmpty()) {
            applyExecStatus(order, WmsConstants.TRANSFER_EXEC_RUNNING);
            updateById(order);
            return;
        }
        // 先校验库位回执：没有目标库位就不能移动库存，否则账实不符
        for (WmsTransferTask task : tasks) {
            WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
            if (detail == null || oConvertUtils.isEmpty(detail.getInLocationId())) {
                applyExecStatus(order, WmsConstants.TRANSFER_EXEC_EXCEPTION);
                updateById(order);
                sendAlert(WmsConstants.TRANSFER_DEVICE_ALERT_TITLE,
                        "调拨单 " + order.getTransferNo() + " 已收到完成回执但缺少库位回执（container-location）");
                return;
            }
        }
        for (WmsTransferTask task : tasks) {
            WmsTransferDetail detail = detailMapper.selectById(task.getDetailId());
            inventoryService.deductSource(task);
            inventoryService.addToLocation(task, detail, detail.getInLocationId(), detail.getInLocationCode());
            detailMapper.updateById(detail);
        }
        completePhysical(order);
    }

    /** 实物完成：exec=实物完成，总状态按 ERP 时钟派生（Phase A 停在待ERP审核）。 */
    private void completePhysical(WmsTransferOrder order) {
        applyExecStatus(order, WmsConstants.TRANSFER_EXEC_COMPLETED);
        order.setExecFinishTime(new Date());
        order.setOperator(currentOperator());
        updateById(order);
    }

    /** 只改 exec_status 并按派生规则同步 status（含迁移守卫）。 */
    private void applyExecStatus(WmsTransferOrder order, String execStatus) {
        String nextStatus = statusResolver.deriveStatus(execStatus, order.getSyncStatus());
        statusResolver.assertTransition(order.getStatus(), nextStatus);
        order.setExecStatus(execStatus).setStatus(nextStatus);
    }

    private WmsTransferOrder requireOrder(String id) {
        WmsTransferOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("调拨单不存在");
        }
        return order;
    }

    private void requireStatus(WmsTransferOrder order, String expected, String message) {
        if (!expected.equals(order.getStatus())) {
            throw new RuntimeException(message + "（当前：" + order.getStatus() + "）");
        }
    }

    private void requireExec(WmsTransferOrder order, String expected, String message) {
        if (!expected.equals(order.getExecStatus())) {
            throw new RuntimeException(message + "（当前实物状态：" + order.getExecStatus() + "）");
        }
    }

    private void requireSameMode(WmsTransferOrder order, boolean same) {
        boolean isSame = WmsConstants.TRANSFER_MODE_SAME.equals(order.getTransferMode());
        if (isSame != same) {
            throw new RuntimeException(same ? "只有同仓区域调拨单支持该操作" : "只有跨仓调拨单支持该操作");
        }
    }

    private WmsWarehouse requireWarehouse(String id, String label) {
        WmsWarehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            throw new RuntimeException(label + "不存在：" + id);
        }
        return warehouse;
    }

    private WmsLocation requireTransitLocation(WmsTransferOrder order) {
        String transitId = oConvertUtils.isEmpty(order.getTransitLocationId())
                ? transitLocationId : order.getTransitLocationId();
        WmsLocation location = oConvertUtils.isEmpty(transitId) ? null : locationMapper.selectById(transitId);
        if (location == null) {
            throw new RuntimeException("在途暂存库位未配置或不存在（wms.transfer.transit-location-id）");
        }
        return location;
    }

    private WmsLocation findLocationByCode(String locationCode) {
        List<WmsLocation> rows = locationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getLocationCode, locationCode)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<WmsTransferDetail> listDetails(String transferId) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsTransferDetail>()
                .eq(WmsTransferDetail::getTransferId, transferId)
                .orderByAsc(WmsTransferDetail::getLineNo));
    }

    private List<WmsTransferTask> listTasks(String transferId) {
        return taskMapper.selectList(new LambdaQueryWrapper<WmsTransferTask>()
                .eq(WmsTransferTask::getTransferId, transferId)
                .orderByAsc(WmsTransferTask::getCreateTime));
    }

    private WmsTransferTask findTaskByTaskNo(String taskNo) {
        if (oConvertUtils.isEmpty(taskNo)) {
            return null;
        }
        List<WmsTransferTask> rows = taskMapper.selectList(new LambdaQueryWrapper<WmsTransferTask>()
                .eq(WmsTransferTask::getTaskNo, taskNo)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String currentOperator() {
        try {
            Object principal = org.apache.shiro.SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof org.jeecg.common.system.vo.LoginUser) {
                return ((org.jeecg.common.system.vo.LoginUser) principal).getUsername();
            }
        } catch (Exception e) {
            log.debug("取当前用户失败，回退 system：{}", e.getMessage());
        }
        return "system";
    }

    private void sendAlert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    oConvertUtils.isEmpty(alertToUser) ? WmsConstants.ALERT_TO_USER : alertToUser, title, content));
        } catch (Exception e) {
            log.error("调拨告警投递失败：{}", e.getMessage(), e);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
