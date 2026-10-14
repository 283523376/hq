package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * @Description: 供应商业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsSupplierServiceImplTest {

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
}
