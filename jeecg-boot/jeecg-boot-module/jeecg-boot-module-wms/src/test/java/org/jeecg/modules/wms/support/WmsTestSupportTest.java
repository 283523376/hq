package org.jeecg.modules.wms.support;

import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @Description: 测试支撑工具自检（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsTestSupportTest {

    /** 演示用宿主：字段声明在父类上，验证 inject 能沿继承链找到。 */
    private static class Parent {
        protected Object service;
    }

    private static class Child extends Parent {
    }

    /** 反射注入必须能沿继承链命中父类字段——这是控制器测试赖以成立的前提。 */
    @Test
    void inject_shouldWalkUpClassHierarchy() {
        Child child = new Child();
        Object service = new Object();

        WmsTestSupport.inject(child, "service", service);

        assertEquals(service, child.service);
    }

    /** 字段不存在时必须抛明确的异常，而不是静默失败让后续 NPE 难以定位。 */
    @Test
    void inject_shouldFailLoudlyOnUnknownField() {
        assertThrows(IllegalStateException.class,
                () -> WmsTestSupport.inject(new Child(), "noSuchField", new Object()));
    }

    /** 构造出的 xlsx 必须能被 autopoi 按「两行标题 + 一行表头」解析出全部数据行。 */
    @Test
    void materialXlsx_shouldProduceParseableWorkbook() throws Exception {
        byte[] xlsx = WmsTestSupport.materialXlsx(Arrays.asList(
                new String[]{"M001", "螺栓", "CAT-BAN", "M8x20"},
                new String[]{"M002", "螺母", "CAT-BAN", "M8"}));

        ImportParams params = new ImportParams();
        params.setTitleRows(2);
        params.setHeadRows(1);
        params.setNeedSave(false);
        List<WmsMaterial> list =
                ExcelImportUtil.importExcel(new ByteArrayInputStream(xlsx), WmsMaterial.class, params);

        assertEquals(2, list.size());
        assertEquals("M001", list.get(0).getMaterialCode());
        assertEquals("螺栓", list.get(0).getMaterialName());
        assertEquals("CAT-BAN", list.get(0).getCategoryCode());
        assertEquals("M8x20", list.get(0).getSpec());
    }

    /** 传 null 即「仅表头」——用于构造空文件场景（BD001-T17）。 */
    @Test
    void materialXlsx_shouldProduceHeaderOnlyWorkbook() throws Exception {
        byte[] xlsx = WmsTestSupport.materialXlsx(null);

        ImportParams params = new ImportParams();
        params.setTitleRows(2);
        params.setHeadRows(1);
        params.setNeedSave(false);
        List<WmsMaterial> list =
                ExcelImportUtil.importExcel(new ByteArrayInputStream(xlsx), WmsMaterial.class, params);

        assertNotNull(list);
        assertEquals(0, list.size());
    }

    /** 空 Shiro 绑定后 getPrincipal() 必须是 null——控制器正是据此走「导出人为空」的分支。 */
    @Test
    void bindShiro_shouldExposeNullPrincipal() {
        WmsTestSupport.bindShiro();
        try {
            assertNull(org.apache.shiro.SecurityUtils.getSubject().getPrincipal());
        } finally {
            WmsTestSupport.unbindShiro();
        }
    }
}