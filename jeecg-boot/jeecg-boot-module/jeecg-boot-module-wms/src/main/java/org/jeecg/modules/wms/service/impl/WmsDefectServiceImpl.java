package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsDefectService;
import org.jeecg.modules.wms.vo.WmsDefectLedgerVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 不良品处置（XT-001）。
 *
 * <p>报废出库复用 {@code wms_outbound_order}（类型=报废出库），**一步到位**：
 * 建单 → 扣库存 → 已完成。废品仓是固定库位、无设备搬运，不走分配/复核那套。
 *
 * <p>报废是**减库存的不可逆动作**，所以：数量必须 ≤ 可用量、只允许废品仓的货、
 * 扣减影响行数为 0（并发抢走）时抛错让事务回滚。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Service
public class WmsDefectServiceImpl implements IWmsDefectService {

    private final WmsInventoryMapper inventoryMapper;
    private final WmsOutboundOrderMapper orderMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsProductionInboundMapper inboundMapper;
    private final WmsMaterialMapper materialMapper;
    private final IWmsAuditLogService auditLogService;

    public WmsDefectServiceImpl(WmsInventoryMapper inventoryMapper,
                                WmsOutboundOrderMapper orderMapper,
                                WmsOutboundDetailMapper detailMapper,
                                WmsLocationMapper locationMapper,
                                WmsWarehouseMapper warehouseMapper,
                                WmsProductionInboundMapper inboundMapper,
                                WmsMaterialMapper materialMapper,
                                IWmsAuditLogService auditLogService) {
        this.inventoryMapper = inventoryMapper;
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.locationMapper = locationMapper;
        this.warehouseMapper = warehouseMapper;
        this.inboundMapper = inboundMapper;
        this.materialMapper = materialMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public IPage<WmsDefectLedgerVO> pageLedger(int pageNo, int pageSize, String materialKeyword,
                                               String batchNo, String locationCode) {
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

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
