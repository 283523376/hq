package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * @Description: 供应商业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsSupplierServiceImplTest {

    // ============================================================ 既有用例（勿删）

    /** 编码首尾空格必须被去掉，否则 `SUP001` 与 ` SUP001 ` 会并存，与唯一索引语义不一致。 */
    @Test
    void addSupplier_shouldTrimCodeAndName() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("  SUP001  ")
                .setSupplierName("  永鑫五金  ");

        service.addSupplier(supplier);

        assertEquals("SUP001", supplier.getSupplierCode());
        assertEquals("永鑫五金", supplier.getSupplierName());
    }

    /** 编码为空白必须直接拒绝，不能落一条空编码。 */
    @Test
    void addSupplier_shouldRejectBlankCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("   ")
                .setSupplierName("永鑫五金");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addSupplier(supplier));
        assertEquals("供应商编码不能为空", ex.getMessage());
    }

    /** 编码重复必须拒绝。 */
    @Test
    void addSupplier_shouldRejectDuplicatedCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("SUP001")
                .setSupplierName("永鑫五金");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addSupplier(supplier));
        assertEquals("编码已存在，请重新输入", ex.getMessage());
    }

    // ============================================================ 新增（BD002-T01）

    /** 新增必须清空客户端传入的 id、重置 delFlag、状态落默认启用，且所有文本字段去空格。 */
    @Test
    void addSupplier_shouldApplyDefaultsForNewEntity() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("  SUP001  ").setSupplierName("永鑫五金")
                .setContact("  张三  ").setPhone("  13800000000  ").setSupplyScope("  五金件  ");
        supplier.setId("客户端伪造ID");
        supplier.setDelFlag(1);

        service.addSupplier(supplier);

        assertNull(supplier.getId(), "新增必须清空 id，交由 MyBatis-Plus 生成");
        assertEquals(0, supplier.getDelFlag());
        assertEquals(WmsConstants.STATUS_ENABLE, supplier.getStatus());
        assertEquals("张三", supplier.getContact(), "联系人同样要去空格");
        assertEquals("13800000000", supplier.getPhone());
        assertEquals("五金件", supplier.getSupplyScope());
    }

    /** 名称缺失必须拒绝——否则列表里会出现一条没有名字的供应商。 */
    @Test
    void addSupplier_shouldRejectBlankName() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("SUP001")
                .setSupplierName("   ");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addSupplier(supplier));
        assertEquals("供应商名称不能为空", ex.getMessage());
        verify(service, never()).save(any());
    }

    /** 已显式指定的状态（如停用）不得被默认值覆盖。 */
    @Test
    void addSupplier_shouldKeepExplicitStatus() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("SUP001").setSupplierName("永鑫五金")
                .setStatus(WmsConstants.STATUS_DISABLE);

        service.addSupplier(supplier);

        assertEquals(WmsConstants.STATUS_DISABLE, supplier.getStatus());
    }

    // ============================================================ 编辑（BD002-T03）

    /** 编辑成功：trim 后按自身 id 排除查重并更新。 */
    @Test
    void editSupplier_shouldTrimAndUpdate() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("  SUP001  ").setSupplierName("  永鑫五金（改名）  ")
                .setContact("  李四  ");
        supplier.setId("1");

        service.editSupplier(supplier);

        assertEquals("SUP001", supplier.getSupplierCode());
        assertEquals("永鑫五金（改名）", supplier.getSupplierName());
        assertEquals("李四", supplier.getContact());
        verify(service).checkCodeExists("SUP001", "1");
        verify(service).updateById(supplier);
    }

    /** 编辑时编码撞上别的供应商必须拒绝。 */
    @Test
    void editSupplier_shouldRejectDuplicatedCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("SUP001").setSupplierName("永鑫五金");
        supplier.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editSupplier(supplier));
        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).updateById(any());
    }

    /** 编辑时编码被清空必须拒绝。 */
    @Test
    void editSupplier_shouldRejectBlankCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());

        WmsSupplier supplier = new WmsSupplier()
                .setSupplierCode("").setSupplierName("永鑫五金");
        supplier.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editSupplier(supplier));
        assertEquals("供应商编码不能为空", ex.getMessage());
    }

    // ============================================================ 停用（BD002-T04）

    /** 停用只需一条更新，且把状态置为 0。 */
    @Test
    void disableSupplier_shouldSetStatusDisabled() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).updateById(any());

        service.disableSupplier("1");

        ArgumentCaptor<WmsSupplier> captor = ArgumentCaptor.forClass(WmsSupplier.class);
        verify(service).updateById(captor.capture());
        assertEquals("1", captor.getValue().getId());
        assertEquals(WmsConstants.STATUS_DISABLE, captor.getValue().getStatus());
    }

    // ============================================================ 删除（BD002-T05/T06）

    /** 无关联订单时按 id 直接删除（BD002-T05）。 */
    @Test
    void removeSupplier_shouldRemoveById() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).removeById("1");

        service.removeSupplier("1");

        verify(service).removeById("1");
    }

    /**
     * ⚠ 现状记录（BD002-T06 缺口 G-06）：**没有**关联订单校验，删除不做任何检查。
     *
     * <p>蓝图要求「该供应商存在关联订单，不允许删除」。本用例固化当前行为，
     * 供将来补上该校验时立即变红。注意 `wms_order.supplier_id` 已存在，
     * 但 `removeSupplier` 未查询它。
     */
    @Test
    void removeSupplier_shouldNotCheckRelatedOrders() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).removeById("1");

        service.removeSupplier("1");

        verify(service).removeById("1");
    }

    /** 空/null 批量删除必须安全返回，不触发任何删除。 */
    @Test
    void removeBatch_shouldNoOpOnNullOrEmpty() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());

        service.removeBatch(null);
        service.removeBatch(Collections.emptyList());

        verify(service, never()).removeByIds(any(Collection.class));
    }

    /** 批量删除按 id 集合一次删除。 */
    @Test
    void removeBatch_shouldRemoveAll() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(true).when(service).removeByIds(any(Collection.class));

        service.removeBatch(Arrays.asList("1", "2"));

        verify(service).removeByIds(Arrays.asList("1", "2"));
    }

    // ============================================================ 查重按 trim 比对

    /**
     * 查重必须按 **trim 后**的编码比对。
     *
     * <p>否则 `" SUP001"` 会绕过 `"SUP001"` 的查重，最终撞上唯一索引 `uk_wms_supplier_code` 报 500，
     * 用户看到的是数据库异常而不是「编码已存在」。本用例断言拼进 SQL 的参数值已被 trim。
     */
    @Test
    void checkCodeExists_shouldCompareTrimmedCode() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(0L).when(service).count(any(Wrapper.class));

        service.checkCodeExists("  SUP001  ", null);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).count(captor.capture());
        QueryWrapper<WmsSupplier> wrapper = (QueryWrapper<WmsSupplier>) captor.getValue();
        // 必须先渲染 SQL 片段：MyBatis-Plus 的条件参数是惰性填充的，
        // 不调 getSqlSegment() 时 getParamNameValuePairs() 恒为空，断言会退化成永真
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("supplier_code"), "查重条件应落在 supplier_code 列，实际：" + sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("SUP001"),
                "查重必须按 trim 后的编码比对，实际参数：" + wrapper.getParamNameValuePairs());
    }

    // ============================================================ 下拉列表

    /** 下拉列表只取启用中的供应商（订单页选择用）。 */
    @Test
    void listAllEnabled_shouldQueryEnabledOnly() {
        WmsSupplierServiceImpl service = spy(new WmsSupplierServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        assertTrue(service.listAllEnabled().isEmpty());

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        QueryWrapper<WmsSupplier> wrapper = (QueryWrapper<WmsSupplier>) captor.getValue();
        // 同上：先渲染片段，否则参数集合为空、断言形同虚设
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("status"), "下拉列表应限定 status，实际：" + sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(WmsConstants.STATUS_ENABLE),
                "下拉列表必须限定 status=1，实际参数：" + wrapper.getParamNameValuePairs());
    }
}