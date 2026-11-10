package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.vo.WmsImpactVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 影响面分析单测（XT-002）：输入一张工单号，看它在 WMS 碰过什么。
 *
 * <p>这是用户「照着清单逐条纠正错误工单」的直接依据，缺项的代价是把错误留在库里；
 * 因此查不到时**必须显式报缺口**，不能静默返回空。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsImpactServiceTest {

    private WmsWorkOrderMapper workOrderMapper;
    private WmsProductionInboundMapper inboundMapper;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsAuditLogMapper auditLogMapper;
    private WmsImpactServiceImpl service;

    @BeforeEach
    void setUp() {
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        auditLogMapper = mock(WmsAuditLogMapper.class);
        service = new WmsImpactServiceImpl(workOrderMapper, inboundMapper, outboundMapper,
                detailMapper, inventoryMapper, auditLogMapper);
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(auditLogMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());
    }

    private static WmsWorkOrder order() {
        return new WmsWorkOrder().setWorkOrderNo("MO1").setWorkOrderStatus("已下达");
    }

    private static WmsProductionInbound inbound() {
        return new WmsProductionInbound().setInboundNo("RK1").setWorkOrderNo("MO1")
                .setMaterialCode("M1").setBatchNo("L1").setErpBillNo("SCRK001");
    }

    private static WmsOutboundOrder outbound() {
        return new WmsOutboundOrder().setOutboundNo("CK1").setWorkOrderNo("MO1")
                .setErpBillNo("FLL001");
    }

    /** 工单 + 入库单 + 领料单：三组都要收齐。 */
    @Test
    void analyze_collectsAllRelatedDocuments() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of(outbound()));
        // 库存行也要能查到，否则「数据齐全」的断言会被新的缺口提示打脸
        WmsInventoryLedgerVO row = new WmsInventoryLedgerVO();
        row.setMaterialCode("M1");
        row.setBatchNo("L1");
        Page<WmsInventoryLedgerVO> page = new Page<>();
        page.setRecords(List.of(row));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(page);

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertEquals("MO1", vo.getWorkOrder().getWorkOrderNo());
        assertEquals(1, vo.getProductionInbounds().size());
        assertEquals(1, vo.getOutbounds().size());
        assertEquals("CK1", vo.getOutbounds().get(0).getOrder().getOutboundNo());
        assertTrue(vo.getGaps().isEmpty(), "数据齐全时不应报缺口：" + vo.getGaps());
    }

    /** 库存行由单据的 物料+批次 反查当前库，不存快照。 */
    @Test
    void analyze_derivesInventoryRowsFromDocuments() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of());
        WmsInventoryLedgerVO row = new WmsInventoryLedgerVO();
        row.setMaterialCode("M1");
        row.setBatchNo("L1");
        row.setQuantity(new BigDecimal("12"));
        Page<WmsInventoryLedgerVO> page = new Page<>();
        page.setRecords(List.of(row));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(page);

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertEquals(1, vo.getInventoryRows().size());
        assertEquals("L1", vo.getInventoryRows().get(0).getBatchNo());
        verify(inventoryMapper).selectLedgerPage(any(), any());
    }

    /** 单据被删了：不能静默空白，必须告诉使用者「只剩日志可见」。 */
    @Test
    void analyze_whenDocumentsDeleted_reportsGap() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of());
        when(outboundMapper.selectList(any())).thenReturn(List.of());

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertTrue(vo.getProductionInbounds().isEmpty());
        assertTrue(vo.getInventoryRows().isEmpty());
        assertTrue(vo.getGaps().stream().anyMatch(g -> g.contains("仅审计日志可见")),
                "实际：" + vo.getGaps());
    }

    /** 工单本身都不在 WMS：说明它没同步进来过，或已被删除。 */
    @Test
    void analyze_unknownWorkOrder_reportsGap() {
        when(workOrderMapper.selectOne(any())).thenReturn(null);
        when(inboundMapper.selectList(any())).thenReturn(List.of());
        when(outboundMapper.selectList(any())).thenReturn(List.of());

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO-NONE");

        assertNull(vo.getWorkOrder());
        assertTrue(vo.getGaps().stream().anyMatch(g -> g.contains("WMS 中没有这张工单")),
                "实际：" + vo.getGaps());
    }

    /** 回写过 ERP 的单据要单列出来：纠正错误时得去金蝶处理这些单。 */
    @Test
    void analyze_collectsErpBills() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of(outbound()));

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertEquals(2, vo.getErpBills().size());
        assertTrue(vo.getErpBills().stream().anyMatch(b -> "SCRK001".equals(b.getBillNo())));
        assertTrue(vo.getErpBills().stream().anyMatch(b -> "FLL001".equals(b.getBillNo())));
    }

    /** 时间线来自审计表：按工单号或其关联单号都能命中。 */
    @Test
    void analyze_timelineComesFromAuditLog() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of());
        when(auditLogMapper.selectList(any())).thenReturn(List.of(
                new WmsAuditLog().setBizNo("MO1").setAction("同步落地"),
                new WmsAuditLog().setBizNo("MO1").setAction("作废")));

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertEquals(2, vo.getTimeline().size());
    }

    /** 空单号直接拒绝，避免一个空查询把整张表捞出来。 */
    @Test
    void analyze_blankWorkOrderNo_rejected() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.analyzeByWorkOrderNo("  "));
        org.mockito.Mockito.verifyNoInteractions(workOrderMapper);
    }

    /** 评审 M5：LIKE 模糊匹配会把 M0012 混进 M001 的影响面，必须按物料+批次精确过滤。 */
    @Test
    void analyze_inventoryRows_matchedExactly() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of());
        WmsInventoryLedgerVO mine = new WmsInventoryLedgerVO();
        mine.setMaterialCode("M1");
        mine.setBatchNo("L1");
        WmsInventoryLedgerVO others = new WmsInventoryLedgerVO();
        others.setMaterialCode("M12");
        others.setBatchNo("L1");
        Page<WmsInventoryLedgerVO> page = new Page<>();
        page.setRecords(List.of(mine, others));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(page);

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertEquals(1, vo.getInventoryRows().size(), "M12 不该出现在 M1 的影响面里");
        assertEquals("M1", vo.getInventoryRows().get(0).getMaterialCode());
    }

    /** 库存行超过一页时必须明说「只显示了一部分」，不能静默截断。 */
    @Test
    void analyze_inventoryTruncated_reportsGap() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of());
        WmsInventoryLedgerVO row = new WmsInventoryLedgerVO();
        row.setMaterialCode("M1");
        row.setBatchNo("L1");
        Page<WmsInventoryLedgerVO> page = new Page<>(1, 200, 350);
        page.setRecords(new java.util.ArrayList<>(java.util.Collections.nCopies(200, row)));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(page);

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertTrue(vo.getGaps().stream().anyMatch(g -> g.contains("仅显示")),
                "实际：" + vo.getGaps());
    }

    /** 有物料+批次却没有当前库存行：如实说明（可能已出完/被调整），不静默空白。 */
    @Test
    void analyze_noInventoryRow_reportsGap() {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(inboundMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(outboundMapper.selectList(any())).thenReturn(List.of());

        WmsImpactVO vo = service.analyzeByWorkOrderNo("MO1");

        assertTrue(vo.getGaps().stream().anyMatch(g -> g.contains("未查到当前库存行")),
                "实际：" + vo.getGaps());
    }
}
