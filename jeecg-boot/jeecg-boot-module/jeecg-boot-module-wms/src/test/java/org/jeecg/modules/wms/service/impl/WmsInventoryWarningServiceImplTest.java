package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWarningHandleDTO;
import org.jeecg.modules.wms.entity.WmsInventoryWarning;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWarningRule;
import org.jeecg.modules.wms.mapper.WmsInventoryWarningMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsWarningRuleMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.jeecg.modules.wms.vo.WmsMaterialAvailableVO;
import org.jeecg.modules.wms.vo.WmsWarningScanResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存预警扫描/合并/处理（spec 2026-10-28 第 5、6 节）。
 */
class WmsInventoryWarningServiceImplTest {

    private WmsWarningRuleMapper ruleMapper;
    private WmsInventoryWarningMapper warningMapper;
    private WmsMaterialMapper materialMapper;
    private IWmsSyncLogService syncLogService;
    private WmsInventoryWarningServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(WmsWarningRuleMapper.class);
        warningMapper = mock(WmsInventoryWarningMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsInventoryWarningServiceImpl(ruleMapper, warningMapper,
                materialMapper, syncLogService);
    }

    private WmsWarningRule lowStockRule(String scope, String materialId) {
        WmsWarningRule rule = new WmsWarningRule();
        rule.setId("r1");
        rule.setRuleName("下限");
        rule.setRuleType(WmsConstants.WARNING_TYPE_LOW_STOCK);
        rule.setScopeType(scope);
        rule.setMaterialId(materialId);
        rule.setThresholdQty(new BigDecimal("10"));
        rule.setEnabled(1);
        return rule;
    }

    private WmsWarningRule overdueRule() {
        WmsWarningRule rule = new WmsWarningRule();
        rule.setId("r2");
        rule.setRuleName("超期");
        rule.setRuleType(WmsConstants.WARNING_TYPE_OVERDUE);
        rule.setScopeType(WmsConstants.WARNING_SCOPE_ALL);
        rule.setOverdueDays(30);
        rule.setEnabled(1);
        return rule;
    }

    private WmsMaterialAvailableVO available(String materialId, String qty) {
        WmsMaterialAvailableVO vo = new WmsMaterialAvailableVO();
        vo.setMaterialId(materialId);
        vo.setMaterialCode("M-" + materialId);
        vo.setMaterialName("物料" + materialId);
        vo.setAvailableQty(new BigDecimal(qty));
        return vo;
    }

