package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsConversionCreateDTO;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsConversionDetail;
import org.jeecg.modules.wms.entity.WmsConversionOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsConversionDetailMapper;
import org.jeecg.modules.wms.mapper.WmsConversionOrderMapper;
import org.jeecg.modules.wms.service.IWmsConversionService;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 改制单（大改小，V2.4 spec §3.1）：建单 → 完成（生成生产入库单）/ 取消。
 *
 * <p><b>库存口径</b>：投入原材料不入 WMS 库存（ERP 生产订单负责领料记账），WMS 只登记
 * 投入数量；产出在完成时经 {@link IWmsProductionInboundService#submit} 走 RK-002 通道，
 * 复用其立库库位分配、设备任务下发与金蝶回写。
 *
 * <p><b>幂等与并发</b>：状态机只允许 DRAFT→DONE/DRAFT→CANCELLED，且完成/取消都用
 * {@code status='DRAFT'} 条件更新认领——并发重复完成只有一个能翻转状态，
 * 输家在调用生产入库<strong>之前</strong>就被拒绝，因此不会生成第二张入库单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsConversionServiceImpl extends ServiceImpl<WmsConversionOrderMapper, WmsConversionOrder>
        implements IWmsConversionService {

    /** 状态机动作：完成（DRAFT→DONE）。 */
    static final String ACTION_COMPLETE = "complete";

    /** 状态机动作：取消（DRAFT→CANCELLED）。 */
    static final String ACTION_CANCEL = "cancel";

    /** 写入生产入库单备注的来源前缀（溯源关联：改制单 → 生产入库单）。 */
    private static final String SUBMIT_REMARK_PREFIX = "改制单:";

    /** 数量上限，与生产入库 submit 同一口径（decimal(12,3)）。 */
    private static final BigDecimal QTY_MAX = new BigDecimal("999999999.999");

    private final WmsConversionDetailMapper detailMapper;
    private final IWmsWorkOrderService workOrderService;
    private final IWmsMaterialService materialService;
    private final IWmsProductionInboundService productionInboundService;

    public WmsConversionServiceImpl(WmsConversionDetailMapper detailMapper,
                                    IWmsWorkOrderService workOrderService,
                                    IWmsMaterialService materialService,
                                    IWmsProductionInboundService productionInboundService) {
        this.detailMapper = detailMapper;
        this.workOrderService = workOrderService;
        this.materialService = materialService;
        this.productionInboundService = productionInboundService;
    }

    // ------------------------------------------------------------ 建单

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsConversionOrder create(WmsConversionCreateDTO dto, String operator) {
        if (dto == null) {
            throw new RuntimeException("改制单入参不能为空");
        }
        String productionOrderNo = trimRequired(dto.getProductionOrderNo(), "生产订单号不能为空");
        String inputMaterialCode = trimRequired(dto.getInputMaterialCode(), "投入物料编码不能为空");
        String outputMaterialCode = trimRequired(dto.getOutputMaterialCode(), "产出物料编码不能为空");
        BigDecimal inputQty = requireQty(dto.getInputQty(), "投入数量必须大于 0");
        BigDecimal outputQty = requireQty(dto.getOutputQty(), "产出数量必须大于 0");
        String inputBatchNo = trimBatch(dto.getInputBatchNo());
        String outputBatchNo = trimBatch(dto.getOutputBatchNo());

        // 生产订单必须已同步（wms_work_order）且已下达——ERP 生产订单先行是硬前提
        WmsWorkOrder workOrder = workOrderService.getByWorkOrderNo(productionOrderNo);
        if (workOrder == null) {
            throw new RuntimeException("生产订单未同步到 WMS：" + productionOrderNo);
        }
        assertWorkOrderExecutable(workOrder);

        // 投入/产出物料必须存在于物料主数据
        if (!materialService.checkCodeExists(inputMaterialCode, null)) {
            throw new RuntimeException("投入物料不存在：" + inputMaterialCode);
        }
        if (!materialService.checkCodeExists(outputMaterialCode, null)) {
            throw new RuntimeException("产出物料不存在：" + outputMaterialCode);
        }
        // 产出物料必须与生产订单成品一致：完成时的入库单物料取自生产订单（物料=产出物料的前提）
        String workOrderMaterial = workOrder.getMaterialCode() == null
                ? "" : workOrder.getMaterialCode().trim();
        if (!outputMaterialCode.equals(workOrderMaterial)) {
            throw new RuntimeException("产出物料必须与生产订单成品一致（生产订单成品："
                    + workOrder.getMaterialCode() + "）");
        }
        if (outputQty.compareTo(nvl(workOrder.getPlanQty())) > 0) {
            throw new RuntimeException("产出数量超出生产订单计划数量");
        }

        WmsConversionOrder order = new WmsConversionOrder()
                .setConversionNo(nextConversionNo())
                .setProductionOrderNo(productionOrderNo)
                .setInputMaterialCode(inputMaterialCode)
                .setInputBatchNo(inputBatchNo)
                .setInputQty(inputQty)
                .setOutputMaterialCode(outputMaterialCode)
                .setOutputBatchNo(outputBatchNo)
                .setOutputQty(outputQty)
                // 显式置 DRAFT，不依赖 DDL 默认值（2026-11-04 裁决）
                .setStatus(WmsConstants.CONVERSION_STATUS_DRAFT)
                .setOperator(operator);
        WmsUtils.fillCreate(order);
        save(order);

        // 明细两行（IN/OUT）；wms_conversion_detail 无唯一约束，本服务是唯一写入口、一次两行
        saveDetail(order.getId(), WmsConstants.CONVERSION_DIRECTION_IN,
                inputMaterialCode, inputBatchNo, inputQty);
        saveDetail(order.getId(), WmsConstants.CONVERSION_DIRECTION_OUT,
                outputMaterialCode, outputBatchNo, outputQty);
        log.info("改制单创建：单号={}，生产订单={}，投入={}×{}，产出={}×{}",
                order.getConversionNo(), productionOrderNo,
                inputMaterialCode, inputQty, outputMaterialCode, outputQty);
        return order;
    }

    // ------------------------------------------------------------ 完成 / 取消

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(String id, String operator) {
        WmsConversionOrder order = requireOrder(id);
        // 状态机裁决先行：DONE 在这里就被拦下，不会走到生成入库单
        String target = transition(ACTION_COMPLETE, order.getStatus());
        // 条件认领：并发重复完成只有一个能翻转（0 行 = 别人已完成）
        int claimed = baseMapper.update(null, Wrappers.<WmsConversionOrder>update()
                .set("status", target)
                .set("operator", operator)
                .set("update_by", operator)
                .set("update_time", new Date())
                .eq("id", order.getId())
                .eq("status", WmsConstants.CONVERSION_STATUS_DRAFT));
        if (claimed == 0) {
            throw new RuntimeException("改制单已完成");
        }
        submitProductionInbound(order);
        log.info("改制单完成：单号={}，生产订单={}，产出={}×{}，操作人={}",
                order.getConversionNo(), order.getProductionOrderNo(),
                order.getOutputMaterialCode(), order.getOutputQty(), operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id, String operator) {
        WmsConversionOrder order = requireOrder(id);
        String target = transition(ACTION_CANCEL, order.getStatus());
        int claimed = baseMapper.update(null, Wrappers.<WmsConversionOrder>update()
                .set("status", target)
                .set("operator", operator)
                .set("update_by", operator)
                .set("update_time", new Date())
                .eq("id", order.getId())
                .eq("status", WmsConstants.CONVERSION_STATUS_DRAFT));
        if (claimed == 0) {
            // 状态机已放行 DRAFT，走到这里只剩并发改写：让调用方刷新重试
            throw new RuntimeException("改制单状态已变化，请刷新后重试");
        }
        log.info("改制单取消：单号={}，操作人={}", order.getConversionNo(), operator);
    }

    // ------------------------------------------------------------ 查询

    @Override
    public IPage<WmsConversionOrder> listPage(int pageNo, int pageSize, String conversionNo,
                                              String productionOrderNo, String status) {
        QueryWrapper<WmsConversionOrder> qw = new QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(conversionNo)) {
            qw.like("conversion_no", conversionNo.trim());
        }
        if (oConvertUtils.isNotEmpty(productionOrderNo)) {
            qw.like("production_order_no", productionOrderNo.trim());
        }
        if (oConvertUtils.isNotEmpty(status)) {
            qw.eq("status", status);
        }
        qw.orderByDesc("create_time");
        return page(new Page<>(pageNo, pageSize), qw);
    }

    // ------------------------------------------------------------ 状态机（包级可见，便于单测直接驱动）

    /**
     * 改制单状态机——全模块唯一的合法转移表。
     *
     * <pre>DRAFT →(complete)→ DONE；DRAFT →(cancel)→ CANCELLED；
     * RUNNING 为预留态（暂无出边）；DONE/CANCELLED 为终态。</pre>
     *
     * @param action  {@link #ACTION_COMPLETE} / {@link #ACTION_CANCEL}
     * @param current 当前状态
     * @return 目标状态
     * @throws RuntimeException 非法转移：DONE 上重复完成抛「改制单已完成」（上游依赖该文案），
     *                          其余抛「当前状态不允许××」
     */
    static String transition(String action, String current) {
        if (ACTION_COMPLETE.equals(action)) {
            if (WmsConstants.CONVERSION_STATUS_DONE.equals(current)) {
                throw new RuntimeException("改制单已完成");
            }
            if (WmsConstants.CONVERSION_STATUS_DRAFT.equals(current)) {
                return WmsConstants.CONVERSION_STATUS_DONE;
            }
            throw new RuntimeException("当前状态不允许完成，当前状态：" + current);
        }
        if (ACTION_CANCEL.equals(action)) {
            if (WmsConstants.CONVERSION_STATUS_DRAFT.equals(current)) {
                return WmsConstants.CONVERSION_STATUS_CANCELLED;
            }
            throw new RuntimeException("当前状态不允许取消，当前状态：" + current);
        }
        throw new RuntimeException("未知操作：" + action);
    }

    // ------------------------------------------------------------ 内部

    /** 生成生产入库单：产出走 RK-002 通道，备注写「改制单:单号」做溯源关联。 */
    private void submitProductionInbound(WmsConversionOrder order) {
        WmsProductionSubmitDTO dto = new WmsProductionSubmitDTO();
        dto.setWorkOrderNo(order.getProductionOrderNo());
        dto.setGoodQty(order.getOutputQty());
        dto.setBadQty(BigDecimal.ZERO);
        // 产出批次为空时由 submit 回落生产订单批次
        dto.setBatchNo(oConvertUtils.isEmpty(order.getOutputBatchNo())
                ? null : order.getOutputBatchNo());
        // 大改小产出固定是半成品 → 立库（工单产品类型可空，不能依赖它）
        dto.setProductType(WmsConstants.PRODUCT_TYPE_SEMI);
        // 载具码借用改制单号：单号唯一 ⇒ 活跃载具唯一约束天然防重复；同时可溯源
        dto.setCarrierCode(order.getConversionNo());
        dto.setRemark(SUBMIT_REMARK_PREFIX + order.getConversionNo());
        // 技术债 §5-23：结构化关联（此前只有 remark 字符串，反查只能模糊匹配）
        dto.setConversionOrderId(order.getId());
        dto.setSourceMaterialCode(order.getInputMaterialCode());
        productionInboundService.submit(dto);
    }

    private void saveDetail(String conversionId, String direction, String materialCode,
                            String batchNo, BigDecimal qty) {
        WmsConversionDetail detail = new WmsConversionDetail()
                .setConversionId(conversionId)
                .setDirection(direction)
                .setMaterialCode(materialCode)
                .setBatchNo(batchNo)
                .setQty(qty);
        detail.setId(IdWorker.getIdStr());
        WmsUtils.fillCreate(detail);
        detailMapper.insert(detail);
    }

    private WmsConversionOrder requireOrder(String id) {
        if (oConvertUtils.isEmpty(id)) {
            throw new RuntimeException("改制单ID不能为空");
        }
        WmsConversionOrder order = getById(id.trim());
        if (order == null) {
            throw new RuntimeException("改制单不存在");
        }
        return order;
    }

    /** 生产订单必须已下达（已下达/生产中/已完成可改制；待生产、已关闭、已作废拒绝）。 */
    private void assertWorkOrderExecutable(WmsWorkOrder workOrder) {
        String status = workOrder.getWorkOrderStatus();
        if (WmsConstants.WORK_ORDER_STATUS_CANCELLED.equals(status)) {
            throw new RuntimeException("生产订单已作废，不能建改制单");
        }
        if (WmsConstants.WORK_ORDER_STATUS_CLOSED.equals(status)) {
            throw new RuntimeException("生产订单已关闭，不能建改制单");
        }
        if (!WmsConstants.WORK_ORDER_STATUS_RELEASED.equals(status)
                && !WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS.equals(status)
                && !WmsConstants.WORK_ORDER_STATUS_FINISHED.equals(status)) {
            throw new RuntimeException("生产订单未下达，不能建改制单（当前状态：" + status + "）");
        }
    }

    /** 单号：GZ 前缀 + 雪花 ID。包级可见，便于单测打桩。 */
    String nextConversionNo() {
        return WmsConstants.CONVERSION_NO_PREFIX + IdWorker.getIdStr();
    }

    private String trimRequired(String value, String message) {
        if (oConvertUtils.isEmpty(value) || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
        return value.trim();
    }

    private String trimBatch(String batchNo) {
        if (oConvertUtils.isEmpty(batchNo)) {
            return "";
        }
        String trimmed = batchNo.trim();
        if (trimmed.length() > 64) {
            throw new RuntimeException("批次号长度不能超过 64");
        }
        return trimmed;
    }

    private BigDecimal requireQty(BigDecimal qty, String message) {
        if (qty == null || qty.signum() <= 0) {
            throw new RuntimeException(message);
        }
        if (qty.stripTrailingZeros().scale() > 3) {
            throw new RuntimeException("数量精度不能超过 3 位小数");
        }
        if (qty.compareTo(QTY_MAX) > 0) {
            throw new RuntimeException("数量超出可录入范围");
        }
        return qty;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
