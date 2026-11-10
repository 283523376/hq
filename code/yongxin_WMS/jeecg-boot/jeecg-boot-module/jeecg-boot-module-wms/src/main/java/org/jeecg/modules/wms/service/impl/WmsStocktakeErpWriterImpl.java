package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 盘点差异回写金蝶——盘盈走其他入库单（STK_Miscellaneous/QTRK）、
 * 盘亏走其他出库单（STK_MisDelivery/QTCK），两侧**独立**调用独立记日志。
 *
 * <p>金蝶测试账套没有标准盘点单据（spec F4），所以默认载体是其他出入库单；
 * 客户后续建盘点单（E2）时切 {@code stocktake.writeback-mode=STOCK_COUNT} 再实现。
 *
 * <p>单号以金蝶为准（2026-10-28 改造）：不传 FBillNo（原先的 {@code stocktakeNo-GY/-GK}
 * 已废弃），保存后把金蝶返回的 QTRK…/QTCK… 单号分号拼接回存 {@code erp_bill_no}。
 *
 * <p>配置键是 {@code wms.sync.erp.kingdee.stocktake.stock-code}——spec §7 的
 * {@code wms.stocktake.*} 前缀是笔误，以本类常量注释为准（终审 I3 的教训：文案必须
 * 与 @ConfigurationProperties 真正读到的键一致）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
public class WmsStocktakeErpWriterImpl {

    public static final String FORM_ID_GAIN = "STK_Miscellaneous";
    public static final String FORM_ID_LOSS = "STK_MisDelivery";
    private static final String NOT_CONFIGURED =
            "盘点回写未配置 wms.sync.erp.kingdee.stocktake.stock-code（外部依赖 E2）";

    private final WmsKingdeeProperties properties;
    private final WmsKingdeeFormClient formClient;
    private final WmsStocktakeDetailMapper detailMapper;
    private final WmsStocktakeOrderMapper orderMapper;
    private final WmsMaterialMapper materialMapper;

    public WmsStocktakeErpWriterImpl(WmsKingdeeProperties properties,
                                     WmsKingdeeFormClient formClient,
                                     WmsStocktakeDetailMapper detailMapper,
                                     WmsStocktakeOrderMapper orderMapper,
                                     WmsMaterialMapper materialMapper) {
        this.properties = properties;
        this.formClient = formClient;
        this.detailMapper = detailMapper;
        this.orderMapper = orderMapper;
        this.materialMapper = materialMapper;
    }

    /** 盘盈 → 其他入库单 QTRK。 */
    public WmsOrderSyncResult writeGain(WmsStocktakeOrder order) {
        return write(order, FORM_ID_GAIN, true);
    }

    /** 盘亏 → 其他出库单 QTCK。 */
    public WmsOrderSyncResult writeLoss(WmsStocktakeOrder order) {
        return write(order, FORM_ID_LOSS, false);
    }

