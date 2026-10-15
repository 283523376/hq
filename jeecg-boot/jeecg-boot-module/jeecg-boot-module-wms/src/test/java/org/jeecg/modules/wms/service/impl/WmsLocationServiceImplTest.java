package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
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
        // countOutOfEnvelope 读的是 getById（它必须容忍「库区已停用但仍有库位」的情形），
        // 故两条路径都要桩，否则该用例会因 fixture 缺桩而误报为 0。
        when(warehouseService.getById(any())).thenReturn(warehouse);
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

    // ============================================================ T6: 属性 / 释放 / 删除

    private static WmsLocation location(String id, String status) {
        WmsLocation l = new WmsLocation().setLocationCode("LB-R01-C06-L24")
                .setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(24).setStatus(status);
        l.setId(id);
        return l;
    }

    /** 给已有 service 加一个「按 id 返回给定库位」的桩。 */
    private static void stubGetById(WmsLocationServiceImpl service, WmsLocation existing) {
        org.mockito.Mockito.doReturn(existing).when(service).getById(existing.getId());
    }

    // ------------------------------------------------------------ 约束②：库位内有物料时禁改属性

    @Test
    void updateAttr_shouldRejectWhenOccupied() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        dto.setMaxWeight(new java.math.BigDecimal("500.000"));
        dto.setStatus(WmsConstants.LOCATION_STATUS_FAULT);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.updateAttr(dto));
        assertEquals("库位内有物料，不允许修改属性", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void updateAttr_shouldAllowWhenIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));
        org.mockito.Mockito.doReturn(true).when(service).updateById(any());

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        dto.setMaxWeight(new java.math.BigDecimal("500.000"));

        service.updateAttr(dto);

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).updateById(captor.capture());
        assertEquals(new java.math.BigDecimal("500.000"), captor.getValue().getMaxWeight());
        assertNotNull(captor.getValue().getUpdateTime(), "审计字段应由 WmsUtils.fillUpdate 填入");
    }

    @Test
    void updateAttr_shouldRejectMissingLocation() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(null).when(service).getById("nope");
        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("nope");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.updateAttr(dto));
        assertEquals("库位不存在", ex.getMessage());
    }

    // ------------------------------------------------------------ 释放

    /**
     * 释放的由来：若「占用」既代表有物料、又阻断一切状态修改，被误置为占用的库位就
     * **永远无法人工恢复**，约束②会变成锁死缺陷。故单开一个语义明确的「占用 → 空闲」动作。
     */
    @Test
    void release_shouldTurnOccupiedIntoIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));
        org.mockito.Mockito.doReturn(true).when(service).updateById(any());

        service.release("L1");

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).updateById(captor.capture());
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, captor.getValue().getStatus());
    }

    @Test
    void release_shouldRejectWhenNotOccupied() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.release("L1"));
        assertEquals("仅占用状态的库位可释放", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).updateById(any());
    }

    // ------------------------------------------------------------ 删除守卫

    @Test
    void removeLocation_shouldRejectWhenNotIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeLocation("L1"));
        assertEquals("库位已占用或有故障，不允许删除", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).removeById(any(String.class));
    }

    @Test
    void removeLocation_shouldRejectWhenFault() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_FAULT));
        assertThrows(RuntimeException.class, () -> service.removeLocation("L1"));
    }

    @Test
    void removeLocation_shouldDeleteWhenIdle() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));
        org.mockito.Mockito.doReturn(true).when(service).removeById(any(String.class));
        service.removeLocation("L1");
        verify(service).removeById("L1");
    }

    // ------------------------------------------------------------ 手工补录单个库位

    @Test
    void addLocation_shouldGenerateCodeFromCoordinates() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(0L).when(service).count(any(Wrapper.class));
        org.mockito.Mockito.doReturn(true).when(service).save(any());

        service.addLocation(new WmsLocation().setWarehouseId("W1")
                .setRowNo(1).setColNo(6).setLayerNo(24));

        ArgumentCaptor<WmsLocation> captor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(service).save(captor.capture());
        assertEquals("LB-R01-C06-L24", captor.getValue().getLocationCode(), "编码由后端按规则生成，不接受前端传入");
        assertEquals(WmsConstants.LOCATION_STATUS_IDLE, captor.getValue().getStatus());
    }

    @Test
    void addLocation_shouldRejectDuplicateCoordinates() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        org.mockito.Mockito.doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addLocation(
                new WmsLocation().setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(24)));
        assertEquals("该坐标的库位已存在，请重新输入", ex.getMessage());
        verify(service, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void addLocation_shouldRejectBeyondEnvelope() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addLocation(
                new WmsLocation().setWarehouseId("W1").setRowNo(1).setColNo(6).setLayerNo(30)));
        assertEquals("第 30 层超出库区层数上限 24", ex.getMessage());
    }

    // ------------------------------------------------------------ 编辑：坐标不可改

    @Test
    void editLocation_shouldRejectCoordinateChange() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_IDLE));

        // setId 继承自 JeecgEntity，链式返回的是父类型，故不能把 setId 串在 WmsLocation 的 setter 前面
        WmsLocation submitted = new WmsLocation().setRowNo(1).setColNo(6).setLayerNo(10);
        submitted.setId("L1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editLocation(submitted));
        assertEquals("库位坐标不可修改，如需变更请删除后重新生成", ex.getMessage());
    }

    @Test
    void editLocation_shouldApplyAttributeGuard() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 24));
        stubGetById(service, location("L1", WmsConstants.LOCATION_STATUS_OCCUPIED));

        WmsLocation submitted = new WmsLocation().setMaxWeight(new java.math.BigDecimal("1.000"));
        submitted.setId("L1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editLocation(submitted));
        assertEquals("库位内有物料，不允许修改属性", ex.getMessage());
    }

    // ------------------------------------------------------------ 越界计数（T7 用）

    @Test
    void countOutOfEnvelope_shouldCountLocationsBeyondDeclaredDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("LB", 1, 6, 10));
        org.mockito.Mockito.doReturn(84L).when(service).count(any(Wrapper.class));
        assertEquals(84L, service.countOutOfEnvelope("W1"));
    }

    @Test
    void countOutOfEnvelope_shouldReturnZeroWhenNoDimensions() {
        WmsLocationServiceImpl service = newService(warehouse("XB", null, null, null));
        assertEquals(0L, service.countOutOfEnvelope("W1"));
    }
}