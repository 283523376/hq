package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.dto.WmsDefectRecordQueryDTO;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.mapper.WmsDefectRecordMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeConsignedReturnWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsWarehouseCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * @Description: 不良品记录分页（{@code listPage}）单测——查询条件换 DTO（待办 §5-17）后：
 * 文本 like 能力不丢、时间区间按天闭区间生效、不传时间不产生时间条件。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsDefectRecordsListTest {

    private WmsInventoryMapper inventoryMapper;
    private WmsProductionInboundMapper productionInboundMapper;
    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsMaterialMapper materialMapper;
    private IWmsAuditLogService auditLogService;
    private WmsDefectRecordMapper defectRecordMapper;
    private WmsInboundOrderMapper inboundOrderMapper;
    private IWmsCustomerService customerService;
    private WmsWarehouseCodeResolver warehouseResolver;
    private IWmsKingdeeConsignedReturnWriter returnWriter;
    private WmsKingdeeFormClient formClient;
    private IWmsSystemConfigService configService;
    private ISysBaseAPI sysBaseAPI;
    private WmsDefectServiceImpl service;

    @BeforeEach
    void setUp() {
        inventoryMapper = mock(WmsInventoryMapper.class);
        productionInboundMapper = mock(WmsProductionInboundMapper.class);
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        auditLogService = mock(IWmsAuditLogService.class);
        defectRecordMapper = mock(WmsDefectRecordMapper.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        customerService = mock(IWmsCustomerService.class);
        warehouseResolver = mock(WmsWarehouseCodeResolver.class);
        returnWriter = mock(IWmsKingdeeConsignedReturnWriter.class);
        formClient = mock(WmsKingdeeFormClient.class);
        configService = mock(IWmsSystemConfigService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsDefectServiceImpl(inventoryMapper, orderMapper, detailMapper,
                locationMapper, warehouseMapper, productionInboundMapper, materialMapper,
                auditLogService, defectRecordMapper, inboundOrderMapper, customerService,
                warehouseResolver, returnWriter, formClient, configService, sysBaseAPI);
    }

    @SuppressWarnings("unchecked")
    private QueryHolder queryOfLastListPage(WmsDefectRecordQueryDTO dto) {
        doReturn(new Page<WmsDefectRecord>(1, 10)).when(defectRecordMapper)
                .selectPage(any(Page.class), any(Wrapper.class));
        service.listPage(1, 10, dto);
        ArgumentCaptor<Wrapper<WmsDefectRecord>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(defectRecordMapper).selectPage(any(Page.class), captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsDefectRecord> qw =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsDefectRecord>) captor.getValue();
        return new QueryHolder(qw.getSqlSegment(), qw.getParamNameValuePairs().values());
    }

    /** 简单持有 sqlSegment 与参数值，便于断言。 */
    private record QueryHolder(String sql, java.util.Collection<Object> params) {
    }

    /** 日期按天闭区间：begin 00:00:00 起、end 当天 23:59:59.999 止。 */
    @Test
    void listPage_timeRangeClosedPerDay() {
        WmsDefectRecordQueryDTO dto = new WmsDefectRecordQueryDTO();
        dto.setCreateTimeBegin(dayAt(2026, Calendar.NOVEMBER, 4, 0, 0, 0));
        dto.setCreateTimeEnd(dayAt(2026, Calendar.NOVEMBER, 5, 0, 0, 0));

        QueryHolder q = queryOfLastListPage(dto);

        assertTrue(q.sql().contains("create_time >="), q.sql());
        assertTrue(q.sql().contains("create_time <="), q.sql());
        boolean beginHit = q.params().stream().anyMatch(p -> p instanceof Date d
                && d.getTime() == dayAt(2026, Calendar.NOVEMBER, 4, 0, 0, 0).getTime());
        assertTrue(beginHit, "begin 未按原值参与过滤：" + q.params());
        Date endBound = q.params().stream().filter(p -> p instanceof Date)
                .map(p -> (Date) p)
                .filter(d -> d.after(dayAt(2026, Calendar.NOVEMBER, 5, 6, 0, 0)))
                .findFirst().orElse(null);
        assertNotNull(endBound, "end 必须扩到当天尾（23:59:59.999），否则当天记录查不到");
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(endBound);
        assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY));
        assertEquals(Calendar.NOVEMBER, endCal.get(Calendar.MONTH));
        assertEquals(5, endCal.get(Calendar.DAY_OF_MONTH));
    }

    /** 不传时间不产生时间条件——空查询窗口不能误伤全量浏览。 */
    @Test
    void listPage_noTimeRange_noTimeConditions() {
        WmsDefectRecordQueryDTO dto = new WmsDefectRecordQueryDTO();
        dto.setStatus("待处理");

        QueryHolder q = queryOfLastListPage(dto);

        assertFalse(q.sql().contains("create_time >="), q.sql());
        assertFalse(q.sql().contains("create_time <="), q.sql());
        assertTrue(q.sql().contains("status"), q.sql());
    }

    /** DTO 迁移不得丢既有能力：物料/来源单号仍是任意连续子串模糊（2026-11-02 口径）。 */
    @Test
    void listPage_keepsLikeFilters() {
        WmsDefectRecordQueryDTO dto = new WmsDefectRecordQueryDTO();
        dto.setMaterialCode("M00");
        dto.setSourceBillNo("SC1");

        QueryHolder q = queryOfLastListPage(dto);

        assertTrue(q.sql().contains("material_code"), q.sql());
        assertTrue(q.sql().contains("source_bill_no"), q.sql());
        // like 的参数会被 MyBatis-Plus 包上 %：真实的模糊匹配行为就是「任意连续子串」
        assertTrue(q.params().contains("%M00%") && q.params().contains("%SC1%"), q.params().toString());
    }

    private Date dayAt(int year, int month, int day, int hour, int minute, int second) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month, day, hour, minute, second);
        return cal.getTime();
    }
}