    private WmsOrderSyncResult write(WmsStocktakeOrder order, String formId, boolean gain) {
        String stockCode = properties.getStocktake().getStockCode();
        if (!StringUtils.hasText(stockCode)) {
            return WmsOrderSyncResult.fail(NOT_CONFIGURED);
        }
        List<WmsStocktakeDetail> details = detailMapper.selectList(
                new LambdaQueryWrapper<WmsStocktakeDetail>()
                        .eq(WmsStocktakeDetail::getStocktakeId, order.getId()));
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (WmsStocktakeDetail detail : details) {
            if (detail.getActualQty() == null) {
                continue;
            }
            BigDecimal diff = detail.getActualQty()
                    .subtract(detail.getBookQty() == null ? java.math.BigDecimal.ZERO : detail.getBookQty());
            // 严格按符号分流：diff==0 的行两侧都不带（金蝶库存单据 0 数量会直接报错）
            boolean isGain = diff.signum() > 0;
            boolean isLoss = diff.signum() < 0;
            if ((gain && !isGain) || (!gain && !isLoss)) {
                continue;
            }
            // 盘点明细只存 material_id，material_code 一直是空的（2026-11-06 真账套实测：
            // 直接拿 code 会发出 {"FNumber": null}，金蝶报「物料编码是必填项」）。
            // 这里按 id 反查物料编码，查不到就显式失败，不发半成品报文。
            String materialCode = resolveMaterialCode(detail);
            if (!StringUtils.hasText(materialCode)) {
                return WmsOrderSyncResult.fail("盘点明细[" + detail.getId()
                        + "]物料编码为空且按 id 反查失败，无法回写" + formId);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("FMaterialId", mapByNumber(materialCode));
            entry.put("FQty", diff.abs());
            // 批号必须是字典：QTCK（STK_MisDelivery）收到裸字符串会报
            // 「字段Key：FLot…格式错误，不能转换为字典类型」（2026-11-06 真账套实测；
            // 盘盈侧 QTRK 恰好容忍裸串，所以只挂了一半，别据此认为两边口径不同）
            if (StringUtils.hasText(detail.getBatchNo())) {
                entry.put("FLot", mapByNumber(detail.getBatchNo()));
            }
            // 分录仓库两侧都要显式传（2026-11-09 二次实测更正）：
            // - 盘盈缺 FStockId 直接报「收货仓库是必填项」；
            // - 盘亏缺 FStockId 虽能存，但金蝶按**物料默认仓库**落库（实测写成 Y.05 而非盘点的 D.02），
            //   审核时按错仓扣库存必报负库存——所以盘亏也必须传。
            entry.put("FStockId", mapByNumber(properties.getStocktake().getStockCode()));
            // 库存状态只有盘盈侧需要
            if (gain && StringUtils.hasText(properties.getStockStatusId())) {
                entry.put("FStockStatusId", mapByNumber(properties.getStockStatusId()));
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            log.info("盘点单[{}]{}差异为空，跳过回写 {}", order.getStocktakeNo(),
                    gain ? "盘盈" : "盘亏", formId);
            return WmsOrderSyncResult.ok();
        }
        // WmsOrderSyncResult 无带参 ok()，成功结果统一用 ok() + 上层记日志
        // 单号以金蝶为准（2026-10-28 改造）：不传 FBillNo，由金蝶生成 QTRK…/QTCK…，保存后回存
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FDate", java.time.LocalDate.now().toString());
        model.put("FStockId", mapByNumber(properties.getStocktake().getStockCode()));
        // 其他出库单必填之一：部门（或客户），缺了报「部门和客户不允许同时为空！」（真账套实测）
        if (StringUtils.hasText(properties.getStocktake().getDeptNumber())) {
            model.put("FDeptId", mapByNumber(properties.getStocktake().getDeptNumber().trim()));
        }
        model.put("FEntity", entries);
        WmsKingdeeResult result = formClient.save(formId, model);
        if (!result.isSuccess()) {
            return WmsOrderSyncResult.fail("盘点回写[" + formId + "]失败：" + result.getMessage());
        }
        if (result.getNumber() == null || result.getNumber().isEmpty()) {
            return WmsOrderSyncResult.fail("盘点回写[" + formId + "]保存后未返回单号（盘点单 "
                    + order.getStocktakeNo() + "）");
        }
        persistErpBillNo(order, result.getNumber());
        // 保存后提交（A→B）：不提交单据停在 A，金蝶前台无法直接审核（2026-11-09 实测）
        WmsKingdeeResult submitted = formClient.submit(formId, List.of(result.getNumber()));
        if (submitted == null || !submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("盘点回写[" + formId + "]已保存 " + result.getNumber()
                    + " 但提交失败：" + (submitted == null ? "无返回" : submitted.getMessage()));
        }
        return WmsOrderSyncResult.ok();
    }

    /**
     * 回存金蝶单号：盘盈/盘亏是两张独立单据，{@code erp_bill_no} 用分号拼接
     * （如 {@code QTRK10126100001;QTCK10126100002}），已存在则去重不重复追加。
     */
    private void persistErpBillNo(WmsStocktakeOrder order, String erpBillNo) {
        String existing = order.getErpBillNo();
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split(";")) {
                if (!part.isBlank()) {
                    merged.add(part.trim());
                }
            }
        }
        merged.add(erpBillNo);
        String joined = String.join(";", merged);
        if (joined.equals(existing)) {
            return;
        }
        WmsStocktakeOrder patch = new WmsStocktakeOrder();
        patch.setId(order.getId());
        patch.setErpBillNo(joined);
        orderMapper.updateById(patch);
        order.setErpBillNo(joined);
    }

    /** 明细里的物料编码可能为空（历史数据/建单只写了 id），回写前按 id 兜底反查。 */
    private String resolveMaterialCode(WmsStocktakeDetail detail) {
        if (StringUtils.hasText(detail.getMaterialCode())) {
            return detail.getMaterialCode().trim();
        }
        if (!StringUtils.hasText(detail.getMaterialId())) {
            return null;
        }
        WmsMaterial material = materialMapper.selectById(detail.getMaterialId());
        return material == null ? null : material.getMaterialCode();
    }

    private static Map<String, Object> mapByNumber(String number) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("FNumber", number);
        return map;
    }
}
