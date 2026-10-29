package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsProductionManualCompleteDTO;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @Description: 生产入库主流程（RK-002）：提交、回执、转人工、关单与同步支撑。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsProductionInboundServiceImpl
        extends ServiceImpl<WmsProductionInboundMapper, WmsProductionInbound>
        implements IWmsProductionInboundService {

    @Autowired
    private WmsWorkOrderMapper workOrderMapper;

    @Autowired
    private WmsProductionInboundMapper inboundMapper;

    @Autowired
    private WmsLocationMapper locationMapper;

    /** 盘点重盘标记（可空：盘点模块可能尚未参与）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IWmsStocktakeService stocktakeService;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Autowired
    private WmsInventoryMapper inventoryMapper;

    @Autowired
    private IWmsLocationService locationService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsSystemConfigService systemConfigService;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsProductionInbound submit(WmsProductionSubmitDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        // 工单行锁：同一工单的并发提交串行化，避免两条同时越过计划量
        WmsWorkOrder order = workOrderMapper.selectByNoForUpdate(dto.getWorkOrderNo().trim());
        if (order == null) {
            throw new RuntimeException("工单号无效");
        }
        if (!WmsConstants.WORK_ORDER_STATUS_RELEASED.equals(order.getWorkOrderStatus())
                && !WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS.equals(order.getWorkOrderStatus())
                && !WmsConstants.WORK_ORDER_STATUS_FINISHED.equals(order.getWorkOrderStatus())) {
            throw new RuntimeException("工单状态不允许入库");
        }

        BigDecimal good = nvl(dto.getGoodQty());
        BigDecimal bad = nvl(dto.getBadQty());
        if (good.signum() < 0 || bad.signum() < 0 || good.add(bad).signum() <= 0) {
            throw new RuntimeException("数量必须大于 0");
        }
        if (good.stripTrailingZeros().scale() > 3 || bad.stripTrailingZeros().scale() > 3) {
            throw new RuntimeException("数量精度不能超过 3 位小数");
        }
        if (good.add(bad).compareTo(new BigDecimal("999999999.999")) > 0) {
            throw new RuntimeException("数量超出可录入范围");
        }

        BigDecimal used = nvl(inboundMapper.sumInboundedQty(order.getWorkOrderNo()));
        BigDecimal plan = nvl(order.getPlanQty());
        if (used.add(good).add(bad).compareTo(plan) > 0) {
            throw new RuntimeException("数量超出工单范围");
        }

        String carrierCode = dto.getCarrierCode() == null ? null : dto.getCarrierCode().trim();
        if (oConvertUtils.isEmpty(carrierCode)) {
            throw new RuntimeException("载具码不能为空");
        }
        if (carrierCode.length() > 64) {
            throw new RuntimeException("载具码长度不能超过 64");
        }
        if (dto.getDefectRemark() != null && dto.getDefectRemark().length() > 500) {
            throw new RuntimeException("不良原因长度不能超过 500");
        }
        if (inboundMapper.selectActiveByCarrier(carrierCode) != null) {
            throw new RuntimeException("载具正在使用中");
        }

        // 产品类型按「入库来源」判定（2026-10-28 用户口径）：工单作业台提交的是**完工入库 → 成品**。
        // 工单本身不携带产品类型（金蝶没有这个概念），所以这里不能因为字段为空就拒绝入库。
        String productType = resolveProductType(order.getProductType());
        boolean semi = WmsConstants.PRODUCT_TYPE_SEMI.equals(productType);
        String identType = semi ? WmsConstants.IDENT_TYPE_RFID : WmsConstants.IDENT_TYPE_QRCODE;

        String locationId = null;
        if (good.signum() > 0) {
            locationId = semi ? allocateStereoLocation() : requireDefaultLocation(
                    WmsConstants.WAREHOUSE_TYPE_AGILE,
                    WmsConstants.LOCATION_CODE_AGILE_DEFAULT,
                    "敏捷仓未配置，请联系管理员");
            // 上架目标被盘点冻结时拦下（立库由 allocateStereoLocation 内部占用，同样要挡）
            WmsStocktakeFreezeGuard.assertNotFrozen(locationId, locationMapper);
        }
        String scrapLocationId = null;
        if (bad.signum() > 0) {
            scrapLocationId = resolveScrapLocationId();
            WmsStocktakeFreezeGuard.assertNotFrozen(scrapLocationId, locationMapper);
        }

        String batchNo = oConvertUtils.isEmpty(dto.getBatchNo()) ? order.getBatchNo() : dto.getBatchNo().trim();
        if (batchNo == null || batchNo.length() > 64) {
            throw new RuntimeException("批次号长度不能超过 64");
        }
        boolean hasGood = good.signum() > 0;
        WmsProductionInbound record = new WmsProductionInbound()
                .setInboundNo(nextNo(WmsConstants.INBOUND_NO_PREFIX_PRODUCTION))
                .setWorkOrderNo(order.getWorkOrderNo())
                .setProductType(productType)
                .setMaterialId(order.getMaterialId())
                .setMaterialCode(order.getMaterialCode())
                .setMaterialName(order.getMaterialName())
                .setBatchNo(batchNo)
                .setQuantity(good.add(bad))
                .setGoodQty(good)
                .setBadQty(bad)
                .setStatus(hasGood ? WmsConstants.PROD_STATUS_PENDING_PUTAWAY : WmsConstants.PROD_STATUS_INBOUND)
                .setCarrierCode(carrierCode)
                .setIdentType(identType)
                .setLocationId(locationId)
                .setScrapLocationId(scrapLocationId)
                .setWcsTaskNo(hasGood
                        ? nextNo(semi ? WmsConstants.TASK_NO_PREFIX_WCS : WmsConstants.TASK_NO_PREFIX_RMS)
                        : null)
                .setTaskStatus(hasGood
                        ? WmsConstants.TASK_STATUS_NOT_DISPATCHED : WmsConstants.TASK_STATUS_FINISHED)
                .setDefectRemark(dto.getDefectRemark())
                .setSyncStatus(hasGood
                        ? WmsConstants.SYNC_STATUS_NOT_REQUIRED : WmsConstants.SYNC_STATUS_PENDING)
                .setRetryCount(0)
                .setOperator(WmsUtils.currentUsername())
                .setSubmitTime(new Date())
                .setCompleteTime(hasGood ? null : new Date());
        record.setDeletedAt(0L);
        WmsUtils.fillCreate(record);
        try {
            save(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 活跃载具唯一索引兜底跨事务并发：同一载具只允许一条未完结记录
            if (e.getMessage() != null && e.getMessage().contains("uk_wms_prod_inbound_active_carrier")) {
                throw new RuntimeException("载具正在使用中");
            }
            throw e;
        }

        if (bad.signum() > 0) {
            // container_code 是 NOT NULL：不良品入库没有载具，必须显式给空串
            inventoryMapper.upsertInventory(new WmsInventory()
                    .setMaterialId(order.getMaterialId())
                    .setLocationId(scrapLocationId)
                    .setBatchNo(batchNo)
                    .setContainerCode("")
                    .setQuantity(bad));
        }
        log.info("生产入库提交：单号={}，工单={}，良品={}，不良品={}，状态={}",
                record.getInboundNo(), order.getWorkOrderNo(), good, bad, record.getStatus());
        return record;
    }

    @Override
    public WmsProductionInbound findByTaskNo(String taskNo) {
        if (oConvertUtils.isEmpty(taskNo)) {
            return null;
        }
        return inboundMapper.selectOne(Wrappers.<WmsProductionInbound>query()
                .eq("wcs_task_no", taskNo.trim()).last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean onTaskFeedback(String taskNo, String carrierCode, String taskStatus,
                                  String stackCode, String message, boolean rms) {
        if (oConvertUtils.isEmpty(taskNo)) {
            return false;
        }
        String status = taskStatus == null ? "" : taskStatus.trim().toUpperCase();
        if ("FINISHED".equals(status)) {
            // 条件认领：重复回执影响 0 行 → 不重复写库存
            int claimed = inboundMapper.claimFinished(taskNo.trim(), stackCode);
            if (claimed == 0) {
                log.info("任务[{}]重复回执或已处理，忽略", taskNo);
                return false;
            }
            WmsProductionInbound record = findByTaskNo(taskNo);
            if (record == null) {
                return false;
            }
            writeGoodsInventory(record);
            markRecheckIfFrozen(record);
            log.info("生产入库完成：单号={}，任务号={}，来源={}",
                    record.getInboundNo(), taskNo, rms ? "RMS" : "WCS");
            return true;
        }
        if ("RUNNING".equals(status) || "OUTSTATION".equals(status)) {
            inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("status", WmsConstants.PROD_STATUS_EXECUTING)
                    .set("task_status", WmsConstants.TASK_STATUS_EXECUTING)
                    .set("update_time", new Date())
                    .eq("wcs_task_no", taskNo.trim())
                    .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY, WmsConstants.PROD_STATUS_EXECUTING));
            return false;
        }
        if ("FAILED".equals(status)) {
            WmsProductionInbound record = findByTaskNo(taskNo);
            String error = oConvertUtils.isEmpty(message) ? "设备回执任务失败" : message;
            int affected = inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("status", WmsConstants.PROD_STATUS_EXCEPTION)
                    .set("task_status", WmsConstants.TASK_STATUS_FAILED)
                    .set("task_error", truncate(error))
                    .set("update_time", new Date())
                    .eq("wcs_task_no", taskNo.trim())
                    .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY,
                            WmsConstants.PROD_STATUS_EXECUTING, WmsConstants.PROD_STATUS_EXCEPTION));
            // 已入库/已关闭记录的重复 FAILED 回执不再告警，避免噪音
            if (affected > 0 && record != null) {
                alert(WmsConstants.PRODUCTION_DEVICE_ALERT_TITLE,
                        "入库单号：" + record.getInboundNo() + "；任务号：" + taskNo + "；原因：" + error);
            }
            return false;
        }
        log.warn("任务[{}]回执状态未知：{}", taskNo, taskStatus);
        return false;
    }

    @Override
    public int onContainerLocation(String containerCode, String stackCode) {
        if (oConvertUtils.isEmpty(containerCode) || oConvertUtils.isEmpty(stackCode)) {
            return 0;
        }
        // 载具可复用：只更新最近一条记录，避免篡改历史追溯
        return inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                .set("stack_code", stackCode)
                .set("update_time", new Date())
                .eq("carrier_code", containerCode.trim())
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualComplete(String id, WmsProductionManualCompleteDTO dto) {
        WmsProductionInbound record = getById(id);
        if (record == null) {
            throw new RuntimeException("生产入库记录不存在");
        }
        // 字段校验必须**先于**状态认领：认领之后再抛异常会留下「已入库」的半截状态
        // （虽然有事务兜底，也不该让用户看到「失败但单据已变」）
        String remark = dto == null || dto.getRemark() == null ? null : dto.getRemark().trim();
        if (remark != null && remark.length() > 500) {
            throw new RuntimeException("备注长度不能超过 500");
        }
        String carrier = dto == null || dto.getCarrierCode() == null
                ? null : dto.getCarrierCode().trim();
        if (carrier != null && carrier.length() > 64) {
            throw new RuntimeException("载具码长度不能超过 64");
        }
        // 条件认领：只有异常待人工能转人工完成，重复点击只有一个生效
        int affected = inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                .set("status", WmsConstants.PROD_STATUS_INBOUND)
                .set("task_status", WmsConstants.TASK_STATUS_FINISHED)
                .set("complete_time", new Date())
                .set("sync_status", WmsConstants.SYNC_STATUS_PENDING)
                .set("manual_remark", remark)
                .setSql("task_error = NULL")
                .set("update_time", new Date())
                .eq("id", id)
                .eq("status", WmsConstants.PROD_STATUS_EXCEPTION));
        if (affected == 0) {
            throw new RuntimeException("当前状态不允许转人工完成");
        }

        if (oConvertUtils.isNotEmpty(carrier) && !carrier.equals(record.getCarrierCode())) {
            if (inboundMapper.selectActiveByCarrier(carrier) != null) {
                throw new RuntimeException("载具正在使用中");
            }
            inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("carrier_code", carrier)
                    .set("update_time", new Date())
                    .eq("id", id));
            record.setCarrierCode(carrier);
        }

        writeGoodsInventory(record);
        log.info("生产入库转人工完成：单号={}，任务号={}", record.getInboundNo(), record.getWcsTaskNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(String id, String reason) {
        if (oConvertUtils.isEmpty(reason == null ? null : reason.trim())) {
            throw new RuntimeException("关单原因不能为空");
        }
        WmsProductionInbound record = getById(id);
        if (record == null) {
            throw new RuntimeException("生产入库记录不存在");
        }
        String trimmed = reason.trim();
        int affected = inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                .set("status", WmsConstants.PROD_STATUS_CLOSED)
                .set("task_status", WmsConstants.TASK_STATUS_CLOSED)
                .set("sync_status", WmsConstants.SYNC_STATUS_NOT_REQUIRED)
                .set("close_reason", trimmed)
                .set("close_by", WmsUtils.currentUsername())
                .set("close_time", new Date())
                .set("update_time", new Date())
                .eq("id", id)
                .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY, WmsConstants.PROD_STATUS_EXCEPTION));
        if (affected == 0) {
            throw new RuntimeException("当前状态不允许关单");
        }
        // 半成品释放立库库位；成品使用共享的敏捷仓在库位，不释放
        if (WmsConstants.PRODUCT_TYPE_SEMI.equals(record.getProductType())
                && oConvertUtils.isNotEmpty(record.getLocationId())) {
            locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                    .set("status", WmsConstants.LOCATION_STATUS_IDLE)
                    .set("update_time", new Date())
                    .eq("id", record.getLocationId())
                    .eq("status", WmsConstants.LOCATION_STATUS_OCCUPIED));
        }
        log.info("生产入库关单：单号={}，原因={}", record.getInboundNo(), trimmed);
    }

    /** 良品库存写入（入库完成/转人工完成共用）。 */
    /** 落账库位若正被盘点冻结，标重盘（在途任务不拒收，只提示重盘）。 */
    private void markRecheckIfFrozen(WmsProductionInbound record) {
        if (stocktakeService == null || record.getLocationId() == null) {
            return;
        }
        try {
            stocktakeService.markRecheck(record.getLocationId(), record.getMaterialId(),
                    record.getBatchNo(), record.getStackCode());
        } catch (Exception e) {
            log.warn("生产入库重盘标记失败（不影响落账）：{}", e.getMessage());
        }
    }

    private void writeGoodsInventory(WmsProductionInbound record) {
        if (record.getGoodQty() == null || record.getGoodQty().signum() <= 0
                || oConvertUtils.isEmpty(record.getLocationId())) {
            return;
        }
        // 成品敏捷仓按料箱记库存（CK-002 出库按容器分配）；半成品立库不区分容器
        String containerCode = WmsConstants.PRODUCT_TYPE_FINISHED.equals(record.getProductType())
                && oConvertUtils.isNotEmpty(record.getCarrierCode())
                ? record.getCarrierCode() : "";
        inventoryMapper.upsertInventory(new WmsInventory()
                .setMaterialId(record.getMaterialId())
                .setLocationId(record.getLocationId())
                .setBatchNo(record.getBatchNo())
                .setContainerCode(containerCode)
                .setQuantity(record.getGoodQty()));
    }

    // ------------------------------------------------------------ 查询

    @Override
    public List<WmsProductionInbound> listByWorkOrderNo(String workOrderNo) {
        return list(com.baomidou.mybatisplus.core.toolkit.Wrappers.<WmsProductionInbound>lambdaQuery()
                .eq(WmsProductionInbound::getWorkOrderNo, workOrderNo == null ? "" : workOrderNo.trim())
                .orderByDesc(WmsProductionInbound::getSubmitTime)
                .last("LIMIT 100"));
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<WmsProductionInbound> listPage(
            int pageNo, int pageSize, WmsProductionInbound query) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsProductionInbound> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (query != null) {
            if (oConvertUtils.isNotEmpty(query.getInboundNo())) {
                qw.like("inbound_no", query.getInboundNo().trim());
            }
            if (oConvertUtils.isNotEmpty(query.getWorkOrderNo())) {
                qw.eq("work_order_no", query.getWorkOrderNo().trim());
            }
            if (oConvertUtils.isNotEmpty(query.getProductType())) {
                qw.eq("product_type", query.getProductType());
            }
            if (oConvertUtils.isNotEmpty(query.getStatus())) {
                qw.eq("status", query.getStatus());
            }
            if (oConvertUtils.isNotEmpty(query.getTaskStatus())) {
                qw.eq("task_status", query.getTaskStatus());
            }
            if (oConvertUtils.isNotEmpty(query.getSyncStatus())) {
                qw.eq("sync_status", query.getSyncStatus());
            }
            if (oConvertUtils.isNotEmpty(query.getCarrierCode())) {
                qw.eq("carrier_code", query.getCarrierCode().trim());
            }
        }
        qw.orderByDesc("submit_time");
        com.baomidou.mybatisplus.core.metadata.IPage<WmsProductionInbound> result =
                page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize), qw);
        enrichLocationCodes(result.getRecords());
        return result;
    }

    @Override
    public WmsProductionInbound detail(String id) {
        WmsProductionInbound record = getById(id);
        if (record == null) {
            throw new RuntimeException("生产入库记录不存在");
        }
        if (oConvertUtils.isNotEmpty(record.getLocationId())) {
            WmsLocation location = locationMapper.selectById(record.getLocationId());
            if (location != null) {
                record.setLocationCode(location.getLocationCode());
            }
        }
        return record;
    }

    /** 列表补齐库位编码，避免前端展示雪花 ID。 */
    private void enrichLocationCodes(List<WmsProductionInbound> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.Set<String> ids = records.stream()
                .map(WmsProductionInbound::getLocationId)
                .filter(oConvertUtils::isNotEmpty)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        List<WmsLocation> locations = locationMapper.selectBatchIds(ids);
        if (locations == null) {
            return;
        }
        java.util.Map<String, String> codeById = locations.stream()
                .collect(java.util.stream.Collectors.toMap(WmsLocation::getId, WmsLocation::getLocationCode));
        for (WmsProductionInbound record : records) {
            if (oConvertUtils.isNotEmpty(record.getLocationId())) {
                record.setLocationCode(codeById.get(record.getLocationId()));
            }
        }
    }

    // ------------------------------------------------------------ 同步补偿支撑（Task 8）

    @Override
    public Date syncStaleBefore() {
        return new Date(System.currentTimeMillis()
                - WmsConstants.INBOUND_SYNC_STALE_MINUTES * 60_000L);
    }

    @Override
    public int claimForSync(String id, Date staleBefore) {
        return inboundMapper.claimForSync(id, staleBefore);
    }

    @Override
    public List<WmsProductionInbound> selectSyncCandidates(Date staleBefore, int limit) {
        return inboundMapper.selectSyncCandidates(staleBefore, limit);
    }

    @Override
    public void persistSyncResult(String id, org.jeecg.modules.wms.sync.WmsOrderSyncAttempt attempt) {
        UpdateWrapper<WmsProductionInbound> update = new UpdateWrapper<WmsProductionInbound>()
                .set("sync_status", attempt.isSuccess()
                        ? WmsConstants.SYNC_STATUS_SUCCESS : WmsConstants.SYNC_STATUS_FAILED)
                .set("retry_count", attempt.getRetryCount())
                .set("last_sync_error", attempt.isSuccess() ? null : truncate(attempt.getErrorMessage()))
                .set("last_sync_time", new Date())
                .set("update_time", new Date())
                .setSql(attempt.isSuccess()
                        ? "sync_fail_count = 0" : "sync_fail_count = sync_fail_count + 1")
                .eq("id", id);
        inboundMapper.update(null, update);
    }

    @Override
    public List<WmsProductionInbound> selectUndispatchedBefore(Date before, int limit) {
        return inboundMapper.selectUndispatchedBefore(before, limit);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    /** 半成品：按库位编码顺序抢占一个立库空闲库位。 */
    private String allocateStereoLocation() {
        List<WmsLocation> candidates = locationService.listFreeByWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO);
        if (candidates != null) {
            for (WmsLocation candidate : candidates) {
                int affected = locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                        .set("status", WmsConstants.LOCATION_STATUS_OCCUPIED)
                        .eq("id", candidate.getId())
                        .eq("status", WmsConstants.LOCATION_STATUS_IDLE));
                if (affected == 1) {
                    return candidate.getId();
                }
            }
        }
        alert(WmsConstants.PRODUCTION_LOCATION_ALERT_TITLE,
                "立库无空闲库位，生产入库提交被拒绝，请人工介入");
        throw new RuntimeException("立库库位不足，已告警");
    }

    /**
     * 不良品默认仓：优先读系统配置 {@code defect.warehouse.code}（BD-004），
     * 未配置或配置的库区/默认位不存在时回退到「废品仓 + FPC-DEFAULT」。
     */
    private String resolveScrapLocationId() {
        String configuredCode = systemConfigService.getString(
                WmsConfigKey.DEFECT_WAREHOUSE_CODE.getKey(), "");
        if (oConvertUtils.isNotEmpty(configuredCode)) {
            WmsWarehouse warehouse = warehouseMapper.selectOne(Wrappers.<WmsWarehouse>query()
                    .eq("warehouse_code", configuredCode.trim()).last("LIMIT 1"));
            if (warehouse != null) {
                WmsLocation location = locationMapper.selectOne(Wrappers.<WmsLocation>query()
                        .eq("warehouse_id", warehouse.getId())
                        .eq("location_code", WmsConstants.LOCATION_CODE_SCRAP_DEFAULT)
                        .last("LIMIT 1"));
                if (location != null) {
                    return location.getId();
                }
            }
            log.warn("系统配置 defect.warehouse.code={} 未找到对应库区或默认位，回退到废品仓默认位",
                    configuredCode);
        }
        return requireDefaultLocation(
                WmsConstants.WAREHOUSE_TYPE_SCRAP,
                WmsConstants.LOCATION_CODE_SCRAP_DEFAULT,
                "废品仓未配置，请联系管理员");
    }

    /** 按库区类型 + 默认库位编码取库位；缺失时抛业务异常。 */
    private String requireDefaultLocation(String warehouseType, String locationCode, String message) {
        WmsWarehouse warehouse = warehouseMapper.selectOne(Wrappers.<WmsWarehouse>query()
                .eq("warehouse_type", warehouseType).last("LIMIT 1"));
        if (warehouse == null) {
            throw new RuntimeException(message);
        }
        WmsLocation location = locationMapper.selectOne(Wrappers.<WmsLocation>query()
                .eq("warehouse_id", warehouse.getId())
                .eq("location_code", locationCode)
                .last("LIMIT 1"));
        if (location == null) {
            throw new RuntimeException(message);
        }
        return location.getId();
    }

    /** 站内告警；告警设施自身异常不得掩盖业务失败。 */
    protected void alert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(
                    WmsConstants.ALERT_FROM_USER, alertToUser, title, content));
        } catch (Exception e) {
            log.error("生产入库告警投递失败：{}", e.getMessage(), e);
        }
    }

    /** 生成业务单号：前缀 + 雪花 ID（多实例部署不撞号）。 */
    String nextNo(String prefix) {
        return prefix + com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    /**
     * 工单作业台提交入库时的产品类型判定。
     *
     * <p>口径（2026-10-28 用户明确）：金蝶里没有成品/半成品之分——**半成品是工序汇报产生的库存，
     * 成品是完工入库产生的库存**。工单作业台走的是完工入库，所以工单没带类型时按**成品**处理；
     * 显式写了「半成品」的（历史数据/特殊工单）沿用原值。
     */
    private String resolveProductType(String raw) {
        if (WmsConstants.PRODUCT_TYPE_SEMI.equals(raw)) {
            return WmsConstants.PRODUCT_TYPE_SEMI;
        }
        return WmsConstants.PRODUCT_TYPE_FINISHED;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
