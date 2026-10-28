package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWarningHandleDTO;
import org.jeecg.modules.wms.entity.WmsInventoryWarning;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWarningRule;
import org.jeecg.modules.wms.mapper.WmsInventoryWarningMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsWarningRuleMapper;
import org.jeecg.modules.wms.service.IWmsInventoryWarningService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.jeecg.modules.wms.vo.WmsMaterialAvailableVO;
import org.jeecg.modules.wms.vo.WmsWarningScanResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Description: 库存预警扫描与处理。
 *
 * <p>合并提醒（spec 2026-10-28 D6）：同 {@code dedupKey} 且 {@code PENDING} 只保留一条，
 * 再次命中只累加 {@code hitCount}、刷新 {@code currentQty}；解除后 dedupKey 追加
 * {@code #R#时间戳}，不再参与合并。
 *
 * <p>预警**不自动解除**（D7）：恢复与否都要人工处理留痕；处理也**不改库存**（D9），
 * 补料/清理走各自的入库/出库/盘点流程。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsInventoryWarningServiceImpl implements IWmsInventoryWarningService {

    private static final int REMARK_MAX_LENGTH = 500;
    private static final int HANDLE_TYPE_MAX_LENGTH = 20;

    private final WmsWarningRuleMapper ruleMapper;
    private final WmsInventoryWarningMapper warningMapper;
    private final WmsMaterialMapper materialMapper;
    private final IWmsSyncLogService syncLogService;

    public WmsInventoryWarningServiceImpl(WmsWarningRuleMapper ruleMapper,
                                          WmsInventoryWarningMapper warningMapper,
                                          WmsMaterialMapper materialMapper,
                                          IWmsSyncLogService syncLogService) {
        this.ruleMapper = ruleMapper;
        this.warningMapper = warningMapper;
        this.materialMapper = materialMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsWarningScanResultVO scan() {
        long start = System.currentTimeMillis();
        WmsWarningScanResultVO vo = new WmsWarningScanResultVO();
        List<WmsWarningRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<WmsWarningRule>()
                .eq(WmsWarningRule::getEnabled, 1));
        vo.setRules(rules.size());
        if (rules.isEmpty()) {
            vo.setMessage("没有启用的预警规则，跳过");
            return vo;
        }

        AtomicInteger seq = new AtomicInteger();
        for (WmsWarningRule rule : rules) {
            try {
                if (WmsConstants.WARNING_TYPE_LOW_STOCK.equals(rule.getRuleType())) {
                    scanLowStock(rule, vo, seq);
                } else if (WmsConstants.WARNING_TYPE_OVERDUE.equals(rule.getRuleType())) {
                    scanOverdue(rule, vo, seq);
                } else {
                    log.warn("规则[{}]类型[{}]无法识别，跳过", rule.getRuleName(), rule.getRuleType());
                }
            } catch (Exception e) {
                // 单条规则失败不影响其它规则
                log.error("规则[{}]扫描失败：{}", rule.getRuleName(), e.getMessage(), e);
            }
        }

        vo.setMessage(String.format("扫描 %d 条规则，命中 %d，新建 %d，合并 %d",
                vo.getRules(), vo.getScanned(), vo.getCreated(), vo.getMerged()));
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_WMS_INTERNAL,
                WmsConstants.SYNC_LOG_TYPE_INVENTORY_WARNING, null,
                WmsConstants.SYNC_LOG_TARGET_WMS, true, 0,
                null, vo.getMessage(), null, System.currentTimeMillis() - start);
        log.info("库存预警扫描完成：{}", vo.getMessage());
        return vo;
    }

    // ------------------------------------------------------------ 库存下限

    private void scanLowStock(WmsWarningRule rule, WmsWarningScanResultVO vo, AtomicInteger seq) {
        BigDecimal threshold = rule.getThresholdQty();
        if (threshold == null) {
            return;
        }
        // 一次聚合取全部物料的可用量，作用域在内存里筛（库存表规模远小于「物料×库位」组合）
        Map<String, WmsMaterialAvailableVO> available =
                toMap(warningMapper.selectAvailableByMaterial(null, null, rule.getWarehouseId()));

        List<WmsMaterialAvailableVO> candidates = new ArrayList<>();
        if (WmsConstants.WARNING_SCOPE_MATERIAL.equals(rule.getScopeType())) {
            candidates.add(candidateOf(rule.getMaterialId(), available));
        } else if (WmsConstants.WARNING_SCOPE_CATEGORY.equals(rule.getScopeType())) {
            List<WmsMaterial> materials = materialMapper.selectList(new LambdaQueryWrapper<WmsMaterial>()
                    .eq(WmsMaterial::getCategoryId, rule.getCategoryId()));
            for (WmsMaterial material : materials) {
                candidates.add(candidateOf(material.getId(), available));
            }
        } else {
            // ALL：只盯「当前有库存记录」的物料；否则会把全库几万个零库存物料全报一遍
            candidates.addAll(available.values());
        }

        for (WmsMaterialAvailableVO candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            BigDecimal qty = candidate.getAvailableQty() == null
                    ? BigDecimal.ZERO : candidate.getAvailableQty();
            if (qty.compareTo(threshold) >= 0) {
                continue;
            }
            recordHit(rule, vo, seq, candidate.getMaterialId(), candidate.getMaterialCode(),
                    candidate.getMaterialName(), rule.getWarehouseId(), null, null, null, null,
                    qty, null, null);
        }
    }

    /** 取候选物料；聚合结果里没有（零库存）时回查主数据，库存记为 0。 */
    private WmsMaterialAvailableVO candidateOf(String materialId,
                                               Map<String, WmsMaterialAvailableVO> available) {
        if (!StringUtils.hasText(materialId)) {
            return null;
        }
        WmsMaterialAvailableVO hit = available.get(materialId);
        if (hit != null) {
            return hit;
        }
        WmsMaterialAvailableVO snapshot = warningMapper.selectMaterialSnapshot(materialId);
        if (snapshot != null && snapshot.getAvailableQty() == null) {
            snapshot.setAvailableQty(BigDecimal.ZERO);
        }
        return snapshot;
    }

    // ------------------------------------------------------------ 存放超期

    private void scanOverdue(WmsWarningRule rule, WmsWarningScanResultVO vo, AtomicInteger seq) {
        Integer days = rule.getOverdueDays();
        if (days == null) {
            return;
        }
        List<WmsInventoryStockRowVO> rows = warningMapper.selectOverdueRows(days,
                rule.getMaterialId(), rule.getCategoryId(), rule.getWarehouseId(),
                WmsConstants.WARNING_SCAN_ROW_LIMIT);
        for (WmsInventoryStockRowVO row : rows) {
            recordHit(rule, vo, seq, row.getMaterialId(), row.getMaterialCode(), row.getMaterialName(),
                    row.getWarehouseId(), row.getLocationId(), row.getLocationCode(),
                    row.getBatchNo(), row.getContainerCode(),
                    row.getQuantity(), days, row.getFirstInboundTime());
        }
    }

    // ------------------------------------------------------------ 生成 / 合并

    private void recordHit(WmsWarningRule rule, WmsWarningScanResultVO vo, AtomicInteger seq,
                           String materialId, String materialCode, String materialName,
                           String warehouseId, String locationId, String locationCode,
                           String batchNo, String containerCode,
                           BigDecimal currentQty, Integer overdueDays, Date firstInboundTime) {
        String dedupKey = dedupKey(rule.getId(), materialId, locationId, batchNo, containerCode);
        Date now = new Date();
        WmsInventoryWarning pending = warningMapper.selectOne(new LambdaQueryWrapper<WmsInventoryWarning>()
                .eq(WmsInventoryWarning::getDedupKey, dedupKey)
                .eq(WmsInventoryWarning::getStatus, WmsConstants.WARNING_STATUS_PENDING)
                .last("LIMIT 1"));

        if (pending != null) {
            // 合并：只刷新数量与命中记录，不新建、不改单号
            pending.setCurrentQty(currentQty);
            pending.setOverdueDays(overdueDays);
            pending.setFirstInboundTime(firstInboundTime);
            pending.setHitCount(pending.getHitCount() == null ? 2 : pending.getHitCount() + 1);
            pending.setLastHitTime(now);
            warningMapper.updateById(pending);
            vo.addMerged();
            return;
        }

        WmsInventoryWarning warning = new WmsInventoryWarning();
        warning.setWarningNo(nextWarningNo(now, seq));
        warning.setRuleId(rule.getId());
        warning.setRuleName(rule.getRuleName());
        warning.setRuleType(rule.getRuleType());
        warning.setMaterialId(materialId);
        warning.setMaterialCode(materialCode);
        warning.setMaterialName(materialName);
        warning.setWarehouseId(warehouseId);
        warning.setLocationId(locationId);
        warning.setLocationCode(locationCode);
        warning.setBatchNo(batchNo);
        warning.setContainerCode(containerCode);
        warning.setCurrentQty(currentQty == null ? BigDecimal.ZERO : currentQty);
        warning.setThresholdQty(rule.getThresholdQty());
        warning.setOverdueDays(overdueDays);
        warning.setFirstInboundTime(firstInboundTime);
        warning.setStatus(WmsConstants.WARNING_STATUS_PENDING);
        warning.setDedupKey(dedupKey);
        warning.setHitCount(1);
        warning.setLastHitTime(now);
        warning.setCreateBy("system");
        warning.setCreateTime(now);
        warningMapper.insert(warning);
        vo.addCreated();
    }

    /**
     * 合并键：规则 + 物料 + 库位 + 批次 + 容器。
     *
     * <p>容器必须入键——`wms_inventory` 的唯一键含 `container_code`，同库位同批次可能有多箱。
     */
    private String dedupKey(String ruleId, String materialId, String locationId,
                            String batchNo, String containerCode) {
        return nvl(ruleId) + "|" + nvl(materialId) + "|" + nvl(locationId)
                + "|" + nvl(batchNo) + "|" + nvl(containerCode);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 单号：WRN + yyyyMMddHHmmssSSS + 2 位序号（同毫秒内多条不撞）。 */
    private String nextWarningNo(Date now, AtomicInteger seq) {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(now);
        return WmsConstants.WARNING_NO_PREFIX + stamp + String.format("%02d", seq.incrementAndGet() % 100);
    }

    private Map<String, WmsMaterialAvailableVO> toMap(List<WmsMaterialAvailableVO> rows) {
        Map<String, WmsMaterialAvailableVO> map = new HashMap<>();
        for (WmsMaterialAvailableVO row : rows) {
            if (row.getMaterialId() != null) {
                map.put(row.getMaterialId(), row);
            }
        }
        return map;
    }

    // ------------------------------------------------------------ 处理

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(WmsWarningHandleDTO dto, String operator) {
        if (dto == null || !StringUtils.hasText(dto.getId())) {
            throw new RuntimeException("预警ID不能为空");
        }
        WmsInventoryWarning warning = warningMapper.selectById(dto.getId());
        if (warning == null) {
            throw new RuntimeException("预警不存在或已删除");
        }
        if (!WmsConstants.WARNING_STATUS_PENDING.equals(warning.getStatus())) {
            throw new RuntimeException("该预警已处理，请勿重复操作");
        }
        String handleType = dto.getHandleType() == null ? "" : dto.getHandleType().trim();
        if (handleType.length() > HANDLE_TYPE_MAX_LENGTH
                || !(WmsConstants.WARNING_HANDLE_REPLENISH.equals(handleType)
                || WmsConstants.WARNING_HANDLE_CLEAR.equals(handleType)
                || WmsConstants.WARNING_HANDLE_OTHER.equals(handleType))) {
            throw new RuntimeException("处理方式必须是 REPLENISH（补料）、CLEAR（清理）或 OTHER（其他）");
        }
        String remark = dto.getHandleRemark() == null ? "" : dto.getHandleRemark().trim();
        if (remark.isEmpty()) {
            throw new RuntimeException("处理说明不能为空（留痕审计）");
        }
        if (remark.length() > REMARK_MAX_LENGTH) {
            throw new RuntimeException("处理说明不能超过 " + REMARK_MAX_LENGTH + " 个字符");
        }

        Date now = new Date();
        warning.setStatus(WmsConstants.WARNING_STATUS_RESOLVED);
        warning.setHandleType(handleType);
        warning.setHandleRemark(remark);
        warning.setHandledBy(StringUtils.hasText(operator) ? operator : "system");
        warning.setHandleTime(now);
        // 解除后退出合并池：下一轮再命中会新建一条，而不是把已解除的记录改回待处理
        warning.setDedupKey(warning.getDedupKey() + WmsConstants.WARNING_DEDUP_RESOLVED_SEP
                + now.getTime());
        warningMapper.updateById(warning);
        log.info("预警[{}]已由[{}]处理：{} {}", warning.getWarningNo(), warning.getHandledBy(),
                handleType, remark);
    }
}
