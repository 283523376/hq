package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsSubcontractOrderSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsSubcontractSyncResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 委外订单（SUB_SUBREQORDER）→ 外协出库只读草稿。
 *
 * <p>两条硬约束（spec 2026-10-26 §5.1）：
 * <ul>
 *   <li>组织过滤片段未配置 → 不查询（测试账套里 200+ 张委外单属 org 100 历史组织）；</li>
 *   <li>草稿只承载订单头，不展开 BOM 子项——子项多为尚未纳入范围的原材料。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Service
public class WmsSubcontractOrderSyncServiceImpl implements IWmsSubcontractOrderSyncService {

    private static final String FORM_ID = "SUB_SUBREQORDER";
    private static final String FIELD_KEYS =
            "FBillNo,FDocumentStatus,FDocumentType,FDate,FMaterialId.FNumber,FQty,FPickMtrlStatus";
    /** FPickMtrlStatus 在查询结果中的下标。 */
    private static final int PICK_STATUS_INDEX = 6;
    /** 草稿单号前缀，避免与 WMS 自建出库单号撞号。 */
    private static final String OUTBOUND_NO_PREFIX = "WG";
    private static final String REQUIREMENT_SOURCE = "ERP委外订单";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsOutboundOrderMapper outboundOrderMapper;
    private final IWmsSyncLogService syncLogService;

    public WmsSubcontractOrderSyncServiceImpl(WmsKingdeeFormClient formClient,
                                              WmsKingdeeProperties properties,
                                              WmsOutboundOrderMapper outboundOrderMapper,
                                              IWmsSyncLogService syncLogService) {
        this.formClient = formClient;
        this.properties = properties;
        this.outboundOrderMapper = outboundOrderMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    public WmsSubcontractSyncResultVO syncFromKingdee(int top) {
        WmsSubcontractSyncResultVO vo = new WmsSubcontractSyncResultVO();
        if (!properties.isEnabled()) {
            vo.getMessages().add("金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
            return vo;
        }
        String orgFilter = properties.getSubcontract().getOrgFilter();
        if (!StringUtils.hasText(orgFilter)) {
            vo.getMessages().add("委外同步未配置组织过滤（外部依赖 E4），跳过");
            log.warn("委外订单同步跳过：subcontract.org-filter 未配置");
            return vo;
        }

        // 片段整体加括号：运维若配了含 OR 的多组织片段，不加括号会让 AND 只作用于最后一个条件
        String filter = "(" + orgFilter.trim() + ") AND FDocumentStatus='C'";
        long start = System.currentTimeMillis();
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, FIELD_KEYS, filter, top);
        if (!result.isSuccess()) {
            syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, "SUBCONTRACT", null,
                    WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, 0, filter, null, result.getMessage(),
                    System.currentTimeMillis() - start);
            throw new RuntimeException("金蝶委外订单查询失败：" + result.getMessage());
        }

        List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
        // ExecuteBillQuery 对这种单据是「一分录一行」，必须按单号聚合，否则同一张单会被
        // 反复 upsert（计数虚高），且任一分录领料完成就把整单置关闭（部分领料被当成整单完成）。
        Map<String, List<List<Object>>> byBill = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            if (row == null || row.isEmpty() || row.get(0) == null) {
                continue;
            }
            byBill.computeIfAbsent(asText(row.get(0)), k -> new ArrayList<>()).add(row);
        }
        vo.setQueried(byBill.size());
        String doneValue = properties.getSubcontract().getPickStatusDone();
        for (Map.Entry<String, List<List<Object>>> entry : byBill.entrySet()) {
            upsertDraft(entry.getKey(), entry.getValue(), doneValue, vo);
        }
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, "SUBCONTRACT", null,
                WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, filter,
                String.format("查询%d 新增%d 更新%d 作废%d", vo.getQueried(), vo.getCreated(),
                        vo.getUpdated(), vo.getVoided()),
                null, System.currentTimeMillis() - start);
        return vo;
    }

    private void upsertDraft(String billNo, List<List<Object>> rows, String doneValue,
                             WmsSubcontractSyncResultVO vo) {
        // 整单完成 = 所有分录都到「已领料」；部分完成仍是未完成
        boolean done = StringUtils.hasText(doneValue) && !rows.isEmpty()
                && rows.stream().allMatch(row -> row.size() > PICK_STATUS_INDEX
                        && doneValue.equals(asText(row.get(PICK_STATUS_INDEX))));

        WmsOutboundOrder existing = outboundOrderMapper.selectOne(
                new LambdaQueryWrapper<WmsOutboundOrder>()
                        .eq(WmsOutboundOrder::getErpSubOrderNo, billNo)
                        .last("limit 1"));
        if (existing == null) {
            if (done) {
                // 尚未建草稿即已领料完成：无需再建
                vo.getMessages().add(billNo + "：ERP 已领料完成，未生成草稿");
                return;
            }
            WmsOutboundOrder draft = new WmsOutboundOrder();
            draft.setOutboundNo(OUTBOUND_NO_PREFIX + billNo);
            draft.setOutboundType(WmsConstants.OUTBOUND_TYPE_SUBCONTRACT);
            draft.setStatus(WmsConstants.OUTBOUND_STATUS_DRAFT);
            draft.setRequirementSource(REQUIREMENT_SOURCE);
            draft.setErpSubOrderNo(billNo);
            draft.setOperator("SYSTEM");
            draft.setRemark("金蝶委外订单同步生成的只读草稿：本期不含明细，不拣货不回写");
            outboundOrderMapper.insert(draft);
            vo.setCreated(vo.getCreated() + 1);
            return;
        }
        if (done && !WmsConstants.OUTBOUND_STATUS_CLOSED.equals(existing.getStatus())) {
            existing.setStatus(WmsConstants.OUTBOUND_STATUS_CLOSED);
            existing.setCloseReason("ERP 已领料完成");
            existing.setCloseTime(new Date());
            outboundOrderMapper.updateById(existing);
            vo.setVoided(vo.getVoided() + 1);
            return;
        }
        if (!done) {
            outboundOrderMapper.updateById(existing);
            vo.setUpdated(vo.getUpdated() + 1);
        }
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
