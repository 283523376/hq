package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WmsMaterialCodeResolver 三级优先单测：表列 → YAML → 原样/null。
 */
class WmsMaterialCodeResolverTest {

    private WmsKingdeeProperties properties;
    private WmsMaterialMapper materialMapper;
    private WmsMaterialCodeResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setMaterialMap(Map.of("YAML001", "KD.YAML"));
        properties.setMaterialProductType(Map.of("YAML001", "半成品"));
        materialMapper = mock(WmsMaterialMapper.class);
        resolver = new WmsMaterialCodeResolver(properties, materialMapper);
    }

    private void dbMaterial(String code, String kingdeeCode, String productType) {
        WmsMaterial material = new WmsMaterial();
        material.setMaterialCode(code);
        material.setKingdeeCode(kingdeeCode);
        material.setKingdeeProductType(productType);
        when(materialMapper.selectOne(any())).thenReturn(material);
    }

    @Test
    void resolve_dbColumnWins() {
        dbMaterial("PBC001", "KD.PBC", null);
        assertEquals("KD.PBC", resolver.resolveKingdeeCode("PBC001"));
        assertEquals("KD.PBC", resolver.resolveKingdeeCode("YAML001"));
    }

    @Test
    void resolve_fallsBackToYaml_whenDbEmpty() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        assertEquals("KD.YAML", resolver.resolveKingdeeCode("YAML001"));
        assertEquals("半成品", resolver.resolveProductType("YAML001"));
    }

    @Test
    void resolve_passthrough_whenNothingConfigured() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        assertEquals("UNKNOWN", resolver.resolveKingdeeCode("UNKNOWN"));
        assertNull(resolver.resolveProductType("UNKNOWN"));
    }

    @Test
    void resolve_dbProductTypeWins() {
        dbMaterial("PBC001", null, "成品");
        assertEquals("成品", resolver.resolveProductType("PBC001"));
    }

    @Test
    void reverse_dbHitReturnsMaterialCode() {
        dbMaterial("PBC001", "KD.PBC", null);
        assertEquals("PBC001", resolver.reverseKingdeeCode("KD.PBC"));
    }

    @Test
    void reverse_yamlFallback() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        assertEquals("YAML001", resolver.reverseKingdeeCode("KD.YAML"));
        assertNull(resolver.reverseKingdeeCode("KD.NOTHING"));
    }

    // ---------------- 反向解析：YAML 映射优先于「自映射」记录（2026-10-29 物料双身份） ----------------

    @Test
    void reverse_yamlWinsOverSelfMappedDbRow() {
        // 数据库里存在一条 self-mapped 记录（material_code == kingdee_code），同时 YAML 把它映射到 FG001。
        // 库存挂在 FG001 上，自映射记录是空壳 —— 必须优先 YAML，否则 PPBOM 待办解析到没有库存的那条。
        dbMaterial("KD.YAML", "KD.YAML", null);
        assertEquals("YAML001", resolver.reverseKingdeeCode("KD.YAML"));
    }

    @Test
    void reverse_fallsBackToDbWhenNoYamlEntry() {
        dbMaterial("SELF001", "SELF001", null);
        assertEquals("SELF001", resolver.reverseKingdeeCode("SELF001"));
    }

    @Test
    void reverse_unknownReturnsNull() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        assertNull(resolver.reverseKingdeeCode("NOT.EXIST"));
    }
}
