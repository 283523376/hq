package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.entity.WmsWarningRule;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.mapper.WmsWarningRuleMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则校验（spec 2026-10-28 第 4 节）：非法一律拒绝保存。
 */
class WmsWarningRuleServiceImplTest {

    private WmsWarningRuleMapper ruleMapper;
    private WmsMaterialMapper materialMapper;
    private WmsMaterialCategoryMapper categoryMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsWarningRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(WmsWarningRuleMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        categoryMapper = mock(WmsMaterialCategoryMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        service = new WmsWarningRuleServiceImpl(materialMapper, categoryMapper, warehouseMapper);
        WmsTestSupport.inject(service, "baseMapper", ruleMapper);
        when(ruleMapper.insert(any(WmsWarningRule.class))).thenReturn(1);
        when(ruleMapper.selectCount(any())).thenReturn(0L);
    }

    private WmsWarningRule lowStockAll() {
        WmsWarningRule rule = new WmsWarningRule();
        rule.setRuleName("全库下限");
        rule.setRuleType(WmsConstants.WARNING_TYPE_LOW_STOCK);
        rule.setScopeType(WmsConstants.WARNING_SCOPE_ALL);
        rule.setThresholdQty(new BigDecimal("10"));
        return rule;
    }

    private WmsWarningRule overdueMaterial() {
        WmsWarningRule rule = new WmsWarningRule();
        rule.setRuleName("关键件超期");
        rule.setRuleType(WmsConstants.WARNING_TYPE_OVERDUE);
        rule.setScopeType(WmsConstants.WARNING_SCOPE_MATERIAL);
        rule.setMaterialId("m1");
        rule.setOverdueDays(30);
        return rule;
    }

    @Test
    void createsValidLowStockRule() {
        WmsWarningRule saved = service.createRule(lowStockAll());
        assertEquals("全库下限", saved.getRuleName());
        assertEquals(Integer.valueOf(1), saved.getEnabled());
    }

    @Test
    void rejectsBlankName() {
        WmsWarningRule rule = lowStockAll();
        rule.setRuleName("  ");
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("规则名称不能为空"));
    }

    @Test
    void rejectsUnknownRuleType() {
        WmsWarningRule rule = lowStockAll();
        rule.setRuleType("SOMETHING");
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("规则类型"));
    }

    @Test
    void rejectsLowStockWithoutThreshold() {
        WmsWarningRule rule = lowStockAll();
        rule.setThresholdQty(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("必须填写下限数量"));
    }

    @Test
    void rejectsNonPositiveThreshold() {
        WmsWarningRule rule = lowStockAll();
        rule.setThresholdQty(BigDecimal.ZERO);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("必须大于 0"));
    }

    @Test
    void rejectsThresholdScaleOverThree() {
        WmsWarningRule rule = lowStockAll();
        rule.setThresholdQty(new BigDecimal("1.2345"));
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("最多保留 3 位小数"));
    }

    @Test
    void rejectsLowStockCarryingOverdueDays() {
        WmsWarningRule rule = lowStockAll();
        rule.setOverdueDays(30);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("不允许填写超期天数"));
    }

    @Test
    void rejectsOverdueWithoutDays() {
        WmsWarningRule rule = overdueMaterial();
        rule.setOverdueDays(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("必须填写超期天数"));
    }

    @Test
    void rejectsOverdueDaysOutOfRange() {
        WmsWarningRule zero = overdueMaterial();
        zero.setOverdueDays(0);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(zero))
                .getMessage().contains("1 ~"));

        WmsWarningRule tooBig = overdueMaterial();
        tooBig.setOverdueDays(WmsConstants.WARNING_OVERDUE_MAX_DAYS + 1);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(tooBig))
                .getMessage().contains("1 ~"));
    }

    @Test
    void rejectsOverdueCarryingThreshold() {
        WmsWarningRule rule = overdueMaterial();
        rule.setThresholdQty(new BigDecimal("5"));
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("不允许填写下限数量"));
    }

    @Test
    void rejectsMaterialScopeWithoutMaterialId() {
        WmsWarningRule rule = overdueMaterial();
        rule.setMaterialId(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("必须选择物料"));
    }

    @Test
    void rejectsMaterialThatDoesNotExist() {
        when(materialMapper.selectById("m1")).thenReturn(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(overdueMaterial()))
                .getMessage().contains("所选物料不存在"));
    }

    @Test
    void rejectsCategoryScopeWithoutCategoryId() {
        WmsWarningRule rule = lowStockAll();
        rule.setScopeType(WmsConstants.WARNING_SCOPE_CATEGORY);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("必须选择物料分类"));
    }

    @Test
    void acceptsCategoryScopeWithExistingCategory() {
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId("c1");
        when(categoryMapper.selectById("c1")).thenReturn(category);

        WmsWarningRule rule = lowStockAll();
        rule.setScopeType(WmsConstants.WARNING_SCOPE_CATEGORY);
        rule.setCategoryId("c1");
        service.createRule(rule);
        assertEquals("c1", rule.getCategoryId());
        assertNull(rule.getMaterialId());
    }

    @Test
    void allScopeClearsMaterialAndCategory() {
        WmsWarningRule rule = lowStockAll();
        rule.setMaterialId("m1");
        rule.setCategoryId("c1");
        service.createRule(rule);
        assertNull(rule.getMaterialId());
        assertNull(rule.getCategoryId());
    }

    @Test
    void rejectsUnknownWarehouse() {
        WmsWarningRule rule = lowStockAll();
        rule.setWarehouseId("w1");
        when(warehouseMapper.selectById("w1")).thenReturn(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(rule))
                .getMessage().contains("所选库区不存在"));
    }

    @Test
    void acceptsExistingWarehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId("w1");
        when(warehouseMapper.selectById("w1")).thenReturn(warehouse);
        WmsWarningRule rule = lowStockAll();
        rule.setWarehouseId("w1");
        service.createRule(rule);
        assertEquals("w1", rule.getWarehouseId());
    }

    @Test
    void rejectsDuplicateName() {
        when(ruleMapper.selectCount(any())).thenReturn(1L);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(lowStockAll()))
                .getMessage().contains("已存在"));
    }

    @Test
    void rejectsAnotherRuleOnSameTarget() {
        // 第 1 次（重名）返回 0，第 2 次（同目标）返回 1
        when(ruleMapper.selectCount(any())).thenReturn(0L, 1L);
        assertTrue(assertThrows(RuntimeException.class, () -> service.createRule(lowStockAll()))
                .getMessage().contains("已有同类型的预警规则"));
    }

    @Test
    void updateRequiresExistingRule() {
        WmsWarningRule rule = lowStockAll();
        rule.setId("absent");
        when(ruleMapper.selectById("absent")).thenReturn(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.updateRule(rule))
                .getMessage().contains("规则不存在"));
    }

    @Test
    void removeRequiresExistingRule() {
        when(ruleMapper.selectById("absent")).thenReturn(null);
        assertTrue(assertThrows(RuntimeException.class, () -> service.removeRule("absent"))
                .getMessage().contains("规则不存在"));
    }

    @Test
    void materialScopeClearsCategory() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        when(materialMapper.selectById("m1")).thenReturn(material);
        WmsWarningRule rule = overdueMaterial();
        rule.setCategoryId("c1");
        WmsWarningRule result = service.createRule(rule);
        assertNull(result.getCategoryId());
        assertEquals("m1", result.getMaterialId());
    }
}
