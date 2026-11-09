package org.jeecg.modules.wms.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsPrintDocument;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.service.IWmsPrintService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.service.print.WmsPrintDataAssembler;
import org.jeecg.modules.wms.service.store.WmsPrintSnapshotStore;
import org.jeecg.modules.wms.sync.IWmsKingdeePrintReader;
import org.jeecg.modules.wms.sync.WmsKingdeePrintBill;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单据打印编排（DY-001）。
 *
 * <p>链路（spec §3.1）：
 * <ol>
 *   <li>盘盈/盘亏：入参是 PD… 本地盘点单 → 解析回写的逗号合并 {@code erp_bill_no}，按
 *       QTRK/QTCK 前缀取单号（未回写直接拒绝）；入参本身就是金蝶单号则校验前缀后直查。</li>
 *   <li>销售出库：入参命中本地销售出库单（XS…）→ 本地明细组装为底，再尝试金蝶补价税
 *       （失败仅 warn 不阻断）；本地查无（XSCK…）→ 金蝶链路。</li>
 *   <li>其余 4 类：金蝶链路。</li>
 * </ol>
 * 金蝶链路失败/未启用：有快照回退快照（标注 {@code snapshotTime} + warn），无快照才报错。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Slf4j
@Service
public class WmsPrintServiceImpl implements IWmsPrintService {

    private static final TypeReference<List<Map<String, String>>> ROWS_TYPE =
            new TypeReference<List<Map<String, String>>>() {
            };

    /** 打印抬头默认公司名（与样单一致；系统配置 print.company.name 可覆盖）。 */
    private static final String DEFAULT_COMPANY_NAME = "宜昌永鑫精工科技股份有限公司";

    private final WmsPrintSnapshotStore store;
    private final IWmsKingdeePrintReader reader;
    private final WmsPrintDataAssembler assembler;
    private final WmsStocktakeOrderMapper stocktakeMapper;
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final WmsMaterialMapper materialMapper;
    private final IWmsSystemConfigService configService;

    public WmsPrintServiceImpl(WmsPrintSnapshotStore store, IWmsKingdeePrintReader reader,
                               WmsPrintDataAssembler assembler, WmsStocktakeOrderMapper stocktakeMapper,
                               WmsOutboundOrderMapper outboundMapper,
                               WmsOutboundDetailMapper detailMapper, WmsMaterialMapper materialMapper,
                               IWmsSystemConfigService configService) {
        this.store = store;
        this.reader = reader;
        this.assembler = assembler;
        this.stocktakeMapper = stocktakeMapper;
        this.outboundMapper = outboundMapper;
        this.detailMapper = detailMapper;
        this.materialMapper = materialMapper;
        this.configService = configService;
    }

    @Override
    public WmsPrintDataVO printData(String billType, String billNo) {
        String type = trim(billType);
        String no = trim(billNo);
        if (type.isEmpty()) {
            throw new RuntimeException("单据类型不能为空");
        }
        if (no.isEmpty()) {
            throw new RuntimeException("单据号不能为空");
        }
        WmsPrintDataVO vo;
        if (WmsConstants.PRINT_BILL_PROFIT.equals(type) || WmsConstants.PRINT_BILL_LOSS.equals(type)) {
            vo = inventoryRoute(type, no);
        } else if (WmsConstants.PRINT_BILL_SALES_OUTSTOCK.equals(type)) {
            vo = salesOutstockRoute(no);
        } else if (!isSupported(type)) {
            throw new RuntimeException("不支持的打印单据类型：" + type);
        } else {
            vo = kingdeeRoute(type, no, null, true);
        }
        applyCompanyConfig(vo);
        return vo;
    }

