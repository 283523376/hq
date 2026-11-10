package org.jeecg.modules.wms.service.store;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.jeecg.modules.wms.entity.WmsPrintDocument;
import org.jeecg.modules.wms.mapper.WmsPrintDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 打印快照存储单测（DY-001 Task 1）：upsert 插入/刷新两态、
 * find trim 与查空。快照是金蝶来源单据的离线打印底账，幂等落库是底线。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsPrintSnapshotStoreTest {

    private WmsPrintDocumentMapper mapper;
    private WmsPrintSnapshotStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsPrintDocumentMapper.class);
        store = new WmsPrintSnapshotStore(mapper);
    }

    /** 无已有行 → insert（自建 id、snapshotTime=now）；已有行 → updateById 刷新 payload 与 snapshotTime，不新增行。 */
    @Test
    void upsert_inserts_whenAbsent_andUpdates_whenPresent() {
        // 1) 查无 → insert
        when(mapper.selectList(any())).thenReturn(List.of());
        store.upsert("LOSS", "QTCK10126110002", "{\"billNo\":\"QTCK10126110002\"}");

        ArgumentCaptor<WmsPrintDocument> insCaptor = ArgumentCaptor.forClass(WmsPrintDocument.class);
        verify(mapper).insert(insCaptor.capture());
        WmsPrintDocument inserted = insCaptor.getValue();
        assertNotNull(inserted.getId(), "快照行必须自建 id（MyBatis-Plus 不为自定义调用生成主键）");
        assertEquals("LOSS", inserted.getBillType());
        assertEquals("QTCK10126110002", inserted.getErpBillNo());
        assertNotNull(inserted.getSnapshotTime());

        // 2) 已有行 → 沿用旧 id 刷新，不再 insert
        Date oldTime = new Date(System.currentTimeMillis() - 60_000L);
        WmsPrintDocument existing = new WmsPrintDocument()
                .setBillType("LOSS")
                .setErpBillNo("QTCK10126110002")
                .setPayload("{\"billNo\":\"old\"}")
                .setSnapshotTime(oldTime);
        existing.setId("DOC1");
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        store.upsert("LOSS", " QTCK10126110002 ", "{\"billNo\":\"new\"}");

        ArgumentCaptor<WmsPrintDocument> updCaptor = ArgumentCaptor.forClass(WmsPrintDocument.class);
        verify(mapper).updateById(updCaptor.capture());
        WmsPrintDocument patch = updCaptor.getValue();
        assertEquals("DOC1", patch.getId(), "刷新必须沿用已有行 id，不能插入第二行");
        assertEquals("{\"billNo\":\"new\"}", patch.getPayload());
        assertNotNull(patch.getSnapshotTime());
        assertTrue(patch.getSnapshotTime().after(oldTime), "snapshotTime 必须刷新为当前时间");
        verify(mapper, times(1)).insert(any(WmsPrintDocument.class));
    }

    /** find：入参 trim 后匹配；查无返回 empty。 */
    @Test
    void find_trimsInputs_andReturnsEmpty_whenNotFound() {
        when(mapper.selectList(any())).thenReturn(List.of());
        Optional<WmsPrintDocument> miss = store.find(" MISC_DELIVERY ", " QTRK10126110010 ");
        assertTrue(miss.isEmpty());

        ArgumentCaptor<Wrapper<WmsPrintDocument>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("bill_type") && sql.contains("erp_bill_no"), sql);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsPrintDocument> qw =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsPrintDocument>) captor.getValue();
        java.util.Collection<Object> params = qw.getParamNameValuePairs().values();
        assertTrue(params.contains("MISC_DELIVERY") && params.contains("QTRK10126110010"),
                params.toString());
    }

    /** find 命中时原样返回实体。 */
    @Test
    void find_returnsDocument_whenHit() {
        WmsPrintDocument doc = new WmsPrintDocument()
                .setBillType("SALES_OUTSTOCK")
                .setErpBillNo("XSCK1012619868")
                .setPayload("[]")
                .setSnapshotTime(new Date());
        doc.setId("DOC2");
        when(mapper.selectList(any())).thenReturn(List.of(doc));

        Optional<WmsPrintDocument> hit = store.find("SALES_OUTSTOCK", "XSCK1012619868");
        assertTrue(hit.isPresent());
        assertEquals("[]", hit.get().getPayload());
    }
}
