package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsOperationReportDraftService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeOperationReportReader;
import org.jeecg.modules.wms.vo.WmsOperationReportRowVO;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @Description: 工序汇报 → WMS 半成品入库**草稿**（阶段 C）。
 *
 * <p>口径（2026-10-28 用户明确）：
 * <ul>
 *   <li>半成品 = 工序汇报产生的库存，所以 productType 固定「半成品」；</li>
 *   <li>只落草稿：不分配库位、不下发 WCS、不回写金蝶；</li>
 *   <li>`FStockId` 为空（只汇报未入库）、外部仓（CK007）、物料不存在 → 跳过并给可通过日志排查的原因。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsOperationReportDraftServiceImpl implements IWmsOperationReportDraftService {

    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final String SYNC_OBJECT_TYPE = "OPERATION_REPORT";

    private final WmsKingdeeOperationReportReader reader;
    private final WmsProductionInboundMapper inboundMapper;
    private final WmsMaterialMapper materialMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final IWmsSyncLogService syncLogService;

    /** 可选依赖：既有单测按旧构造器 new 服务，注入不到时为 null，落点走 WmsAuditRecorder 自动跳过。 */
    @Autowired(required = false)
    private IWmsAuditLogService auditLogService;

    public WmsOperationReportDraftServiceImpl(WmsKingdeeOperationReportReader reader,
                                              WmsProductionInboundMapper inboundMapper,
                                              WmsMaterialMapper materialMapper,
                                              WmsWarehouseMapper warehouseMapper,
                                              IWmsSyncLogService syncLogService) {
        this.reader = reader;
        this.inboundMapper = inboundMapper;
        this.materialMapper = materialMapper;
        this.warehouseMapper = warehouseMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOperationReportSyncResultVO ingest(String startDate, String endDate, Integer maxRows) {
        long start = System.currentTimeMillis();
        int limit = maxRows == null || maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        WmsOperationReportSyncResultVO vo = new WmsOperationReportSyncResultVO();

        List<WmsOperationReportRowVO> rows = reader.fetch(startDate, endDate, limit);
        vo.setFetched(rows.size());
        for (WmsOperationReportRowVO row : rows) {
            try {
                apply(row, vo);
            } catch (Exception e) {
                // 单行失败不拖垮整批：草稿阶段以「看得见原因」优先
                vo.skip(row.getBillNo() + "：" + e.getMessage());
            }
        }
        String message = String.format("工序汇报：拉取 %d 行，新建草稿 %d，更新 %d，跳过 %d",
                vo.getFetched(), vo.getCreated(), vo.getUpdated(), vo.getSkipped());
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                null, WmsConstants.SYNC_LOG_TARGET_WMS, true, 0,
                null, message, null, System.currentTimeMillis() - start);
        log.info(message);
        return vo;
    }

    private void apply(WmsOperationReportRowVO row, WmsOperationReportSyncResultVO vo) {
        if (row.getQualifiedQty() == null || row.getQualifiedQty().signum() <= 0) {
            vo.skip(row.getBillNo() + "：合格数量为 0");
            return;
        }
        if (row.getStockNumber() == null || row.getStockNumber().trim().isEmpty()) {
            // 只汇报未指定入库仓 = 货还在车间，不进 WMS 库存
            vo.skip(row.getBillNo() + "：未指定入库仓库（只汇报未入库）");
            return;
        }
        String stockNumber = row.getStockNumber().trim();
        WmsWarehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseCode, stockNumber)
                .last("LIMIT 1"));
        if (warehouse != null && WmsConstants.WAREHOUSE_TYPE_EXTERNAL.equals(warehouse.getWarehouseType())) {
            vo.skip(row.getBillNo() + "：入库仓 " + stockNumber + " 是外部仓（VMI），不在 WMS 记账");
            return;
        }
        WmsMaterial material = materialMapper.selectOne(new LambdaQueryWrapper<WmsMaterial>()
                .eq(WmsMaterial::getMaterialCode, row.getMaterialCode())
                .last("LIMIT 1"));
        if (material == null) {
            vo.skip(row.getBillNo() + "：物料 " + row.getMaterialCode() + " 不在 WMS 主数据");
            return;
        }

        String entryKey = entryKey(row);
        WmsProductionInbound existing = inboundMapper.selectOne(new LambdaQueryWrapper<WmsProductionInbound>()
                .eq(WmsProductionInbound::getErpEntryKey, entryKey)
                .last("LIMIT 1"));
        Date now = new Date();
        if (existing != null) {
            // 草稿阶段允许刷新数量；已进入后续状态的单据不动，避免覆盖现场作业
            if (!WmsConstants.PROD_STATUS_DRAFT.equals(existing.getStatus())) {
                vo.skip(row.getBillNo() + "：草稿已推进到「" + existing.getStatus() + "」，跳过更新");
                return;
            }
            existing.setQuantity(row.getQualifiedQty());
            existing.setGoodQty(row.getQualifiedQty());
            existing.setBatchNo(resolveBatch(row));
            existing.setUpdateTime(now);
            inboundMapper.updateById(existing);
            vo.setUpdated(vo.getUpdated() + 1);
            WmsAuditRecorder.record(auditLogService, reportEvent(entryKey, existing, existing, row));
            return;
        }

        WmsProductionInbound created = new WmsProductionInbound();
        created.setInboundNo(inboundNo(row));
        created.setWorkOrderNo(row.getMoNumber());
        created.setProductType(WmsConstants.PRODUCT_TYPE_SEMI);
        created.setMaterialId(material.getId());
        created.setMaterialCode(row.getMaterialCode());
        created.setMaterialName(row.getMaterialName());
        created.setBatchNo(resolveBatch(row));
        created.setQuantity(row.getQualifiedQty());
        created.setGoodQty(row.getQualifiedQty());
        created.setBadQty(BigDecimal.ZERO);
        created.setStatus(WmsConstants.PROD_STATUS_DRAFT);
        created.setIdentType(WmsConstants.IDENT_TYPE_RFID);
        created.setErpBillNo(row.getBillNo());
        created.setErpEntryKey(entryKey);
        created.setManualRemark("工序汇报草稿：入库仓 " + stockNumber
                + (row.getStockName() == null ? "" : " " + row.getStockName())
                + (row.getWorkshopName() == null ? "" : "；车间 " + row.getWorkshopName()));
        created.setCreateTime(now);
        created.setUpdateTime(now);
        inboundMapper.insert(created);
        vo.setCreated(vo.getCreated() + 1);
        WmsAuditRecorder.record(auditLogService, reportEvent(entryKey, null, created, row));
    }

    /** 工序汇报的审计事件：biz_no 用「单号#序列#工序号」，与幂等键同源，事后按它反查。 */
    private WmsAuditEvent reportEvent(String entryKey, WmsProductionInbound before,
                                      WmsProductionInbound after, WmsOperationReportRowVO row) {
        return new WmsAuditEvent()
                .setBizType(WmsConstants.AUDIT_BIZ_INBOUND)
                .setBizNo(entryKey)
                .setBizRef(row.getMoNumber())
                .setAction(WmsConstants.AUDIT_ACTION_SYNC_IN)
                .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_SYNC)
                .setBeforeValue(WmsAuditRecorder.snapshot(auditLogService, before,
                        "inboundNo", "quantity", "goodQty", "batchNo", "status"))
                .setAfterValue(WmsAuditRecorder.snapshot(auditLogService, after,
                        "inboundNo", "quantity", "goodQty", "batchNo", "status", "erpBillNo"))
                .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                .setResult(WmsConstants.AUDIT_RESULT_SUCCESS);
    }

    /** 幂等键：单号#工序序列#工序号（一张 GXHB 有多个分录，仅用单号会互相覆盖）。 */
    static String entryKey(WmsOperationReportRowVO row) {
        return row.getBillNo() + "#" + nvl(row.getSeq()) + "#" + nvl(row.getOperNumber());
    }

    /** WMS 单号：GXHB 单号 + 序列 + 工序号，便于反查。 */
    static String inboundNo(WmsOperationReportRowVO row) {
        String no = row.getBillNo() + "-" + nvl(row.getSeq()) + "-" + nvl(row.getOperNumber());
        return no.length() > 64 ? no.substring(0, 64) : no;
    }

    private String resolveBatch(WmsOperationReportRowVO row) {
        String lot = row.getLot() == null ? "" : row.getLot().trim();
        return lot.isEmpty() || "0".equals(lot) ? "KD-" + row.getBillNo() : lot;
    }

    private static String nvl(String value) {
        return value == null ? "" : value.trim();
    }
}