    @Test
    void scanWithoutRulesSkips() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        WmsWarningScanResultVO vo = service.scan();
        assertEquals(0, vo.getRules());
        assertTrue(vo.getMessage().contains("跳过"));
        verify(warningMapper, never()).insert(any(WmsInventoryWarning.class));
    }

    @Test
    void lowStockBelowThresholdCreatesWarning() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(lowStockRule(WmsConstants.WARNING_SCOPE_ALL, null)));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull()))
                .thenReturn(List.of(available("m1", "5")));
        when(warningMapper.selectOne(any())).thenReturn(null);
        when(warningMapper.insert(any(WmsInventoryWarning.class))).thenReturn(1);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(1, vo.getCreated());
        assertEquals(1, vo.getScanned());
        ArgumentCaptor<WmsInventoryWarning> captor = ArgumentCaptor.forClass(WmsInventoryWarning.class);
        verify(warningMapper).insert(captor.capture());
        WmsInventoryWarning saved = captor.getValue();
        assertEquals(WmsConstants.WARNING_STATUS_PENDING, saved.getStatus());
        assertEquals("m1", saved.getMaterialId());
        assertEquals(0, new BigDecimal("5").compareTo(saved.getCurrentQty()));
        assertEquals("r1|m1|||", saved.getDedupKey());
        assertNotNull(saved.getWarningNo());
        assertTrue(saved.getWarningNo().startsWith(WmsConstants.WARNING_NO_PREFIX));
        assertEquals(Integer.valueOf(1), saved.getHitCount());
    }

    @Test
    void lowStockAtOrAboveThresholdCreatesNothing() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(lowStockRule(WmsConstants.WARNING_SCOPE_ALL, null)));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull()))
                .thenReturn(List.of(available("m1", "10")));

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(0, vo.getCreated());
        verify(warningMapper, never()).insert(any(WmsInventoryWarning.class));
    }

    @Test
    void lowStockMergesIntoPendingWarning() {
        WmsInventoryWarning pending = new WmsInventoryWarning();
        pending.setId("w1");
        pending.setDedupKey("r1|m1|||");
        pending.setStatus(WmsConstants.WARNING_STATUS_PENDING);
        pending.setHitCount(1);

        when(ruleMapper.selectList(any())).thenReturn(List.of(lowStockRule(WmsConstants.WARNING_SCOPE_ALL, null)));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull()))
                .thenReturn(List.of(available("m1", "3")));
        when(warningMapper.selectOne(any())).thenReturn(pending);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getMerged());
        assertEquals(Integer.valueOf(2), pending.getHitCount());
        assertEquals(0, new BigDecimal("3").compareTo(pending.getCurrentQty()));
        verify(warningMapper, never()).insert(any(WmsInventoryWarning.class));
        verify(warningMapper).updateById(pending);
    }

    @Test
    void materialScopeWithZeroStockStillWarns() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(lowStockRule(WmsConstants.WARNING_SCOPE_MATERIAL, "m9")));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull())).thenReturn(List.of());
        WmsMaterialAvailableVO snapshot = available("m9", "0");
        when(warningMapper.selectMaterialSnapshot("m9")).thenReturn(snapshot);
        when(warningMapper.selectOne(any())).thenReturn(null);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(1, vo.getCreated());
        verify(warningMapper).insert(any(WmsInventoryWarning.class));
    }

    @Test
    void categoryScopeCoversMaterialsWithoutStock() {
        WmsWarningRule rule = lowStockRule(WmsConstants.WARNING_SCOPE_CATEGORY, null);
        rule.setCategoryId("c1");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull()))
                .thenReturn(List.of(available("m1", "1")));
        // 分类下有两个物料：m1 有库存走聚合结果，m2 零库存走主数据快照
        WmsMaterial m1 = new WmsMaterial();
        m1.setId("m1");
        WmsMaterial m2 = new WmsMaterial();
        m2.setId("m2");
        when(materialMapper.selectList(any())).thenReturn(List.of(m1, m2));
        when(warningMapper.selectMaterialSnapshot("m2")).thenReturn(available("m2", "0"));
        when(warningMapper.selectOne(any())).thenReturn(null);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(2, vo.getCreated());
    }

    @Test
    void overdueCreatesOneWarningPerRow() {
        WmsInventoryStockRowVO row = new WmsInventoryStockRowVO();
        row.setMaterialId("m1");
        row.setMaterialCode("M-1");
        row.setMaterialName("物料1");
        row.setLocationId("l1");
        row.setLocationCode("L-01");
        row.setBatchNo("B1");
        row.setContainerCode("C1");
        row.setQuantity(new BigDecimal("20"));
        row.setFirstInboundTime(new Date());

        when(ruleMapper.selectList(any())).thenReturn(List.of(overdueRule()));
        when(warningMapper.selectOverdueRows(anyInt(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(row));
        when(warningMapper.selectOne(any())).thenReturn(null);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsInventoryWarning> captor = ArgumentCaptor.forClass(WmsInventoryWarning.class);
        verify(warningMapper).insert(captor.capture());
        WmsInventoryWarning saved = captor.getValue();
        assertEquals(WmsConstants.WARNING_TYPE_OVERDUE, saved.getRuleType());
        assertEquals("B1", saved.getBatchNo());
        assertEquals("L-01", saved.getLocationCode());
        assertEquals(Integer.valueOf(30), saved.getOverdueDays());
        assertEquals("r2|m1|l1|B1|C1", saved.getDedupKey());
    }

    @Test
    void ruleFailureDoesNotBreakOtherRules() {
        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(
                lowStockRule(WmsConstants.WARNING_SCOPE_ALL, null), overdueRule()));
        when(warningMapper.selectAvailableByMaterial(isNull(), isNull(), isNull()))
                .thenThrow(new RuntimeException("db down"));
        when(warningMapper.selectOverdueRows(anyInt(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        when(warningMapper.selectOne(any())).thenReturn(null);

        WmsWarningScanResultVO vo = service.scan();

        assertEquals(2, vo.getRules());
        assertEquals(0, vo.getCreated());
    }

    // ------------------------------------------------------------ 处理

    private WmsInventoryWarning pendingWarning() {
        WmsInventoryWarning warning = new WmsInventoryWarning();
        warning.setId("w1");
        warning.setWarningNo("WRN1");
        warning.setStatus(WmsConstants.WARNING_STATUS_PENDING);
        warning.setDedupKey("r1|m1|||");
        return warning;
    }

    @Test
    void handleResolvesAndLeavesPendingPool() {
        WmsInventoryWarning warning = pendingWarning();
        when(warningMapper.selectById("w1")).thenReturn(warning);

        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        dto.setId("w1");
        dto.setHandleType(WmsConstants.WARNING_HANDLE_REPLENISH);
        dto.setHandleRemark("已补 500");
        service.handle(dto, "张三");

        assertEquals(WmsConstants.WARNING_STATUS_RESOLVED, warning.getStatus());
        assertEquals("张三", warning.getHandledBy());
        assertEquals("已补 500", warning.getHandleRemark());
        assertNotNull(warning.getHandleTime());
        assertTrue(warning.getDedupKey().startsWith("r1|m1|||" + WmsConstants.WARNING_DEDUP_RESOLVED_SEP));
        verify(warningMapper).updateById(warning);
    }

    @Test
    void handleRejectsAlreadyResolved() {
        WmsInventoryWarning warning = pendingWarning();
        warning.setStatus(WmsConstants.WARNING_STATUS_RESOLVED);
        when(warningMapper.selectById("w1")).thenReturn(warning);

        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        dto.setId("w1");
        dto.setHandleType(WmsConstants.WARNING_HANDLE_CLEAR);
        dto.setHandleRemark("x");

        assertTrue(assertThrows(RuntimeException.class, () -> service.handle(dto, "张三"))
                .getMessage().contains("请勿重复操作"));
    }

    @Test
    void handleRejectsUnknownHandleType() {
        when(warningMapper.selectById("w1")).thenReturn(pendingWarning());
        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        dto.setId("w1");
        dto.setHandleType("BURN");
        dto.setHandleRemark("x");

        assertTrue(assertThrows(RuntimeException.class, () -> service.handle(dto, "张三"))
                .getMessage().contains("处理方式必须是"));
    }

    @Test
    void handleRejectsBlankRemark() {
        when(warningMapper.selectById("w1")).thenReturn(pendingWarning());
        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        dto.setId("w1");
        dto.setHandleType(WmsConstants.WARNING_HANDLE_OTHER);
        dto.setHandleRemark("   ");

        assertTrue(assertThrows(RuntimeException.class, () -> service.handle(dto, "张三"))
                .getMessage().contains("处理说明不能为空"));
    }

    @Test
    void handleRejectsUnknownWarning() {
        when(warningMapper.selectById("absent")).thenReturn(null);
        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        dto.setId("absent");
        dto.setHandleType(WmsConstants.WARNING_HANDLE_OTHER);
        dto.setHandleRemark("x");

        assertTrue(assertThrows(RuntimeException.class, () -> service.handle(dto, "张三"))
                .getMessage().contains("预警不存在"));
    }

    @Test
    void handleRejectsMissingId() {
        WmsWarningHandleDTO dto = new WmsWarningHandleDTO();
        assertTrue(assertThrows(RuntimeException.class, () -> service.handle(dto, "张三"))
                .getMessage().contains("预警ID不能为空"));
    }
}
