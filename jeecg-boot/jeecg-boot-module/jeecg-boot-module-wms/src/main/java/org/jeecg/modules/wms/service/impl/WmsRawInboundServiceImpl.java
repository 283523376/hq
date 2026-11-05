package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsPutawayDTO;
import org.jeecg.modules.wms.dto.WmsRawInboundDTO;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsRawInboundService;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.sync.IWmsKingdeeConsignedInboundWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReceiveWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeeRawOrderReader;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.util.WmsInboundRouting;
import org.jeecg.modules.wms.vo.WmsRawPutawayResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 外购/受托原材料到货登记与段差棒直入（RK-001 V2.4，spec §3.2）。
 *
 * <p><b>为什么不复用 {@code WmsInboundServiceImpl.putaway}</b>：那条链路绑定检验任务
 * （待检验 → 待分配 → 待上架）并走旧 HTTP 网关 {@code WmsInboundErpSyncGateway}；
 * 原材料到货要求「跳过检验、一步上架、回写金蝶可配置单据」，两条链路的状态机与回写目标都不同。
 *
 * <p><b>处理方式不落库，用单据状态区分</b>：{@code wms_inbound_order} 没有 handling_type 列，
 * 因此：
 * <ul>
 *   <li>DIRECT（段差棒直入）到货登记为「待分配」，上架后「已审核」，库存入账；</li>
 *   <li>CONVERT（大改小）到货登记即「已审核」（登记完成，原材料直送车间），
 *       {@code sync_status=无需同步}，不存在可上架状态——{@link #putawayRaw} 自然拒绝。</li>
 * </ul>
 *
 * <p><b>所有权口径</b>：{@code sourceType=CONSIGNED} 的库存行写
 * {@code ownership_type=CONSIGNED} + {@code consignor_id=受托方}；其余写 {@code OWNED} + 空串。
 * 两列必须显式写入——六列唯一键会按它们把自有/受托货分开，漏写会被并入自有行。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsRawInboundServiceImpl extends ServiceImpl<WmsInboundOrderMapper, WmsInboundOrder>
        implements IWmsRawInboundService {

    /** 金蝶开关关闭 / formId 未配置时的统一提示：配置未就绪必须显式失败，不得静默跳过。 */
    static final String KD_NOT_READY = "请先在系统配置中启用并填写金蝶单据 formId";

    /** 到货登记单号前缀：与采购入库共用同一条 SL 序列（同一张表、同一个唯一索引）。 */
    private static final String ORDER_NO_PREFIX = "SL";

    private final WmsInboundDetailMapper detailMapper;
    private final IWmsMaterialService materialService;
    private final IWmsMaterialCategoryService categoryService;
    private final IWmsSupplierService supplierService;
    private final IWmsCustomerService customerService;
    private final WmsLocationMapper locationMapper;
    private final IWmsWarehouseService warehouseService;
    private final WmsInventoryMapper inventoryMapper;
    private final IWmsKingdeeRawOrderReader rawOrderReader;
    private final IWmsKingdeePurchaseReceiveWriter purchaseReceiveWriter;
    private final IWmsKingdeeConsignedInboundWriter consignedInboundWriter;
    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;

    public WmsRawInboundServiceImpl(WmsInboundDetailMapper detailMapper,
                                    IWmsMaterialService materialService,
                                    IWmsMaterialCategoryService categoryService,
                                    IWmsSupplierService supplierService,
                                    IWmsCustomerService customerService,
                                    WmsLocationMapper locationMapper,
                                    IWmsWarehouseService warehouseService,
                                    WmsInventoryMapper inventoryMapper,
                                    IWmsKingdeeRawOrderReader rawOrderReader,
                                    IWmsKingdeePurchaseReceiveWriter purchaseReceiveWriter,
                                    IWmsKingdeeConsignedInboundWriter consignedInboundWriter,
                                    WmsKingdeeFormClient formClient,
                                    IWmsSystemConfigService configService) {
        this.detailMapper = detailMapper;
        this.materialService = materialService;
        this.categoryService = categoryService;
        this.supplierService = supplierService;
        this.customerService = customerService;
        this.locationMapper = locationMapper;
        this.warehouseService = warehouseService;
        this.inventoryMapper = inventoryMapper;
        this.rawOrderReader = rawOrderReader;
        this.purchaseReceiveWriter = purchaseReceiveWriter;
        this.consignedInboundWriter = consignedInboundWriter;
        this.formClient = formClient;
        this.configService = configService;
    }

    // ------------------------------------------------------------ 到货登记

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsInboundOrder receive(WmsRawInboundDTO dto, String operator) {
        if (dto == null) {
            throw new RuntimeException("到货登记入参不能为空");
        }
        String sourceType = trim(dto.getSourceType());
        String handlingType = trim(dto.getHandlingType());
        if (!WmsConstants.INBOUND_SOURCE_PURCHASE.equals(sourceType)
                && !WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(sourceType)) {
            throw new RuntimeException("来源类型必须为外购(PURCHASE)或受托(CONSIGNED)");
        }
        if (!WmsConstants.INBOUND_HANDLING_DIRECT.equals(handlingType)
                && !WmsConstants.INBOUND_HANDLING_CONVERT.equals(handlingType)) {
            throw new RuntimeException("处理方式必须为大改小(CONVERT)或段差棒直入(DIRECT)");
        }
        String erpOrderNo = trim(dto.getErpOrderNo());
        if (erpOrderNo.isEmpty()) {
            throw new RuntimeException("ERP 订单号不能为空");
        }
        if (dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("到货明细不能为空");
        }
        // 幂等：同一 ERP 订单只允许登记一次，否则会产生两张单 → 各上架一次 → 库存翻倍 + 两张金蝶单。
        // 不加唯一索引（历史数据可能同号多条、source_type 可空），在服务层按「未作废」判重。
        requireNotRegistered(sourceType, erpOrderNo);

        boolean consigned = WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(sourceType);
        boolean direct = WmsConstants.INBOUND_HANDLING_DIRECT.equals(handlingType);
        String consignorId = trim(dto.getConsignorId());
        if (consigned && consignorId.isEmpty()) {
            throw new RuntimeException("受托到货必须指定受托方（consignorId）");
        }
        // 外购直入必须带供应商：金蝶采购收料单的 FSupplierID 是必填项，缺了回写必失败；
        // 放在 ERP 查询之前，避免白调一次接口。
        // 大改小（CONVERT）原始材料不入 WMS 库存、也不回写采购收料单（产出走 PRD_INSTOCK），
        // 因此不强制供应商——否则会对不需要该字段的路径过度校验。
        if (!consigned && direct && trim(dto.getSupplierId()).isEmpty()) {
            throw new RuntimeException("外购直入必须指定供应商（supplierId），否则采购收料单回写会缺少 FSupplierID");
        }

        // 1) ERP 订单校验：金蝶侧查不到这张单就不允许登记（开关关/未配 formId 同样失败）
        queryErpOrder(consigned, erpOrderNo);

        // 2) 供应商（外购）/ 受托方（受托，客户档案）：两种来源写不同的三列，不混用
        WmsSupplier supplier = null;
        WmsCustomer customer = null;
        if (consigned) {
            customer = customerService.getById(consignorId);
            if (customer == null) {
                throw new RuntimeException("受托方不存在");
            }
        } else if (!trim(dto.getSupplierId()).isEmpty()) {
            supplier = supplierService.getById(trim(dto.getSupplierId()));
            if (supplier == null) {
                throw new RuntimeException("供应商不存在");
            }
        }

        // 3) 明细预校验（物料存在、分类为原材料、数量/批次合法），再落单
        List<WmsInboundDetail> details = buildDetails(dto);

        Date now = new Date();
        WmsInboundOrder order = new WmsInboundOrder()
                .setInboundType(consigned ? WmsConstants.INBOUND_TYPE_RAW_CONSIGNED
                        : WmsConstants.INBOUND_TYPE_RAW_PURCHASE)
                .setSourceType(sourceType)
                .setPurchaseOrderNo(erpOrderNo)
                // supplier_* 只给外购；受托单不写，避免与 consignor_* 混用造成歧义
                .setSupplierId(supplier == null ? null : supplier.getId())
                .setSupplierCode(supplier == null ? null : supplier.getSupplierCode())
                .setSupplierName(supplier == null ? null : supplier.getSupplierName())
                .setConsignorId(customer == null ? null : customer.getId())
                .setConsignorCode(customer == null ? null : customer.getCustomerCode())
                .setConsignorName(customer == null ? null : customer.getCustomerName())
                .setSyncStatus(direct ? WmsConstants.SYNC_STATUS_PENDING : WmsConstants.SYNC_STATUS_NOT_REQUIRED);
        if (direct) {
            // 跳过检验：直接待分配，等无检验上架
            order.setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE);
        } else {
            // 大改小：到货只登记，原材料不入 WMS 库存，登记即终态
            order.setInboundStatus(WmsConstants.INBOUND_STATUS_AUDITED);
            order.setAuditBy(operator);
            order.setAuditTime(now);
        }
        order.setCreateBy(operator);
        order.setCreateTime(now);
        order.setUpdateBy(operator);
        order.setUpdateTime(now);
        saveWithFreshOrderNo(order);

        for (WmsInboundDetail detail : details) {
            detail.setInboundId(order.getId());
            detail.setCreateBy(operator);
            detail.setCreateTime(now);
            detail.setUpdateBy(operator);
            detail.setUpdateTime(now);
            detailMapper.insert(detail);
        }
        log.info("原材料到货登记：单号={}，来源={}，处理={}，ERP 单号={}，状态={}，明细 {} 行",
                order.getOrderNo(), sourceType, handlingType, erpOrderNo,
                order.getInboundStatus(), details.size());
        return order;
    }

    /**
     * 同一来源类型下同一 ERP 订单只允许有一张未作废单据。
     *
     * <p>用 {@code selectList + LIMIT 1} 而不是 {@code selectOne}：历史数据可能同号多条，
     * {@code selectOne} 会直接抛 TooManyResultsException，把「已登记」误报成系统错误。
     */
    private void requireNotRegistered(String sourceType, String erpOrderNo) {
        List<WmsInboundOrder> existing = baseMapper.selectList(Wrappers.<WmsInboundOrder>query()
                .eq("source_type", sourceType)
                .eq("purchase_order_no", erpOrderNo)
                .ne("inbound_status", WmsConstants.INBOUND_STATUS_VOIDED)
                .orderByDesc("create_time")
                .last("LIMIT 1"));
        if (existing != null && !existing.isEmpty()) {
            throw new RuntimeException("该 ERP 订单已登记，入库单号：" + existing.get(0).getOrderNo()
                    + "，请勿重复登记");
        }
    }

    /** 查金蝶订单：开关关闭显式失败；查不到行视为订单不存在。 */
    private void queryErpOrder(boolean consigned, String erpOrderNo) {
        if (!rawOrderReader.isEnabled()) {
            throw new RuntimeException(KD_NOT_READY);
        }
        WmsKingdeeResult result = consigned
                ? rawOrderReader.queryConsignedOrder(erpOrderNo)
                : rawOrderReader.queryPurchaseOrder(erpOrderNo);
        String billName = consigned ? "受托加工订单" : "采购订单";
        if (result == null || !result.isSuccess()) {
            throw new RuntimeException("查询金蝶" + billName + "[" + erpOrderNo + "]失败："
                    + (result == null ? "无返回" : result.getMessage()));
        }
        if (result.getRows() == null || result.getRows().isEmpty()) {
            throw new RuntimeException("金蝶" + billName + "[" + erpOrderNo + "]不存在或未审核，不能登记到货");
        }
    }

    /** 明细校验并转成实体（不落库）：物料必须存在且分类为原材料。 */
    private List<WmsInboundDetail> buildDetails(WmsRawInboundDTO dto) {
        List<WmsInboundDetail> rows = new ArrayList<>();
        for (WmsRawInboundDTO.Item item : dto.getDetails()) {
            if (item == null || oConvertUtils.isEmpty(item.getMaterialId())) {
                throw new RuntimeException("到货明细的物料不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity().signum() <= 0) {
                throw new RuntimeException("到货数量必须大于 0");
            }
            String batchNo = trim(item.getBatchNo());
            if (batchNo.isEmpty()) {
                throw new RuntimeException("批次号不能为空");
            }
            WmsMaterial material = materialService.getById(item.getMaterialId());
            if (material == null) {
                throw new RuntimeException("物料不存在");
            }
            String categoryType = resolveCategoryType(material);
            // 辅料单独给一句人看得懂的话：不是「类型填错了」，而是本就不在 WMS 范围——
            // 蓝图 V2.4 只做半成品立体仓 + 成品敏捷仓，辅料该去线边柜，而线边柜 XB01 已停用，
            // 若只说「不是原材料」，用户会以为换个物料就行（2026-11-05 用户确认的口径）。
            if (WmsConstants.CATEGORY_TYPE_AUXILIARY.equals(categoryType)) {
                throw new RuntimeException(String.format(
                        "物料[%s]属于辅料，辅料不在 WMS 范围内，不能登记到货（线边柜已停用）",
                        material.getMaterialName()));
            }
            if (!WmsConstants.CATEGORY_TYPE_RAW.equals(categoryType)) {
                throw new RuntimeException(String.format("物料[%s]不是原材料，不能走外购/受托原材料入库",
                        material.getMaterialName()));
            }
            WmsInboundDetail detail = new WmsInboundDetail()
                    .setMaterialId(material.getId())
                    .setMaterialCode(material.getMaterialCode())
                    .setMaterialName(material.getMaterialName())
                    .setQuantity(item.getQuantity())
                    .setBatchNo(batchNo)
                    .setSourceMaterialCode(trim(item.getSourceMaterialCode()).isEmpty()
                            ? null : item.getSourceMaterialCode().trim());
            rows.add(detail);
        }
        return rows;
    }

    // ------------------------------------------------------------ 无检验上架

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsRawPutawayResultVO putawayRaw(String inboundId, WmsPutawayDTO dto, String operator) {
        if (oConvertUtils.isEmpty(inboundId)) {
            throw new RuntimeException("入库单ID不能为空");
        }
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        if (!WmsConstants.INBOUND_SOURCE_PURCHASE.equals(order.getSourceType())
                && !WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType())) {
            throw new RuntimeException("该单据不是外购/受托原材料到货单，不能走无检验上架");
        }
        if (!WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE.equals(order.getInboundStatus())
                && !WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY.equals(order.getInboundStatus())) {
            // 大改小单据登记即「已审核」、且不产生库存行，走到这里必须拒绝
            throw new RuntimeException("当前状态不允许上架");
        }

        // 金蝶配置前置校验：未启用或 formId 为空必须在写库存之前失败（不静默跳过）
        String formId = requireKingdeeReady(order);
        boolean consigned = WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType());
        // V2.4：受托方在 consignor_id（客户档案），不再借用 supplier_id
        String consignorId = trim(order.getConsignorId());
        if (consigned && consignorId.isEmpty()) {
            throw new RuntimeException("受托入库单缺少受托方，不能上架");
        }

        List<WmsInboundDetail> rows = detailMapper.selectList(
                Wrappers.<WmsInboundDetail>query().eq("inbound_id", inboundId).orderByAsc("id"));
        Map<String, WmsInboundDetail> byId = new HashMap<>();
        for (WmsInboundDetail row : rows) {
            byId.put(row.getId(), row);
        }
        validatePutaway(dto, byId);

        Date now = new Date();
        // 条件认领：并发/重复上架只有一个能生效（与旧 putaway 同一口径）
        int claimed = baseMapper.update(null, Wrappers.<WmsInboundOrder>update()
                .set("inbound_status", WmsConstants.INBOUND_STATUS_AUDITED)
                .set("audit_by", operator)
                .set("audit_time", now)
                .eq("id", inboundId)
                .in("inbound_status",
                        WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE,
                        WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY));
        if (claimed == 0) {
            throw new RuntimeException("当前状态不允许上架");
        }
        order.setInboundStatus(WmsConstants.INBOUND_STATUS_AUDITED);
        order.setAuditBy(operator);
        order.setAuditTime(now);

        for (WmsPutawayDTO.Item item : dto.getDetails()) {
            WmsInboundDetail detail = byId.get(item.getDetailId());
            BigDecimal qty = item.getActualQuantity();
            if (qty.signum() > 0) {
                WmsLocation location = requirePutawayLocation(item.getLocationId(), detail);
                occupyLocation(location);
                writeInventory(detail, location, qty, consigned, consignorId, operator, now);
            }
            detail.setActualQuantity(qty);
            detail.setLocationId(oConvertUtils.isEmpty(item.getLocationId())
                    ? detail.getLocationId() : item.getLocationId());
            detail.setUpdateBy(operator);
            detail.setUpdateTime(now);
            detailMapper.updateById(detail);
        }

        // 回写金蝶：失败不回滚库存（库存与单据状态已生效），只落同步失败；
        // 结果必须随响应返回，前端才能如实告知用户「库存已入账但回写失败」。
        WmsRawPutawayResultVO result = syncToKingdee(order, formId, consigned, now);
        log.info("原材料上架完成：单号={}，来源={}，状态={}，同步={}", order.getOrderNo(),
                order.getSourceType(), WmsConstants.INBOUND_STATUS_AUDITED, result.getSyncStatus());
        return result;
    }

    // ------------------------------------------------------------ 回写补偿

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsRawPutawayResultVO resync(String inboundId, String operator) {
        if (oConvertUtils.isEmpty(inboundId)) {
            throw new RuntimeException("入库单ID不能为空");
        }
        WmsInboundOrder order = getById(inboundId);
        if (order == null) {
            throw new RuntimeException("单据不存在");
        }
        if (!WmsConstants.INBOUND_SOURCE_PURCHASE.equals(order.getSourceType())
                && !WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType())) {
            throw new RuntimeException("该单据不是外购/受托原材料到货单，不能重试回写");
        }
        if (!WmsConstants.INBOUND_STATUS_AUDITED.equals(order.getInboundStatus())) {
            throw new RuntimeException("仅已审核单据可重试回写");
        }
        // 幂等：已经同步成功的直接返回，绝不重复开单
        if (WmsConstants.SYNC_STATUS_SUCCESS.equals(order.getSyncStatus())) {
            return resultOf(order);
        }
        // 大改小（CONVERT）登记即终态、原材料不入 WMS 库存，不存在可回写的入库单
        if (WmsConstants.SYNC_STATUS_NOT_REQUIRED.equals(order.getSyncStatus())) {
            throw new RuntimeException("该单据无需同步（大改小登记即终态），不能重试回写");
        }

        String formId = requireKingdeeReady(order);
        boolean consigned = WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType());
        Date now = new Date();
        String erpBillNo = trim(order.getErpBillNo());
        if (!erpBillNo.isEmpty()) {
            // 上次 Save 已建出草稿：只补 Submit，不能再 Save（会重复开单）
            log.info("原材料回写重试：单号={}，已有金蝶单={}，只补提交", order.getOrderNo(), erpBillNo);
            return submitExisting(order, formId, erpBillNo, now);
        }
        log.info("原材料回写重试：单号={}，完整 Save→Submit", order.getOrderNo());
        return syncToKingdee(order, formId, consigned, now);
    }

    /** 上架明细预校验（纯校验不写库）：覆盖全部明细、数量非负、至少一行大于 0。 */
    private void validatePutaway(WmsPutawayDTO dto, Map<String, WmsInboundDetail> byId) {
        if (dto == null || dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("上架明细不能为空");
        }
        Set<String> submitted = new HashSet<>();
        for (WmsPutawayDTO.Item item : dto.getDetails()) {
            if (item == null || oConvertUtils.isEmpty(item.getDetailId())) {
                throw new RuntimeException("上架明细的明细ID不能为空");
            }
            if (!submitted.add(item.getDetailId())) {
                throw new RuntimeException("上架明细不能重复提交");
            }
            if (byId.get(item.getDetailId()) == null) {
                throw new RuntimeException("上架明细与单据不匹配");
            }
            if (item.getActualQuantity() == null || item.getActualQuantity().signum() < 0) {
                throw new RuntimeException("实收数量不能为负");
            }
        }
        if (!submitted.equals(byId.keySet())) {
            throw new RuntimeException("上架明细必须覆盖单据全部明细行");
        }
        int positiveRows = 0;
        for (WmsPutawayDTO.Item item : dto.getDetails()) {
            if (item.getActualQuantity().signum() > 0) {
                positiveRows++;
                if (oConvertUtils.isEmpty(item.getLocationId())) {
                    throw new RuntimeException("实收大于 0 的行必须指定库位");
                }
            }
        }
        if (positiveRows == 0) {
            throw new RuntimeException("整单实收不能全为 0");
        }
    }

    /** 库位校验：存在、未盘点冻结、空闲、库区类型与物料（原材料→立库）匹配。 */
    private WmsLocation requirePutawayLocation(String locationId, WmsInboundDetail detail) {
        WmsLocation location = locationMapper.selectById(locationId);
        if (location == null) {
            throw new RuntimeException("库位不存在");
        }
        WmsStocktakeFreezeGuard.assertNotFrozen(location);
        if (!WmsConstants.LOCATION_STATUS_IDLE.equals(location.getStatus())) {
            throw new RuntimeException("库位不空闲或不存在");
        }
        WmsWarehouse warehouse = warehouseService.getById(location.getWarehouseId());
        WmsMaterial material = materialService.getById(detail.getMaterialId());
        if (material == null) {
            throw new RuntimeException("物料不存在或已删除，无法上架");
        }
        String expectedType = WmsInboundRouting.toWarehouseType(resolveCategoryType(material));
        if (warehouse == null || expectedType == null || !expectedType.equals(warehouse.getWarehouseType())) {
            throw new RuntimeException("库位类型与物料不匹配");
        }
        return location;
    }

    /** 占用库位：条件更新，被并发占用时影响 0 行并回滚。 */
    private void occupyLocation(WmsLocation location) {
        int affected = locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                .set("status", WmsConstants.LOCATION_STATUS_OCCUPIED)
                .eq("id", location.getId())
                .eq("status", WmsConstants.LOCATION_STATUS_IDLE));
        if (affected == 0) {
            throw new RuntimeException("库位已被占用，请重新分配");
        }
    }

    /**
     * 写库存：所有权两列显式写入（六列唯一键据此区分自有/受托）。
     *
     * <p>自建 id：{@code upsertInventory} 是自定义 SQL，MyBatis-Plus 不会为其生成主键。
     */
    private void writeInventory(WmsInboundDetail detail, WmsLocation location, BigDecimal qty,
                                boolean consigned, String consignorId, String operator, Date now) {
        WmsInventory inventory = new WmsInventory()
                .setMaterialId(detail.getMaterialId())
                .setLocationId(location.getId())
                .setBatchNo(detail.getBatchNo())
                // container_code 是 NOT NULL：立体库不按料箱管理，必须显式给空串
                .setContainerCode("")
                .setQuantity(qty)
                .setOwnershipType(consigned ? WmsConstants.OWNERSHIP_CONSIGNED : WmsConstants.OWNERSHIP_OWNED)
                .setConsignorId(consigned ? consignorId : "")
                .setFirstInboundTime(now);
        // 审计列声明在 JeecgEntity 上（链式 setter 返回基类），只能逐条赋值
        inventory.setCreateBy(operator);
        inventory.setCreateTime(now);
        inventory.setUpdateBy(operator);
        inventory.setUpdateTime(now);
        // 主键必须自建：upsertInventory 是自定义 SQL，MyBatis-Plus 不会为其生成 ASSIGN_ID
        inventory.setId(IdWorker.getIdStr());
        inventoryMapper.upsertInventory(inventory);
    }

    /** 校验金蝶同步就绪（开关 + writer 对应 formId），返回 formId 供 Submit 复用。 */
    private String requireKingdeeReady(WmsInboundOrder order) {
        boolean consigned = WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType());
        boolean enabled = consigned ? consignedInboundWriter.isEnabled() : purchaseReceiveWriter.isEnabled();
        WmsConfigKey formKey = consigned
                ? WmsConfigKey.KD_FORM_CONSIGNED_INBOUND : WmsConfigKey.KD_FORM_PURCHASE_INBOUND;
        String formId = trim(configService.getString(formKey.getKey(), ""));
        if (!enabled || formId.isEmpty()) {
            throw new RuntimeException(KD_NOT_READY);
        }
        return formId;
    }

    /**
     * 回写并提交金蝶单据（不抛异常）。
     *
     * <p>配置就绪由 {@link #requireKingdeeReady} 在写库存前判定；这里只处理**调用结果**：
     * 失败记 {@code sync_status=同步失败} 与 {@code last_sync_error}，不中断上架。
     * Save 成功但 Submit 失败时仍回存 {@code erp_bill_no}——ERP 侧已存在该草稿，
     * 不记单号会导致重试时重复建单。
     *
     * <p><b>受托入库单的 Writer 内部已完成 Submit</b>（Push→Save→Submit，2026-11-04 真账套实测），
     * 这里不能再提交一次：对已提交单据重复 Submit 会被金蝶拒绝，把成功流程记成「同步失败」。
     */
    private WmsRawPutawayResultVO syncToKingdee(WmsInboundOrder order, String formId,
                                                boolean consigned, Date now) {
        WmsInboundOrder syncUpdate = new WmsInboundOrder();
        syncUpdate.setId(order.getId());
        try {
            WmsKingdeeResult saved = consigned
                    ? consignedInboundWriter.write(order)
                    : purchaseReceiveWriter.write(order);
            if (saved == null || !saved.isSuccess()) {
                throw new IllegalStateException(failText(saved, "金蝶保存失败"));
            }
            String erpBillNo = trim(saved.getNumber());
            if (erpBillNo.isEmpty()) {
                throw new IllegalStateException("金蝶保存成功但未返回单据编号");
            }
            syncUpdate.setErpBillNo(erpBillNo);

            if (!consigned) {
                WmsKingdeeResult submitted = formClient.submit(formId, List.of(erpBillNo));
                if (submitted == null || !submitted.isSuccess()) {
                    throw new IllegalStateException(failText(submitted, "金蝶提交失败"));
                }
            }
            syncUpdate.setErpSyncTime(now);
            syncUpdate.setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS);
            syncUpdate.setLastSyncError(null);
            order.setErpBillNo(erpBillNo);
            order.setErpSyncTime(now);
        } catch (Exception e) {
            log.error("入库单[{}]回写金蝶失败（库存已入账，不回滚）：{}",
                    order.getOrderNo(), e.getMessage(), e);
            syncUpdate.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED);
            syncUpdate.setLastSyncError(truncate(e.getMessage()));
        }
        syncUpdate.setLastSyncTime(now);
        order.setSyncStatus(syncUpdate.getSyncStatus());
        updateById(syncUpdate);
        return resultOf(syncUpdate);
    }

    /**
     * 已有金蝶单据时只补提交（Save 已建出草稿，再调 Writer 会重复开单）。
     *
     * <p>失败同样只落同步状态，不抛异常——与上架同口径，前端按返回值分支提示。
     */
    private WmsRawPutawayResultVO submitExisting(WmsInboundOrder order, String formId,
                                                 String erpBillNo, Date now) {
        WmsInboundOrder syncUpdate = new WmsInboundOrder();
        syncUpdate.setId(order.getId());
        syncUpdate.setErpBillNo(erpBillNo);
        try {
            WmsKingdeeResult submitted = formClient.submit(formId, List.of(erpBillNo));
            if (submitted == null || !submitted.isSuccess()) {
                throw new IllegalStateException(failText(submitted, "金蝶提交失败"));
            }
            syncUpdate.setErpSyncTime(now);
            syncUpdate.setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS);
            syncUpdate.setLastSyncError(null);
            order.setErpSyncTime(now);
        } catch (Exception e) {
            log.error("入库单[{}]补提交金蝶失败：{}", order.getOrderNo(), e.getMessage(), e);
            syncUpdate.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED);
            syncUpdate.setLastSyncError(truncate(e.getMessage()));
        }
        syncUpdate.setLastSyncTime(now);
        order.setSyncStatus(syncUpdate.getSyncStatus());
        updateById(syncUpdate);
        return resultOf(syncUpdate);
    }

    /** 单据/补丁对象 → 上架结果 VO。 */
    private WmsRawPutawayResultVO resultOf(WmsInboundOrder order) {
        return new WmsRawPutawayResultVO()
                .setSyncStatus(order.getSyncStatus())
                .setErpBillNo(order.getErpBillNo())
                .setLastSyncError(order.getLastSyncError());
    }

    private String failText(WmsKingdeeResult result, String fallback) {
        if (result == null) {
            return fallback;
        }
        String message = result.getMessage();
        return oConvertUtils.isEmpty(message) ? fallback : fallback + "：" + message;
    }

    /** 截断同步错误到列宽上限，避免超长文案让 updateById 在严格模式下失败并回滚上架。 */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text
                : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    /** 取物料挂靠分类的类型（口径 10：不做向上继承；分类缺失视为空）。 */
    private String resolveCategoryType(WmsMaterial material) {
        if (oConvertUtils.isEmpty(material.getCategoryId())) {
            return null;
        }
        WmsMaterialCategory category = categoryService.getById(material.getCategoryId());
        return category == null ? null : category.getCategoryType();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 保存入库单；单号在并发登记时可能撞唯一索引，重新取号重试（最多 3 次）。
     */
    private void saveWithFreshOrderNo(WmsInboundOrder order) {
        for (int i = 0; ; i++) {
            try {
                order.setOrderNo(nextOrderNo(ORDER_NO_PREFIX));
                save(order);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (i >= 2) {
                    throw new RuntimeException("入库单号生成冲突，请重试");
                }
            }
        }
    }

    /** 生成单号：SL + yyyyMMdd + 4 位当日序号（当日最大号 +1）。包级可见，便于单测打桩。 */
    String nextOrderNo(String prefix) {
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        List<WmsInboundOrder> latest = baseMapper.selectList(
                Wrappers.<WmsInboundOrder>query()
                        .likeRight("order_no", prefix + date + "%")
                        .orderByDesc("order_no")
                        .last("LIMIT 1"));
        int next = 1;
        if (latest != null && !latest.isEmpty() && latest.get(0).getOrderNo() != null) {
            next = Integer.parseInt(latest.get(0).getOrderNo().substring(prefix.length() + date.length())) + 1;
        }
        return prefix + date + String.format("%04d", next);
    }

    @Override
    public IPage<WmsInboundOrder> listPage(int pageNo, int pageSize, String sourceType,
                                           String inboundStatus, String orderNo, String supplierName) {
        QueryWrapper<WmsInboundOrder> qw = new QueryWrapper<>();
        // 只取原材料通道的单据：source_type 为空的都是旧采购收料/生产入库，不属本页
        if (oConvertUtils.isNotEmpty(sourceType)) {
            qw.eq("source_type", sourceType.trim());
        } else {
            qw.in("source_type", WmsConstants.INBOUND_SOURCE_PURCHASE,
                    WmsConstants.INBOUND_SOURCE_CONSIGNED);
        }
        if (oConvertUtils.isNotEmpty(inboundStatus)) {
            qw.eq("inbound_status", inboundStatus.trim());
        }
        if (oConvertUtils.isNotEmpty(orderNo)) {
            String kw = orderNo.trim();
            // purchase_order_no 是列表「ERP 订单号」列展示的字段，必须一起纳入搜索，
            // 否则用户照着列表上看到的号去搜会得到全量
            qw.and(w -> w.like("order_no", kw).or().like("erp_bill_no", kw)
                    .or().like("purchase_order_no", kw));
        }
        if (oConvertUtils.isNotEmpty(supplierName)) {
            // 列表只有一个「供应商/受托方」搜索框：外购看 supplier_name，受托看 consignor_name
            String kw = supplierName.trim();
            qw.and(w -> w.like("supplier_name", kw).or().like("consignor_name", kw));
        }
        qw.orderByDesc("create_time");
        return page(new Page<>(pageNo, pageSize), qw);
    }
}