    /**
     * 抬头公司名与落款公司信息块：系统配置（print.company.name / print.company.info）优先，
     * 未配置时用模板内置默认值——换打印主体不改代码（spec §5-5）。
     */
    private void applyCompanyConfig(WmsPrintDataVO vo) {
        String name = trim(configService.getString(WmsConfigKey.PRINT_COMPANY_NAME.getKey(), ""));
        vo.getExtras().put("公司名", name.isEmpty() ? DEFAULT_COMPANY_NAME : name);
        String info = configService.getString(WmsConfigKey.PRINT_COMPANY_INFO.getKey(), "");
        if (oConvertUtils.isNotEmpty(info)) {
            List<String> lines = new ArrayList<>();
            for (String line : info.split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
            if (!lines.isEmpty()) {
                vo.getExtras().put("公司信息块", lines);
            }
        }
    }

    // ------------------------------------------------------------ 盘盈/盘亏

    private WmsPrintDataVO inventoryRoute(String type, String billNo) {
        WmsStocktakeOrder order = stocktakeMapper.selectOne(new QueryWrapper<WmsStocktakeOrder>()
                .eq("stocktake_no", billNo)
                .last("LIMIT 1"));
        if (order != null) {
            String erpNo = pickErpBillNo(order.getErpBillNo(), type);
            if (erpNo.isEmpty()) {
                if (oConvertUtils.isEmpty(order.getErpBillNo())) {
                    throw new RuntimeException("该盘点单未回写金蝶，暂无" + (isProfit(type) ? "盘盈" : "盘亏")
                            + "单可打印");
                }
                // 回写过金蝶但没有该类型单据（如只有盘亏单 QTCK，却要打盘盈单）
                throw new RuntimeException("该盘点单没有" + (isProfit(type) ? "盘盈单" : "盘亏单")
                        + "（金蝶单号：" + order.getErpBillNo() + "）");
            }
            return kingdeeRoute(type, erpNo, order.getStocktakeNo(), true);
        }
        // 入参本身就是金蝶单号：校验前缀与类型匹配
        requirePrefixMatches(type, billNo);
        return kingdeeRoute(type, billNo, null, true);
    }

    /** 从逗号合并的 erp_bill_no 中按前缀取（QTRK=盘盈、QTCK=盘亏）。 */
    private String pickErpBillNo(String erpBillNo, String type) {
        String want = isProfit(type) ? WmsConstants.PRINT_NO_PREFIX_QTRK : WmsConstants.PRINT_NO_PREFIX_QTCK;
        if (oConvertUtils.isEmpty(erpBillNo)) {
            return "";
        }
        for (String part : erpBillNo.split("[,，;；\\s]+")) {
            String p = part.trim();
            if (p.toUpperCase().startsWith(want)) {
                return p;
            }
        }
        return "";
    }

    private void requirePrefixMatches(String type, String billNo) {
        String want = isProfit(type) ? WmsConstants.PRINT_NO_PREFIX_QTRK : WmsConstants.PRINT_NO_PREFIX_QTCK;
        if (!billNo.toUpperCase().startsWith(want)) {
            throw new RuntimeException("单据类型与单号不匹配：" + (isProfit(type) ? "盘盈单" : "盘亏单")
                    + " 应使用 " + want + " 开头的金蝶单号");
        }
    }

    private static boolean isProfit(String type) {
        return WmsConstants.PRINT_BILL_PROFIT.equals(type);
    }

    // ------------------------------------------------------------ 销售出库

    private WmsPrintDataVO salesOutstockRoute(String billNo) {
        WmsOutboundOrder order = outboundMapper.selectOne(new QueryWrapper<WmsOutboundOrder>()
                .eq("outbound_no", billNo)
                .eq("outbound_type", WmsConstants.OUTBOUND_TYPE_SALES)
                .last("LIMIT 1"));
        if (order == null) {
            return kingdeeRoute(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, billNo, null, true);
        }
        List<WmsOutboundDetail> details = detailMapper.selectList(
                new QueryWrapper<WmsOutboundDetail>().eq("outbound_id", order.getId())
                        .orderByAsc("line_no"));
        if (details == null || details.isEmpty()) {
            throw new RuntimeException("销售出库单[" + billNo + "]没有明细，无法打印");
        }
        Map<String, String> specByCode = loadSpecs(details);
        WmsPrintDataVO vo = assembler.assembleSalesOutstockLocal(order, details, specByCode);
        // 已回写金蝶的本地单：尝试补齐价税等金蝶字段；失败只 warn（本地数据已够打印）
        if (oConvertUtils.isNotEmpty(order.getErpBillNo())) {
            try {
                Optional<WmsKingdeePrintBill> bill = reader.isEnabled()
                        ? reader.queryApproved(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, order.getErpBillNo())
                        : Optional.empty();
                if (bill.isPresent()) {
                    store.upsert(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, order.getErpBillNo(),
                            JSON.toJSONString(bill.get().getRows()));
                    vo.getExtras().put("erpBillNo", order.getErpBillNo());
                }
            } catch (RuntimeException e) {
                log.warn("销售出库单[{}]金蝶补字段失败，仅打印本地数据：{}", billNo, e.getMessage());
                vo.getExtras().put("warn", "金蝶价税信息暂不可用：" + e.getMessage());
            }
        }
        return vo;
    }

    private Map<String, String> loadSpecs(List<WmsOutboundDetail> details) {
        Map<String, String> specs = new LinkedHashMap<>();
        for (WmsOutboundDetail d : details) {
            if (oConvertUtils.isEmpty(d.getMaterialId()) || oConvertUtils.isEmpty(d.getMaterialCode())) {
                continue;
            }
            try {
                org.jeecg.modules.wms.entity.WmsMaterial material = materialMapper.selectById(d.getMaterialId());
                if (material != null && oConvertUtils.isNotEmpty(material.getSpec())) {
                    specs.put(d.getMaterialCode(), material.getSpec());
                }
            } catch (RuntimeException e) {
                log.warn("物料[{}]规格查询失败，规格列留空：{}", d.getMaterialCode(), e.getMessage());
            }
        }
        return specs;
    }

    // ------------------------------------------------------------ 金蝶链路（含快照回退）

    /**
     * @param stocktakeNo 盘盈亏来源的 WMS 盘点单号（作「物料盘点作业编码」回落值）；其他类型传 null
     * @param allowSnapshotFallback 失败时是否允许回退快照（本任务恒 true，保留扩展位）
     */
    private WmsPrintDataVO kingdeeRoute(String type, String erpBillNo, String stocktakeNo,
                                        boolean allowSnapshotFallback) {
        RuntimeException failure = null;
        if (reader.isEnabled()) {
            try {
                Optional<WmsKingdeePrintBill> bill = reader.queryApproved(type, erpBillNo);
                if (bill.isEmpty()) {
                    throw new RuntimeException("单据不存在或未审核，不能打印（单号：" + erpBillNo
                            + "，单据类型：" + type + "）");
                }
                store.upsert(type, erpBillNo, JSON.toJSONString(bill.get().getRows()));
                return assemble(type, bill.get(), stocktakeNo, null);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                failure = e;
                log.warn("金蝶单据[{}]查询失败，尝试回退快照：{}", erpBillNo, e.getMessage());
            }
        } else {
            failure = new RuntimeException("金蝶同步未启用，无法查询该单据；请先启用金蝶同步或联系管理员配置");
        }

        Optional<WmsPrintDocument> snapshot = allowSnapshotFallback
                ? store.find(type, erpBillNo) : Optional.empty();
        if (snapshot.isPresent()) {
            WmsPrintDocument doc = snapshot.get();
            List<Map<String, String>> rows = JSON.parseObject(doc.getPayload(), ROWS_TYPE);
            WmsKingdeePrintBill bill = new WmsKingdeePrintBill()
                    .setBillType(type).setBillNo(erpBillNo)
                    .setRows(rows == null ? List.of() : rows);
            return assemble(type, bill, stocktakeNo, doc.getSnapshotTime())
                    .setExtras(mergeWarn(null, failure.getMessage()));
        }
        throw failure;
    }

    private WmsPrintDataVO assemble(String type, WmsKingdeePrintBill bill, String stocktakeNo,
                                    java.util.Date snapshotTime) {
        WmsPrintDataVO vo;
        if (WmsConstants.PRINT_BILL_PROFIT.equals(type) || WmsConstants.PRINT_BILL_LOSS.equals(type)) {
            vo = assembler.assembleInventoryList(type, bill, stocktakeNo);
        } else if (WmsConstants.PRINT_BILL_MISC_DELIVERY.equals(type)) {
            vo = assembler.assembleMiscDelivery(bill);
        } else if (WmsConstants.PRINT_BILL_MISC_INSTOCK.equals(type)) {
            vo = assembler.assembleMiscInstock(bill);
        } else if (WmsConstants.PRINT_BILL_SUB_PICK.equals(type)) {
            vo = assembler.assembleSubcontractPick(bill, Map.of());
        } else if (WmsConstants.PRINT_BILL_SALES_OUTSTOCK.equals(type)) {
            vo = assembler.assembleSalesOutstock(bill);
        } else if (WmsConstants.PRINT_BILL_SALES_RETURN.equals(type)) {
            vo = assembler.assembleSalesReturn(bill);
        } else {
            throw new RuntimeException("不支持的打印单据类型：" + type);
        }
        if (snapshotTime != null) {
            vo.setSnapshotTime(snapshotTime);
        }
        return vo;
    }

    /** 回退快照时把失败原因放进 extras.warn（前端预览页顶部提示）。 */
    private Map<String, Object> mergeWarn(Map<String, Object> extras, String warn) {
        Map<String, Object> merged = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
        if (warn != null) {
            merged.put("warn", "金蝶暂不可达：" + warn);
        }
        return merged;
    }

    // ------------------------------------------------------------ 本地下拉

    @Override
    public List<Map<String, String>> localBills(String billType, String keyword) {
        String type = trim(billType);
        String kw = trim(keyword);
        List<Map<String, String>> result = new ArrayList<>();
        if (WmsConstants.PRINT_BILL_PROFIT.equals(type) || WmsConstants.PRINT_BILL_LOSS.equals(type)) {
            QueryWrapper<WmsStocktakeOrder> qw = new QueryWrapper<WmsStocktakeOrder>()
                    .isNotNull("erp_bill_no").ne("erp_bill_no", "")
                    .orderByDesc("create_time").last("LIMIT 50");
            if (!kw.isEmpty()) {
                qw.like("stocktake_no", kw);
            }
            for (WmsStocktakeOrder o : stocktakeMapper.selectList(qw)) {
                result.add(option(o.getStocktakeNo(), o.getStocktakeNo() + "（" + o.getStatus() + "）",
                        !pickErpBillNo(o.getErpBillNo(), type).isEmpty()));
            }
            return result;
        }
        if (WmsConstants.PRINT_BILL_SALES_OUTSTOCK.equals(type)) {
            QueryWrapper<WmsOutboundOrder> qw = new QueryWrapper<WmsOutboundOrder>()
                    .eq("outbound_type", WmsConstants.OUTBOUND_TYPE_SALES)
                    .orderByDesc("create_time").last("LIMIT 50");
            if (!kw.isEmpty()) {
                qw.like("outbound_no", kw);
            }
            for (WmsOutboundOrder o : outboundMapper.selectList(qw)) {
                result.add(option(o.getOutboundNo(), o.getOutboundNo() + "（" + o.getStatus() + "）", true));
            }
        }
        return result;
    }

    private Map<String, String> option(String billNo, String label, boolean printable) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("billNo", billNo);
        item.put("label", label);
        item.put("printable", printable ? "1" : "0");
        return item;
    }

    // ------------------------------------------------------------ 支撑

    private boolean isSupported(String type) {
        return List.of(WmsConstants.PRINT_BILL_MISC_DELIVERY, WmsConstants.PRINT_BILL_MISC_INSTOCK,
                WmsConstants.PRINT_BILL_SUB_PICK, WmsConstants.PRINT_BILL_SALES_RETURN,
                WmsConstants.PRINT_BILL_SALES_OUTSTOCK, WmsConstants.PRINT_BILL_PROFIT,
                WmsConstants.PRINT_BILL_LOSS).contains(type);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
