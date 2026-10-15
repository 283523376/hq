package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库位批量生成单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationServiceImplTest {

    private static WmsWarehouse warehouse(String code, Integer rows, Integer cols, Integer layers) {
        WmsWarehouse w = new WmsWarehouse()
                .setWarehouseCode(code)
                .setWarehouseName(code + "库区")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO)
                .setRowCount(rows).setColCount(cols).setLayerCount(layers)
                .setStatus(WmsConstants.STATUS_ENABLE);
        w.setId("W1");
        return w;
    }

    private static WmsLocationGenDTO range(int rowFrom, int rowTo, int colFrom, int colTo, int layerFrom, int layerTo) {
        WmsLocationGenDTO dto = new WmsLocationGenDTO();
        dto.setWarehouseId("W1");
        dto.setRowFrom(rowFrom).setRowTo(rowTo)
           .setColFrom(colFrom).setColTo(colTo)
           .setLayerFrom(layerFrom).setLayerTo(layerTo);
        return dto;
    }

    /**
     * 造一个服务：mapper 的 insertIgnoreBatch 如实返回「本批长度」（模拟全部是新行），
     * 库区守卫返回给定库区。
     */
    private static WmsLocationServiceImpl newService(WmsWarehouse warehouse) {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        WmsTestSupport.inject(service, "baseMapper", mapper);

        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse);
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);
        return service;
    }

    /** 取回注入到 {@code ServiceImpl.baseMapper}（另一包的 protected 字段）的 mock，供 verify 用。 */
    private static WmsLocationMapper mapperOf(WmsLocationServiceImpl service) {
        return (WmsLocationMapper) ReflectionTestUtils.getField(service, "baseMapper");
    }

    // ------------------------------------------------------------ 验收标准①：144 个立库库位

    @Test
    void generate_shouldCreateAllLocationsInRange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 6, 1, 24));

        assertEquals(144, result.getGenerated());
        assertEquals(0, result.getSkipped());
        verify(mapperOf(service), times(1)).insertIgnoreBatch(anyList());
    }

    /** 首末编码必须与需求方确认的样例一致。 */
    @Test
    void generate_shouldProduceApprovedCodes() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        ArgumentCaptor<List<WmsLocation>> captor = ArgumentCaptor.forClass(List.class);

        service.generate(range(1, 1, 1, 6, 1, 24));

        verify(mapperOf(service)).insertIgnoreBatch(captor.capture());
        List<WmsLocation> rows = captor.getValue();
        assertEquals(144, rows.size());
        assertEquals("LB-R01-C01-L01", rows.get(0).getLocationCode(), "首个库位");
        assertEquals("LB-R01-C06-L24", rows.get(143).getLocationCode(), "末个库位");
        assertEquals("W1", rows.get(0).getWarehouseId());
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, rows.get(0).getStatus());
        assertNotNull(rows.get(0).getId(),
                "自定义 XML 插入不会自动填主键，必须在服务层显式设 id；为 null 会被 INSERT IGNORE 静默吞掉");
    }

    // ------------------------------------------------------------ 编码与库区配置解耦（spec §5.2）

    /**
     * **本轮修正的核心断言**：同一个坐标，在库区层数配 10 / 24 / 9999 三种情形下，
     * 生成的编码必须**完全相同**。
     *
     * <p>初稿用 {@code max(2, 库区该维度上限的位数)} 算段宽，本用例在初稿下会红
     * （24 → {@code L24}，9999 → {@code L0024}），故不得不靠禁止改维度来绕过。
     * 现在段宽只由库位自身数字决定，该性质免费成立。
     */
    @Test
    void generate_shouldProduceSameCodeRegardlessOfWarehouseDimensions() {
        String codeAt24 = firstCodeOf(24);
        String codeAt9999 = firstCodeOf(9999);
        String codeAt10 = firstCodeOf(10);

        assertEquals("LB-R01-C06-L10", codeAt24);
        assertEquals(codeAt24, codeAt9999, "层数上限改成 9999 不得改变已有库位的编码");
        assertEquals(codeAt24, codeAt10, "层数上限改成 10 不得改变已有库位的编码");
    }

    private static String firstCodeOf(int layerCount) {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, layerCount));
        ArgumentCaptor<List<WmsLocation>> captor = ArgumentCaptor.forClass(List.class);
        service.generate(range(1, 1, 6, 6, 10, 10));
        verify(mapperOf(service)).insertIgnoreBatch(captor.capture());
        return captor.getValue().get(0).getLocationCode();
    }

    // ------------------------------------------------------------ 幂等（重跑同范围）

    @Test
    void generate_shouldBeIdempotentWhenMapperSkipsEverything() {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList())).thenReturn(0);   // 全部已存在 → 跳过
        WmsTestSupport.inject(service, "baseMapper", mapper);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse("LB", 1, 6, 24));
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 6, 1, 24));

        assertEquals(0, result.getGenerated());
        assertEquals(144, result.getSkipped());
    }

    // ------------------------------------------------------------ 分批（每批 1000）

    @Test
    void generate_shouldSplitIntoBatchesOfOneThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 2500, 1));

        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 2500, 1, 1));

        assertEquals(2500, result.getGenerated());
        verify(mapperOf(service), times(3)).insertIgnoreBatch(anyList());   // 1000 + 1000 + 500
    }

    // ------------------------------------------------------------ 守卫

    /** Review Focus #3：漏填维度时必须明确报错，而不是算出满屏 null 段。 */
    @Test
    void generate_shouldRejectWarehouseWithoutDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", null, null, null));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 1, 1, 1)));
        assertEquals("库区未配置网格维度，请先在库区上填写排 / 列 / 层上限", ex.getMessage());
    }

    @Test
    void generate_shouldRejectZeroDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", 0, 6, 24));
        assertThrows(RuntimeException.class, () -> service.generate(range(1, 1, 1, 1, 1, 1)));
    }

    @Test
    void generate_shouldRejectInvalidRange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(2, 1, 1, 6, 1, 24)));
        assertEquals("库位范围不合法", ex.getMessage());
    }

    @Test
    void generate_shouldRejectZeroOrNegativeBound() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        assertThrows(RuntimeException.class, () -> service.generate(range(0, 1, 1, 6, 1, 24)));
    }

    /** 包络守卫：不得超过库区声明的上限。 */
    @Test
    void generate_shouldRejectBeyondLayerEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 6, 1, 30)));
        assertEquals("第 30 层超出库区层数上限 24", ex.getMessage());
    }

    @Test
    void generate_shouldRejectBeyondColEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 7, 1, 24)));
        assertEquals("第 7 列超出库区列数上限 6", ex.getMessage());
    }

    /** 5 万上限守卫，报错文案必须含「本次 N 个」与「按层 / 列分批」。 */
    @Test
    void generate_shouldRejectOverFiftyThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 60422, 10));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 60422, 1, 10)));
        assertEquals("单次最多生成 50000 个库位，本次 604220 个，请按层 / 列分批", ex.getMessage());
        verify(mapperOf(service), times(0)).insertIgnoreBatch(anyList());
    }

    /** 恰好 5 万应放行（边界不能写成 {@code >=}）。 */
    @Test
    void generate_shouldAllowExactlyFiftyThousand() {
        WmsLocationServiceImpl service = newService(warehouse("MJ", 1, 50000, 1));
        WmsLocationGenResultVO result = service.generate(range(1, 1, 1, 50000, 1, 1));
        assertEquals(50000, result.getGenerated());
    }

    /** Review Focus #1：编码超长必须在插入前拦住，否则 INSERT IGNORE 会静默丢行。 */
    @Test
    void generate_shouldRejectWhenCodeWouldExceedColumnWidth() {
        StringBuilder longCode = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            longCode.append('X');
        }
        WmsLocationServiceImpl service = newService(warehouse(longCode.toString(), 1, 6, 24));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 6, 1, 24)));
        assertTrue(ex.getMessage().startsWith("库区编码过长"), "实际文案：" + ex.getMessage());
        verify(mapperOf(service), times(0)).insertIgnoreBatch(anyList());
    }

    // ------------------------------------------------------------ 部分失败：不整体回滚，但必须报出已生成数

    @Test
    void generate_shouldReportPartialCountOnFailure() {
        WmsLocationServiceImpl service = spy(new WmsLocationServiceImpl());
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.insertIgnoreBatch(anyList()))
                .thenReturn(1000)                                        // 第 1 批成功
                .thenThrow(new RuntimeException("Deadlock found"));      // 第 2 批炸
        WmsTestSupport.inject(service, "baseMapper", mapper);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse("MJ", 1, 2500, 1));
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.generate(range(1, 1, 1, 2500, 1, 1)));

        assertTrue(ex.getMessage().startsWith("已生成 1000 个后失败："), "实际文案：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("重跑同一范围可安全续做"), "必须告诉使用者恢复方式");
    }

    @Test
    void generate_shouldRejectBlankWarehouseId() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        WmsLocationGenDTO dto = range(1, 1, 1, 6, 1, 24);
        dto.setWarehouseId(null);
        assertThrows(RuntimeException.class, () -> service.generate(dto));
    }
}