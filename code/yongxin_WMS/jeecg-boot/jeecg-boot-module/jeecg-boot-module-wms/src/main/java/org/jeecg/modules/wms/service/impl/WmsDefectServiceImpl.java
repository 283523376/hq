package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDefectInboundDTO;
import org.jeecg.modules.wms.dto.WmsDefectRecordQueryDTO;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsDefectRecordMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.service.IWmsDefectService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeConsignedReturnWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsWarehouseCodeResolver;
import org.jeecg.modules.wms.vo.WmsDefectLedgerVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @Description: 不良品处置（XT-001）+ 两类不良来源与退货（RK-005）。
 *
 * <p>报废出库复用 {@code wms_outbound_order}（类型=报废出库），**一步到位**：
 * 建单 → 扣库存 → 已完成。废品仓是固定库位、无设备搬运，不走分配/复核那套。
 *
 * <p>报废是**减库存的不可逆动作**，所以：数量必须 ≤ 可用量、只允许废品仓的货、
 * 扣减影响行数为 0（并发抢走）时抛错让事务回滚。
 *
 * <p>RK-005（2026-11-04）扩展：
 * <ul>
 *   <li><b>生产不良</b>：入自有废品仓（与生产入库提交的不良落仓同一套库位解析）；</li>
 *   <li><b>受托不良</b>：来源单号必须等于受托入库单号——受托方不在记录里存，
 *       一律按 来源单号 → 入库单 → consignor_id 反查客户档案（Task 1 Ruling；V2.4 起为客户）；
 *       SCRAP 扣受托库存（物料+批次+受托方匹配）后入废品仓仍记 CONSIGNED，不转自有；
 *       RETURN 只登记（待处理），退货时聚合同来源单记录共用一张受托加工材料退料单。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Service
public class WmsDefectServiceImpl implements IWmsDefectService {

    /** 退货回写重试次数（含首次）：失败重试并告警，全部失败才抛错回滚。 */
    private static final int RETURN_MAX_ATTEMPTS = 3;

    private final WmsInventoryMapper inventoryMapper;
    private final WmsOutboundOrderMapper orderMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsProductionInboundMapper inboundMapper;
    private final WmsMaterialMapper materialMapper;
    private final IWmsAuditLogService auditLogService;
    private final WmsDefectRecordMapper defectRecordMapper;
    private final WmsInboundOrderMapper inboundOrderMapper;
    private final IWmsCustomerService customerService;
    private final WmsWarehouseCodeResolver warehouseResolver;
    private final IWmsKingdeeConsignedReturnWriter consignedReturnWriter;
    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final ISysBaseAPI sysBaseAPI;

    /** 告警收件人（登录账号），可用 {@code wms.sync.alert.to-user} 覆盖。 */
    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public WmsDefectServiceImpl(WmsInventoryMapper inventoryMapper,
                                WmsOutboundOrderMapper orderMapper,
                                WmsOutboundDetailMapper detailMapper,
                                WmsLocationMapper locationMapper,
                                WmsWarehouseMapper warehouseMapper,
                                WmsProductionInboundMapper inboundMapper,
                                WmsMaterialMapper materialMapper,
                                IWmsAuditLogService auditLogService,
                                WmsDefectRecordMapper defectRecordMapper,
                                WmsInboundOrderMapper inboundOrderMapper,
                                IWmsCustomerService customerService,
                                WmsWarehouseCodeResolver warehouseResolver,
                                IWmsKingdeeConsignedReturnWriter consignedReturnWriter,
                                WmsKingdeeFormClient formClient,
                                IWmsSystemConfigService configService,
                                ISysBaseAPI sysBaseAPI) {
        this.inventoryMapper = inventoryMapper;
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.locationMapper = locationMapper;
        this.warehouseMapper = warehouseMapper;
        this.inboundMapper = inboundMapper;
        this.materialMapper = materialMapper;
        this.auditLogService = auditLogService;
        this.defectRecordMapper = defectRecordMapper;
        this.inboundOrderMapper = inboundOrderMapper;
        this.customerService = customerService;
        this.warehouseResolver = warehouseResolver;
        this.consignedReturnWriter = consignedReturnWriter;
        this.formClient = formClient;
        this.configService = configService;
        this.sysBaseAPI = sysBaseAPI;
    }

    @Override
    public IPage<WmsDefectLedgerVO> pageLedger(int pageNo, int pageSize, String materialKeyword,
                                               String batchNo, String locationCode,
                                               String ownershipType, String consignorId) {
        List<WmsWarehouse> scraps = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseType, WmsConstants.WAREHOUSE_TYPE_SCRAP));
        Page<WmsDefectLedgerVO> result = new Page<>(pageNo, pageSize);
        if (scraps == null || scraps.isEmpty()) {
            // 没配废品仓 = 没有不良品，直接返回空页而不是全表扫描
            result.setRecords(new ArrayList<>());
            result.setTotal(0);
            return result;
        }

        List<WmsDefectLedgerVO> rows = new ArrayList<>();
        long total = 0;
        for (WmsWarehouse warehouse : scraps) {
            WmsInventoryLedgerQueryDTO query = new WmsInventoryLedgerQueryDTO();
            query.setWarehouseId(warehouse.getId());
            query.setMaterialKeyword(materialKeyword);
            query.setBatchNo(batchNo);
            query.setLocationCode(locationCode);
            // 报废只关心还有量的行；0 量的行留在库里但没处置价值
            query.setOnlyPositive(Boolean.TRUE);
            // 默认只列自有库存（受托货不属于企业，不能混进自有台账）；
            // 显式传 CONSIGNED 才看受托不良——它们入废品仓后仍记受托所有权，
            // 若不支持切换就永远无法在不良品模块内看到并处置。
            query.setOwnershipType(resolveOwnershipType(ownershipType));
            query.setConsignorId(oConvertUtils.isEmpty(consignorId) ? null : consignorId.trim());
            IPage<WmsInventoryLedgerVO> page = inventoryMapper.selectLedgerPage(
                    new Page<>(pageNo, pageSize), query);
            if (page != null && page.getRecords() != null) {
                total += page.getTotal();
                for (WmsInventoryLedgerVO row : page.getRecords()) {
                    rows.add(toLedgerRow(row));
                }
            }
        }
        result.setRecords(rows);
        result.setTotal(total);
        return result;
    }

    /** 只认 CONSIGNED，其余（含 null/空串/非法值）一律按 OWNED——绝不能把受托货算进自有台账。 */
    private String resolveOwnershipType(String ownershipType) {
        return WmsConstants.OWNERSHIP_CONSIGNED.equals(trimToEmpty(ownershipType))
                ? WmsConstants.OWNERSHIP_CONSIGNED : WmsConstants.OWNERSHIP_OWNED;
    }

    private WmsDefectLedgerVO toLedgerRow(WmsInventoryLedgerVO row) {
        BigDecimal qty = row.getQuantity() == null ? BigDecimal.ZERO : row.getQuantity();
        BigDecimal locked = row.getLockedQty() == null ? BigDecimal.ZERO : row.getLockedQty();
        return new WmsDefectLedgerVO()
                .setInventoryId(row.getId())
                .setMaterialCode(row.getMaterialCode())
                .setMaterialName(row.getMaterialName())
                .setSpec(row.getSpec())
                .setBatchNo(row.getBatchNo())
                .setWarehouseCode(row.getWarehouseCode())
                .setWarehouseName(row.getWarehouseName())
                .setLocationCode(row.getLocationCode())
                .setQuantity(qty)
                .setLockedQty(locked)
                .setAvailableQty(qty.subtract(locked))
                .setUnit(row.getUnit())
                .setOwnershipType(row.getOwnershipType())
                .setConsignorName(row.getConsignorName())
                .setFirstInboundTime(row.getFirstInboundTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsScrapResultVO scrap(WmsScrapDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getInventoryId())) {
            throw new RuntimeException("请选择要报废的库存行");
        }
        BigDecimal qty = dto.getQuantity();
        if (qty == null || qty.signum() <= 0) {
            throw new RuntimeException("报废数量必须大于 0");
        }

        WmsInventory inventory = inventoryMapper.selectById(dto.getInventoryId());
        if (inventory == null) {
            throw new RuntimeException("库存行不存在（可能已被清理），请刷新后重试");
        }
        assertScrapWarehouse(inventory.getLocationId());

        BigDecimal locked = inventory.getLockedQty() == null ? BigDecimal.ZERO : inventory.getLockedQty();
        BigDecimal available = nvl(inventory.getQuantity()).subtract(locked);
        if (qty.compareTo(available) > 0) {
            throw new RuntimeException("报废数量超过可用数量（可用 " + available.stripTrailingZeros().toPlainString() + "）");
        }

        int affected = inventoryMapper.deductQuantity(inventory.getId(), qty);
        if (affected == 0) {
            // 并发被抢走：抛错让事务回滚，绝不能留下「单建了但库存没扣」的半成品
            throw new RuntimeException("库存已变更，请刷新后重试");
        }

        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo(WmsConstants.OUTBOUND_NO_PREFIX_SCRAP + IdWorker.getIdStr())
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SCRAP)
                .setStatus(WmsConstants.OUTBOUND_STATUS_COMPLETED)
                .setRemark(dto.getReason())
                .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM);
        order.setId(IdWorker.getIdStr());
        order.setCreateTime(new Date());
        orderMapper.insert(order);

        // 明细要存物料快照（code/name/unit），不能只有 id——出库明细表这两个列是 NOT NULL，
        // 而且单据是对账凭证，物料后来改名也不能影响历史单据
        WmsMaterial material = oConvertUtils.isEmpty(inventory.getMaterialId())
                ? null : materialMapper.selectById(inventory.getMaterialId());
        if (material == null) {
            throw new RuntimeException("库存行对应的物料不存在，无法报废");
        }
        WmsOutboundDetail detail = new WmsOutboundDetail()
                .setOutboundId(order.getId())
                .setLineNo(1)
                .setMaterialId(material.getId())
                .setMaterialCode(material.getMaterialCode())
                .setMaterialName(material.getMaterialName())
                .setUnit(material.getUnit())
                .setBatchNo(inventory.getBatchNo())
                .setRequiredQty(qty)
                .setActualQty(qty);
        detail.setId(IdWorker.getIdStr());
        detailMapper.insert(detail);

        BigDecimal remaining = available.subtract(qty);
        recordAudit(order, qty, dto.getReason());

        return new WmsScrapResultVO()
                .setOutboundId(order.getId())
                .setOutboundNo(order.getOutboundNo())
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SCRAP)
                .setScrappedQty(qty)
                .setRemainingQty(remaining)
                .setSyncSuccess(false);
    }

    /** 只允许报废废品仓的货——防止误伤正常库存。 */
    private void assertScrapWarehouse(String locationId) {
        WmsLocation location = oConvertUtils.isEmpty(locationId) ? null : locationMapper.selectById(locationId);
        if (location == null) {
            throw new RuntimeException("库存行缺少库位信息，无法确认是否在废品仓");
        }
        WmsWarehouse warehouse = warehouseMapper.selectById(location.getWarehouseId());
        if (warehouse == null
                || !WmsConstants.WAREHOUSE_TYPE_SCRAP.equals(warehouse.getWarehouseType())) {
            throw new RuntimeException("只有废品仓的库存可以报废出库");
        }
    }

    private void recordAudit(WmsOutboundOrder order, BigDecimal qty, String reason) {
        WmsAuditRecorder.record(auditLogService, new WmsAuditEvent()
                .setBizType(WmsConstants.AUDIT_BIZ_OUTBOUND)
                .setBizNo(order.getOutboundNo())
                .setAction(WmsConstants.AUDIT_ACTION_CANCEL)
                .setChannel(WmsConstants.AUDIT_CHANNEL_UI)
                .setAfterValue("{\"outboundType\":\"报废出库\",\"qty\":" + qty.stripTrailingZeros().toPlainString()
                        + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'")) + "\"}")
                .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                .setResult(WmsConstants.AUDIT_RESULT_SUCCESS));
    }

    // ------------------------------------------------------------ RK-005 两类不良来源

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordInbound(WmsDefectInboundDTO dto, String operator) {
        if (dto == null) {
            throw new RuntimeException("不良品入库登记入参不能为空");
        }
        String sourceType = trimToEmpty(dto.getSourceType());
        if (!WmsConstants.DEFECT_SOURCE_PRODUCTION.equals(sourceType)
                && !WmsConstants.DEFECT_SOURCE_CONSIGNED.equals(sourceType)) {
            throw new RuntimeException("来源类型必须为生产(PRODUCTION)或受托(CONSIGNED)");
        }
        String disposalType = trimToEmpty(dto.getDisposalType());
        if (!WmsConstants.DEFECT_DISPOSAL_SCRAP.equals(disposalType)
                && !WmsConstants.DEFECT_DISPOSAL_RETURN.equals(disposalType)) {
            throw new RuntimeException("处置方式必须为入废品仓(SCRAP)或退货(RETURN)");
        }
        BigDecimal qty = dto.getQty();
        if (qty == null || qty.signum() <= 0) {
            throw new RuntimeException("不良数量必须大于 0");
        }
        String sourceBillNo = trimToEmpty(dto.getSourceBillNo());
        if (sourceBillNo.isEmpty()) {
            // 受托方全靠来源单号反查（Task 1 Ruling），生产不良也要能追溯到入库单
            throw new RuntimeException("来源单号不能为空");
        }
        if (oConvertUtils.isEmpty(dto.getMaterialId())) {
            throw new RuntimeException("物料不能为空");
        }
        WmsMaterial material = materialMapper.selectById(dto.getMaterialId());
        if (material == null) {
            throw new RuntimeException("物料不存在");
        }
        String batchNo = trimToEmpty(dto.getBatchNo());
        if (WmsConstants.DEFECT_SOURCE_CONSIGNED.equals(sourceType) && batchNo.isEmpty()) {
            // 受托扣库存要按 物料+批次+受托方 匹配，缺批次就没法定位受托库存行
            throw new RuntimeException("受托不良必须提供批次号");
        }

        Date now = new Date();
        rejectDuplicate(sourceType, sourceBillNo, material, batchNo, disposalType, qty);
        if (WmsConstants.DEFECT_SOURCE_PRODUCTION.equals(sourceType)) {
            recordProduction(sourceBillNo, material, batchNo, qty, dto.getReason(), disposalType, operator, now);
            return;
        }
        recordConsigned(sourceBillNo, material, batchNo, qty, dto.getReason(), disposalType, operator, now);
    }

    /**
     * 防呆（待办 §5-22）：同 来源类型+来源单号+物料+批次+处置方式+数量 的登记视为同一次提交的
     * 重复请求，直接拒绝——重复提交会重复扣减库存、重复落废品仓台账。
     *
     * <p>只拦**完全相同**的登记：同来源单再次登记但数量不同、或处置方式不同（SCRAP 与 RETURN
     * 并存）都是合法业务，不能误伤（对应单测 {@code recordInbound_sameKeyDifferent*}）。
     * 数量是 DECIMAL 等值比较（SQL 数值相等，3 与 3.00 视为相同）。查询用
     * {@code selectList + LIMIT 1} 而不是 {@code selectOne}：历史数据可能重复，
     * {@code selectOne} 会抛 TooManyResultsException 把「已登记」误报成系统错误。
     * 两请求并发同时过检查的窗口由事务内检查兜底——未加数据库唯一索引，因为历史数据
     * 可能已存在重复行，加索引的迁移有失败风险。
     */
    private void rejectDuplicate(String sourceType, String sourceBillNo, WmsMaterial material,
                                 String batchNo, String disposalType, BigDecimal qty) {
        List<WmsDefectRecord> existing = defectRecordMapper.selectList(Wrappers.<WmsDefectRecord>query()
                .eq("source_type", sourceType)
                .eq("source_bill_no", sourceBillNo)
                .eq("material_code", material.getMaterialCode())
                .eq("batch_no", batchNo)
                .eq("disposal_type", disposalType)
                .eq("qty", qty)
                .orderByDesc("create_time")
                .last("LIMIT 1"));
        if (existing != null && !existing.isEmpty()) {
            throw new RuntimeException("该不良品登记已存在（来源单[" + sourceBillNo + "]物料["
                    + material.getMaterialCode() + "]批次[" + trimToEmpty(batchNo) + "]处置[" + disposalType
                    + "]数量 " + qty.stripTrailingZeros().toPlainString() + "），请勿重复提交；"
                    + "如确需再次登记请先核实历史记录");
        }
    }

    /** 生产不良：生产入库单必须存在；SCRAP 落自有废品仓；退货不适用生产来源。 */
    private void recordProduction(String sourceBillNo, WmsMaterial material, String batchNo, BigDecimal qty,
                                  String reason, String disposalType, String operator, Date now) {
        if (!WmsConstants.DEFECT_DISPOSAL_SCRAP.equals(disposalType)) {
            throw new RuntimeException("生产不良只支持入废品仓（SCRAP），退货仅适用于受托不良");
        }
        WmsProductionInbound inbound = inboundMapper.selectOne(Wrappers.<WmsProductionInbound>query()
                .eq("inbound_no", sourceBillNo).last("LIMIT 1"));
        if (inbound == null) {
            throw new RuntimeException("生产入库单不存在：" + sourceBillNo);
        }
        writeScrapInventory(material.getId(), batchNo, qty, false, "", operator, now);
        insertDefectRecord(buildRecord(WmsConstants.DEFECT_SOURCE_PRODUCTION, sourceBillNo, material,
                batchNo, qty, reason, disposalType, operator, now), WmsConstants.DEFECT_STATUS_DISPOSED);
        log.info("生产不良登记：来源单={}，物料={}，批次={}，数量={}，处置=入废品仓",
                sourceBillNo, material.getMaterialCode(), batchNo, qty);
    }

    /** 受托不良：必须能反查入库单与受托方；SCRAP 扣受托库存后入废品仓（不转自有），RETURN 只登记。 */
    private void recordConsigned(String sourceBillNo, WmsMaterial material, String batchNo, BigDecimal qty,
                                 String reason, String disposalType, String operator, Date now) {
        ConsignorRef ref = resolveConsignor(sourceBillNo);
        WmsCustomer consignor = ref.customer();
        if (WmsConstants.DEFECT_DISPOSAL_RETURN.equals(disposalType)) {
            // 退货只登记台账（待处理）：不扣库存、不写废品仓，等 returnToConsignor 统一回写金蝶
            insertDefectRecord(buildRecord(WmsConstants.DEFECT_SOURCE_CONSIGNED, sourceBillNo, material,
                    batchNo, qty, reason, disposalType, operator, now), WmsConstants.DEFECT_STATUS_PENDING);
            log.info("受托不良登记（待退货）：来源单={}，物料={}，批次={}，数量={}，受托方={}",
                    sourceBillNo, material.getMaterialCode(), batchNo, qty, consignor.getCustomerCode());
            return;
        }
        deductConsignedInventory(sourceBillNo, material, batchNo, qty, consignor);
        writeScrapInventory(material.getId(), batchNo, qty, true, consignor.getId(), operator, now);
        insertDefectRecord(buildRecord(WmsConstants.DEFECT_SOURCE_CONSIGNED, sourceBillNo, material,
                batchNo, qty, reason, disposalType, operator, now), WmsConstants.DEFECT_STATUS_DISPOSED);
        log.info("受托不良登记（入废品仓）：来源单={}，物料={}，批次={}，数量={}，受托方={}，仍记受托不转自有",
                sourceBillNo, material.getMaterialCode(), batchNo, qty, consignor.getCustomerCode());
    }

    /**
     * 扣减受托库存：只匹配 物料+批次+CONSIGNED+受托方 的行（受托货与自有货按六列唯一键分账，
     * 绝不能扣到自有头上），FIFO 逐行扣，总量不足拒绝、并发被抢抛错回滚。
     */
    private void deductConsignedInventory(String sourceBillNo, WmsMaterial material, String batchNo,
                                          BigDecimal qty, WmsCustomer consignor) {
        List<WmsInventory> rows = inventoryMapper.selectList(Wrappers.<WmsInventory>query()
                .eq("material_id", material.getId())
                .eq("batch_no", batchNo)
                .eq("ownership_type", WmsConstants.OWNERSHIP_CONSIGNED)
                .eq("consignor_id", consignor.getId())
                .gt("quantity", BigDecimal.ZERO));
        List<WmsInventory> availableRows = new ArrayList<>();
        BigDecimal totalAvailable = BigDecimal.ZERO;
        for (WmsInventory row : rows) {
            BigDecimal available = nvl(row.getQuantity()).subtract(nvl(row.getLockedQty()));
            if (available.signum() > 0) {
                availableRows.add(row);
                totalAvailable = totalAvailable.add(available);
            }
        }
        if (totalAvailable.compareTo(qty) < 0) {
            throw new RuntimeException("受托库存不足：受托方[" + consignor.getCustomerCode() + "]物料["
                    + material.getMaterialCode() + "]批次[" + batchNo + "]可用 "
                    + totalAvailable.stripTrailingZeros().toPlainString() + "，需求 "
                    + qty.stripTrailingZeros().toPlainString());
        }
        availableRows.sort(Comparator.comparing(WmsInventory::getFirstInboundTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal remaining = qty;
        for (WmsInventory row : availableRows) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal available = nvl(row.getQuantity()).subtract(nvl(row.getLockedQty()));
            BigDecimal deduct = available.min(remaining);
            if (inventoryMapper.deductQuantity(row.getId(), deduct) == 0) {
                throw new RuntimeException("受托库存已变更，请刷新后重试");
            }
            remaining = remaining.subtract(deduct);
        }
        log.info("受托不良扣减受托库存：来源单={}，物料={}，批次={}，数量={}，受托方={}",
                sourceBillNo, material.getMaterialCode(), batchNo, qty, consignor.getCustomerCode());
    }

    /**
     * 退货离库时扣减受托库存：与 SCRAP 同口径（物料+批次+受托方），按物料+批次聚合数量后逐组扣。
     *
     * <p>不良记录只存物料编码（无 materialId），必须先按编码解析物料；解析不到直接拒绝，
     * 否则会静默少扣。数量不足由 {@link #deductConsignedInventory} 抛错，事务整体回滚。
     */
    private void deductReturnedInventory(String sourceBillNo, List<WmsDefectRecord> records,
                                         WmsCustomer consignor) {
        Map<String, BigDecimal> qtyByKey = new java.util.LinkedHashMap<>();
        Map<String, WmsDefectRecord> sampleByKey = new java.util.LinkedHashMap<>();
        for (WmsDefectRecord r : records) {
            if (r == null) {
                continue;
            }
            String key = trimToEmpty(r.getMaterialCode()) + "|" + trimToEmpty(r.getBatchNo());
            qtyByKey.merge(key, nvl(r.getQty()), BigDecimal::add);
            sampleByKey.putIfAbsent(key, r);
        }
        for (Map.Entry<String, BigDecimal> entry : qtyByKey.entrySet()) {
            WmsDefectRecord sample = sampleByKey.get(entry.getKey());
            String materialCode = trimToEmpty(sample.getMaterialCode());
            String batchNo = trimToEmpty(sample.getBatchNo());
            if (materialCode.isEmpty()) {
                throw new RuntimeException("不良记录缺少物料编码，无法扣减受托库存");
            }
            if (batchNo.isEmpty()) {
                throw new RuntimeException("不良记录[" + materialCode + "]缺少批次号，无法扣减受托库存");
            }
            WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                    .eq("material_code", materialCode).last("LIMIT 1"));
            if (material == null) {
                throw new RuntimeException("物料[" + materialCode + "]不存在，无法扣减受托库存");
            }
            deductConsignedInventory(sourceBillNo, material, batchNo, entry.getValue(), consignor);
        }
        log.info("受托不良退货扣减受托库存：来源单={}，受托方={}，共 {} 个物料/批次组合",
                sourceBillNo, consignor.getCustomerCode(), qtyByKey.size());
    }

    /** 待退记录是否物料+批次一致（一致才能聚合成一条分录）。 */
    private boolean isSameMaterialBatch(List<WmsDefectRecord> pending) {
        WmsDefectRecord first = pending.get(0);
        return pending.stream().allMatch(r ->
                Objects.equals(r.getMaterialCode(), first.getMaterialCode())
                        && Objects.equals(r.getBatchNo(), first.getBatchNo()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsDefectRecord returnToConsignor(String recordId, String operator) {
        WmsDefectRecord record = defectRecordMapper.selectById(recordId);
        if (record == null) {
            throw new RuntimeException("不良品记录不存在");
        }
        if (!WmsConstants.DEFECT_SOURCE_CONSIGNED.equals(record.getSourceType())) {
            throw new RuntimeException("只有受托不良品可以退货");
        }
        if (!WmsConstants.DEFECT_DISPOSAL_RETURN.equals(record.getDisposalType())) {
            throw new RuntimeException("该记录处置方式不是退货，不能退货");
        }
        if (!WmsConstants.DEFECT_STATUS_PENDING.equals(record.getStatus())) {
            throw new RuntimeException("当前状态[" + record.getStatus() + "]不允许退货");
        }
        String sourceBillNo = trimToEmpty(record.getSourceBillNo());
        if (sourceBillNo.isEmpty()) {
            // 受托方全靠来源单号反查，缺了就永远定位不到受托人
            throw new RuntimeException("不良记录缺少来源单号，无法定位受托方");
        }
        ConsignorRef ref = resolveConsignor(sourceBillNo);
        WmsCustomer consignor = ref.customer();

        // 同来源单的 RETURN 记录聚合共用一张退货单：已有退货单号的直接沿用（不重复开单）
        List<WmsDefectRecord> returned = defectRecordMapper.selectList(Wrappers.<WmsDefectRecord>query()
                .eq("source_bill_no", sourceBillNo)
                .eq("disposal_type", WmsConstants.DEFECT_DISPOSAL_RETURN)
                .isNotNull("return_bill_no")
                .ne("return_bill_no", ""));
        List<WmsDefectRecord> pending = defectRecordMapper.selectList(Wrappers.<WmsDefectRecord>query()
                .eq("source_bill_no", sourceBillNo)
                .eq("disposal_type", WmsConstants.DEFECT_DISPOSAL_RETURN)
                .eq("status", WmsConstants.DEFECT_STATUS_PENDING)
                .orderByAsc("id"));
        if (pending.isEmpty()) {
            // 触发记录已被并发的退货请求处理掉
            throw new RuntimeException("该记录已被退货，请刷新后重试");
        }

        if (!returned.isEmpty()) {
            String billNo = returned.get(0).getReturnBillNo();
            // 货已离库：扣减受托库存必须与置「已退货」同事务，否则账实不符且后续能重复扣这批
            deductReturnedInventory(sourceBillNo, pending, consignor);
            markReturned(pending, billNo, operator);
            warnUncoveredByReturnedBill(sourceBillNo, billNo, pending, returned);
            log.info("受托不良退货沿用已有退货单：来源单={}，退货单={}，本次聚合 {} 条记录",
                    sourceBillNo, billNo, pending.size());
            return applyReturned(record, billNo);
        }

        if (!consignedReturnWriter.isEnabled()) {
            throw new RuntimeException("金蝶同步未启用，不能退货；请先启用金蝶同步并配置受托加工材料退料单 formId");
        }
        String formId = requireReturnFormId();
        if (consignedReturnWriter.existsByReference(sourceBillNo)) {
            // 上次 Save 成功但本地回存被回滚（进程中断/提交失败）：ERP 已有单，
            // Writer 不提供单号查询，再 write 必然重复开单——置已退货、单号留空并告警，
            // ERP 侧凭 FNote=来源单:<来源单号> 可定位到该单
            deductReturnedInventory(sourceBillNo, pending, consignor);
            markReturned(pending, null, operator);
            String warn = "金蝶已存在来源单[" + sourceBillNo + "]的受托加工材料退料单（FNote=来源单:" + sourceBillNo
                    + "），本地未回存单号，已按已退货处理，请人工在金蝶核对单号并回填。"
                    + uncoveredHint(pending, null);
            log.warn(warn);
            alert(warn);
            return applyReturned(record, null);
        }

        // 退货仓库由来源入库单明细的货位反查（金蝶退料单 FStockId 必填）；
        // 沿用在已有退货单时不写 ERP，故只在真正要开单的分支校验
        if (oConvertUtils.isEmpty(ref.warehouseCode())) {
            throw new RuntimeException("受托入库单[" + sourceBillNo
                    + "]的明细没有库位/仓库信息，无法确定金蝶退货仓库，不能退货");
        }

        // 聚合口径：物料+批次一致的待退记录合成一条分录（数量合计）开一张单；
        // 物料/批次不一致时 Writer 一单只能一条分录，只回写触发记录，其余记录留待处理。
        // 先定范围再聚合：聚合会改写首条记录的数量，扣库存必须按原始数量算。
        List<WmsDefectRecord> scope = isSameMaterialBatch(pending)
                ? pending : List.of(record);
        // 退货成功前扣减受托库存：货已离库，账必须同事务记掉；
        // 不足直接抛错回滚——绝不能出现「已退货 + 金蝶单已开 + 库存没动」的账实不符。
        deductReturnedInventory(sourceBillNo, scope, consignor);
        WmsDefectRecord payload = buildReturnPayload(pending, record);
        String billNo = null;
        String lastError = null;
        for (int attempt = 1; attempt <= RETURN_MAX_ATTEMPTS; attempt++) {
            try {
                if (billNo == null) {
                    WmsKingdeeResult saved = consignedReturnWriter.write(payload,
                            consignor.getCustomerCode(), ref.warehouseCode(), ref.erpBillNo());
                    if (saved == null || !saved.isSuccess() || oConvertUtils.isEmpty(saved.getNumber())) {
                        throw new IllegalStateException("金蝶保存失败："
                                + (saved == null ? "无返回" : saved.getMessage()));
                    }
                    billNo = saved.getNumber();
                    // 单号先回存再提交：提交失败的重试只对同一单号重提，不会重复开单
                    backfillReturnBillNo(scope, billNo, operator);
                }
                WmsKingdeeResult submitted = formClient.submit(formId, List.of(billNo));
                if (submitted == null || !submitted.isSuccess()) {
                    throw new IllegalStateException("金蝶提交失败："
                            + (submitted == null ? "无返回" : submitted.getMessage()));
                }
                markReturned(scope, billNo, operator);
                log.info("受托不良退货成功：来源单={}，退货单={}，本次处理 {} 条记录",
                        sourceBillNo, billNo, scope.size());
                return applyReturned(record, billNo);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("受托不良退货第 {}/{} 次尝试失败：来源单={}，原因={}",
                        attempt, RETURN_MAX_ATTEMPTS, sourceBillNo, lastError);
            }
        }
        String message = "受托不良退货回写金蝶失败（已重试 " + RETURN_MAX_ATTEMPTS
                + " 次）：来源单[" + sourceBillNo + "]，原因：" + lastError;
        alert(message);
        throw new RuntimeException(message);
    }

    @Override
    public IPage<WmsDefectRecord> listPage(int pageNo, int pageSize, WmsDefectRecordQueryDTO query) {
        QueryWrapper<WmsDefectRecord> qw = new QueryWrapper<>();
        if (query != null) {
            if (oConvertUtils.isNotEmpty(query.getSourceType())) {
                qw.eq("source_type", query.getSourceType());
            }
            if (oConvertUtils.isNotEmpty(query.getDisposalType())) {
                qw.eq("disposal_type", query.getDisposalType());
            }
            if (oConvertUtils.isNotEmpty(query.getStatus())) {
                qw.eq("status", query.getStatus());
            }
            // 查询窗口模糊匹配（2026-11-02 口径）：任意连续子串都能命中
            if (oConvertUtils.isNotEmpty(query.getMaterialCode())) {
                qw.like("material_code", query.getMaterialCode().trim());
            }
            if (oConvertUtils.isNotEmpty(query.getSourceBillNo())) {
                qw.like("source_bill_no", query.getSourceBillNo().trim());
            }
            if (query.getCreateTimeBegin() != null) {
                qw.ge("create_time", query.getCreateTimeBegin());
            }
            if (query.getCreateTimeEnd() != null) {
                qw.le("create_time", endOfDay(query.getCreateTimeEnd()));
            }
        }
        qw.orderByDesc("create_time").orderByDesc("id");
        return defectRecordMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 结束日期扩到当天 23:59:59.999：入参按天传（@DateTimeFormat yyyy-MM-dd），闭区间语义。 */
    private Date endOfDay(Date day) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTime();
    }

    // ------------------------------------------------------------ RK-005 内部支撑

    /**
     * 受托方客户档案 + 退货仓库编码（由来源入库单反查）。
     *
     * @param customer      客户档案（受托方）
     * @param warehouseCode 退货仓库编码；来源入库单明细没有库位时为 null（仅退货回写需要）
     * @param erpBillNo     来源入库单的金蝶单号（{@code FSrcBillNo}，溯源用）；未回写成功时为空
     */
    private record ConsignorRef(WmsCustomer customer, String warehouseCode, String erpBillNo) {
    }

    /**
     * 按来源单号反查受托入库单与受托方。
     *
     * <p>受托方不落在不良记录里（{@code wms_defect_record} 无 consignor 列，Task 1 Ruling），
     * 全靠 来源单号 → 入库单 → consignor_id 这根链条；任一环节缺失都必须拒绝，
     * 否则扣错库存、退货单找不到收货人。
     *
     * <p>V2.4：{@code consignor_id} 指向**客户档案**（wms_customer），与金蝶受托方口径一致；
     * 不再借用 {@code supplier_id}（那只给外购）。
     */
    private ConsignorRef resolveConsignor(String sourceBillNo) {
        WmsInboundOrder order = inboundOrderMapper.selectOne(Wrappers.<WmsInboundOrder>query()
                .eq("order_no", sourceBillNo).last("LIMIT 1"));
        if (order == null) {
            throw new RuntimeException("受托入库单不存在：" + sourceBillNo);
        }
        if (!WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType())) {
            throw new RuntimeException("来源入库单[" + sourceBillNo + "]不是受托入库单，不能按受托不良处置");
        }
        if (oConvertUtils.isEmpty(order.getConsignorId())) {
            throw new RuntimeException("受托入库单[" + sourceBillNo + "]缺少受托方，无法登记受托不良");
        }
        WmsCustomer customer = customerService.getById(order.getConsignorId());
        if (customer == null) {
            throw new RuntimeException("受托方不存在（" + order.getConsignorId() + "），无法登记受托不良");
        }
        if (oConvertUtils.isEmpty(customer.getCustomerCode())) {
            throw new RuntimeException("受托方[" + customer.getCustomerName() + "]缺少客户编码，无法回写金蝶");
        }
        return new ConsignorRef(customer, warehouseResolver.byInboundOrder(order.getId()),
                order.getErpBillNo());
    }

    /**
     * 组装退货回写载荷：待退记录物料+批次一致时以首条为载体聚合数量合计
     * （单据日期取最早创建时间，重试/补跑不会写错日期）；不一致时只回写触发记录。
     */
    private WmsDefectRecord buildReturnPayload(List<WmsDefectRecord> pending, WmsDefectRecord trigger) {
        WmsDefectRecord first = pending.get(0);
        if (!isSameMaterialBatch(pending)) {
            log.warn("来源单下存在不同物料/批次的不良退货记录，本次仅回写触发记录 {}，"
                    + "其余记录请逐条退货（各自生成独立退货单）", trigger.getId());
            return trigger;
        }
        BigDecimal total = BigDecimal.ZERO;
        Date earliest = null;
        for (WmsDefectRecord r : pending) {
            total = total.add(nvl(r.getQty()));
            if (r.getCreateTime() != null && (earliest == null || r.getCreateTime().before(earliest))) {
                earliest = r.getCreateTime();
            }
        }
        first.setQty(total);
        if (earliest != null) {
            first.setCreateTime(earliest);
        }
        return first;
    }

    /** Save 成功后先回存单号（状态仍待处理）：提交失败的重试只对同一单号重提，不重复开单。 */
    private void backfillReturnBillNo(List<WmsDefectRecord> records, String billNo, String operator) {
        Date now = new Date();
        for (WmsDefectRecord r : records) {
            if (oConvertUtils.isNotEmpty(r.getReturnBillNo())) {
                continue;
            }
            WmsDefectRecord patch = new WmsDefectRecord();
            patch.setId(r.getId());
            patch.setReturnBillNo(billNo);
            patch.setUpdateBy(operator);
            patch.setUpdateTime(now);
            defectRecordMapper.updateById(patch);
            r.setReturnBillNo(billNo);
        }
    }

    /** 置已退货；{@code billNo} 为空时只改状态（单号留空，由告警提示人工核对回填）。 */
    private void markReturned(List<WmsDefectRecord> records, String billNo, String operator) {
        Date now = new Date();
        for (WmsDefectRecord r : records) {
            WmsDefectRecord patch = new WmsDefectRecord();
            patch.setId(r.getId());
            patch.setStatus(WmsConstants.DEFECT_STATUS_RETURNED);
            if (oConvertUtils.isNotEmpty(billNo)) {
                patch.setReturnBillNo(billNo);
            }
            patch.setUpdateBy(operator);
            patch.setUpdateTime(now);
            defectRecordMapper.updateById(patch);
            r.setStatus(WmsConstants.DEFECT_STATUS_RETURNED);
            if (oConvertUtils.isNotEmpty(billNo)) {
                r.setReturnBillNo(billNo);
            }
        }
    }

    /** 把退货结果同步到触发记录对象上（供控制器直接回显）。 */
    private WmsDefectRecord applyReturned(WmsDefectRecord record, String billNo) {
        record.setStatus(WmsConstants.DEFECT_STATUS_RETURNED);
        if (oConvertUtils.isNotEmpty(billNo)) {
            record.setReturnBillNo(billNo);
        }
        return record;
    }

    private WmsDefectRecord buildRecord(String sourceType, String sourceBillNo, WmsMaterial material,
                                        String batchNo, BigDecimal qty, String reason,
                                        String disposalType, String operator, Date now) {
        WmsDefectRecord record = new WmsDefectRecord()
                .setSourceType(sourceType)
                .setSourceBillNo(sourceBillNo)
                .setMaterialCode(material.getMaterialCode())
                .setBatchNo(batchNo)
                .setQty(qty)
                .setReason(oConvertUtils.isEmpty(reason) ? null : reason.trim())
                .setDisposalType(disposalType);
        record.setCreateBy(operator);
        record.setCreateTime(now);
        record.setUpdateBy(operator);
        record.setUpdateTime(now);
        return record;
    }

    private void insertDefectRecord(WmsDefectRecord record, String status) {
        record.setStatus(status);
        defectRecordMapper.insert(record);
    }

    /**
     * 废品仓落账：所有权两列**显式**写入——六列唯一键据此把受托行与自有行分开，
     * 漏写会被兜底成 OWNED 并入自有行。自建 id：upsertInventory 是自定义 SQL，
     * MyBatis-Plus 不会为其生成主键。
     */
    private void writeScrapInventory(String materialId, String batchNo, BigDecimal qty,
                                     boolean consigned, String consignorId, String operator, Date now) {
        WmsInventory inventory = new WmsInventory()
                .setMaterialId(materialId)
                .setLocationId(resolveScrapLocationId())
                .setBatchNo(batchNo)
                // container_code 是 NOT NULL：废品仓不按料箱管理，必须显式空串
                .setContainerCode("")
                .setQuantity(qty)
                .setOwnershipType(consigned ? WmsConstants.OWNERSHIP_CONSIGNED : WmsConstants.OWNERSHIP_OWNED)
                .setConsignorId(consigned ? consignorId : "")
                .setFirstInboundTime(now);
        inventory.setId(IdWorker.getIdStr());
        inventory.setCreateBy(operator);
        inventory.setCreateTime(now);
        inventory.setUpdateBy(operator);
        inventory.setUpdateTime(now);
        inventoryMapper.upsertInventory(inventory);
    }

    /** 废品仓落账库位：优先系统配置 defect.warehouse.code，回退废品仓默认位（与生产入库同一口径）。 */
    private String resolveScrapLocationId() {
        String configuredCode = configService.getString(WmsConfigKey.DEFECT_WAREHOUSE_CODE.getKey(), "");
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
        WmsWarehouse warehouse = warehouseMapper.selectOne(Wrappers.<WmsWarehouse>query()
                .eq("warehouse_type", WmsConstants.WAREHOUSE_TYPE_SCRAP).last("LIMIT 1"));
        if (warehouse == null) {
            throw new RuntimeException("废品仓未配置，请联系管理员");
        }
        WmsLocation location = locationMapper.selectOne(Wrappers.<WmsLocation>query()
                .eq("warehouse_id", warehouse.getId())
                .eq("location_code", WmsConstants.LOCATION_CODE_SCRAP_DEFAULT)
                .last("LIMIT 1"));
        if (location == null) {
            throw new RuntimeException("废品仓未配置，请联系管理员");
        }
        return location.getId();
    }

    /** 退货提交用的 formId：未配置显式失败（Writer 侧同样校验，双保险且报错更早）。 */
    private String requireReturnFormId() {
        String formId = configService.getString(WmsConfigKey.KD_FORM_CONSIGNED_RETURN.getKey(), "");
        if (oConvertUtils.isEmpty(formId) || formId.trim().isEmpty()) {
            throw new RuntimeException("金蝶受托加工材料退料单未配置 formId（配置项 "
                    + WmsConfigKey.KD_FORM_CONSIGNED_RETURN.getKey() + "），不能退货");
        }
        return formId.trim();
    }

    /**
     * 沿用已有退货单时，提示「本次待退记录里哪些物料/批次不在已回写的记录中」。
     *
     * <p>Writer 一张单只能带一条分录，同来源单不同物料会出现「金蝶单不含该行但本地已置已退货」，
     * 必须告警让人去 ERP 补录，不能静默。
     */
    private void warnUncoveredByReturnedBill(String sourceBillNo, String billNo,
                                             List<WmsDefectRecord> pending, List<WmsDefectRecord> returned) {
        String hint = uncoveredHint(pending, returned);
        if (hint.isEmpty()) {
            return;
        }
        String warn = "受托不良退货沿用已有退货单[" + billNo + "]（来源单[" + sourceBillNo + "]）。" + hint;
        log.warn(warn);
        alert(warn);
    }

    /**
     * @param returned 已回写退货单的记录；为 null 时表示「金蝶已有单但本地无从得知分录」，按全部待退提示
     * @return 需人工补录的提示；无需提示时返回空串
     */
    private String uncoveredHint(List<WmsDefectRecord> pending, List<WmsDefectRecord> returned) {
        Set<String> covered = new HashSet<>();
        if (returned != null) {
            for (WmsDefectRecord r : returned) {
                covered.add(returnKey(r));
            }
        }
        List<String> missing = new ArrayList<>();
        for (WmsDefectRecord r : pending) {
            if (!covered.contains(returnKey(r))) {
                missing.add(r.getMaterialCode() + "/批次[" + trimToEmpty(r.getBatchNo()) + "]/数量 "
                        + nvl(r.getQty()).stripTrailingZeros().toPlainString());
            }
        }
        if (missing.isEmpty()) {
            return "";
        }
        return "以下物料行未包含在该退货单中，需人工在金蝶补录：" + String.join("；", missing);
    }

    private String returnKey(WmsDefectRecord r) {
        return trimToEmpty(r.getMaterialCode()) + "|" + trimToEmpty(r.getBatchNo());
    }

    /** 站内告警；告警设施自身异常不得掩盖业务失败。 */
    private void alert(String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(
                    WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.DEFECT_RETURN_ALERT_TITLE, content));
        } catch (Exception e) {
            log.error("不良退货告警投递失败：{}", e.getMessage(), e);
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
