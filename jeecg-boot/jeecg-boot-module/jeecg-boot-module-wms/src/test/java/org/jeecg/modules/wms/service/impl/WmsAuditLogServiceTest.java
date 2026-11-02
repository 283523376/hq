package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 审计写入服务单测（不启 Spring、不连库）。
 *
 * <p>三条约束是这个组件存在的理由，缺一条就退化成一个会拖垮业务的日志工具：
 * 字段落全、没有单号不写、写失败不抛。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditLogServiceTest {

    private WmsAuditLogMapper mapper;
    private WmsAuditLogServiceImpl service;

    /**
     * LambdaQueryWrapper 取 SQL 片段要先有实体的 TableInfo——正常由 MyBatis 启动时注册，
     * 纯单测里没有 Spring，手动初始化一次。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                WmsAuditLog.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WmsAuditLogMapper.class);
        service = new WmsAuditLogServiceImpl(mapper);
    }

    private static WmsAuditEvent fullEvent() {
        Map<String, Object> before = new HashMap<>();
        before.put("status", "待生产");
        before.put("qty", 10);
        Map<String, Object> after = new HashMap<>();
        after.put("status", "已下达");
        after.put("qty", 10);
        return new WmsAuditEvent()
                .setBizType("工单")
                .setBizNo("MO1")
                .setBizRef("MO1#1")
                .setAction("修改")
                .setChannel("ERP同步")
                .setBeforeValue(before)
                .setAfterValue(after)
                .setOperator("张三")
                .setIp("127.0.0.1")
                .setRequestId("req-1")
                .setResult("成功")
                .setCostMs(12);
    }

    private WmsAuditLog captureInserted() {
        ArgumentCaptor<WmsAuditLog> captor = ArgumentCaptor.forClass(WmsAuditLog.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 事件里的每个字段都要落到实体，且 diff_fields 只报真正变化的字段。 */
    @Test
    void record_persistsAllFields() {
        service.record(fullEvent());

        WmsAuditLog row = captureInserted();
        assertNotNull(row.getId(), "主键必须由服务生成");
        assertEquals("工单", row.getBizType());
        assertEquals("MO1", row.getBizNo());
        assertEquals("MO1#1", row.getBizRef());
        assertEquals("修改", row.getAction());
        assertEquals("ERP同步", row.getChannel());
        assertEquals("张三", row.getOperator());
        assertEquals("127.0.0.1", row.getIp());
        assertEquals("req-1", row.getRequestId());
        assertEquals("成功", row.getResult());
        assertEquals(12, row.getCostMs());
        assertNotNull(row.getCreateTime(), "发生时间必须落库，不能依赖 DB 默认值");
        assertTrue(row.getBeforeValue().contains("待生产"), "变更前值应为 JSON：" + row.getBeforeValue());
        assertTrue(row.getAfterValue().contains("已下达"), "变更后值应为 JSON：" + row.getAfterValue());
        assertEquals("status", row.getDiffFields(), "qty 未变化，不应出现在差异字段里");
    }

    /** 没有业务单号就失去反查入口——宁可不写，也不能落一条事后查不到的日志。 */
    @Test
    void record_missingBizNo_rejected() {
        WmsAuditEvent event = fullEvent().setBizNo("   ");

        assertDoesNotThrow(() -> service.record(event));
        verify(mapper, never()).insert(any(WmsAuditLog.class));
    }

    /** fail-open：审计库炸了，业务必须照常。金蝶那边可能已经落了单，回滚只会两边不一致。 */
    @Test
    void record_swallowsException_neverThrows() {
        when(mapper.insert(any(WmsAuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.record(fullEvent()));
        verify(mapper).insert(any(WmsAuditLog.class));
    }

    /** 前后值为空时 diff_fields 为 null，不写空串。 */
    @Test
    void record_withoutBeforeAfter_hasNoDiff() {
        WmsAuditEvent event = fullEvent().setBeforeValue(null).setAfterValue(null);

        service.record(event);

        assertNull(captureInserted().getDiffFields());
    }

    /** snapshot 只取点名的字段，字段不存在就跳过；bean 为 null 时返回 null。 */
    @Test
    void snapshot_keepsRequestedFieldsOnly() {
        WmsAuditEvent bean = new WmsAuditEvent().setBizNo("MO1").setOperator("张三");

        String json = service.snapshot(bean, "bizNo", "noSuchField");

        assertTrue(json.contains("\"bizNo\":\"MO1\""), "实际：" + json);
        assertFalse(json.contains("noSuchField"), "不存在的字段必须跳过，实际：" + json);
        assertFalse(json.contains("operator"), "未点名的字段不应出现，实际：" + json);
        assertNull(service.snapshot(null, "bizNo"));
    }

    /** 超长文本按列宽截断，否则一条脏数据会让整表插入失败（进而丢审计）。 */
    @Test
    void record_truncatesOverlongText() {
        String longMsg = "x".repeat(800);
        service.record(fullEvent().setErrorMsg(longMsg));

        WmsAuditLog row = captureInserted();
        assertNotNull(row.getErrorMsg());
        assertEquals(500, row.getErrorMsg().length(), "error_msg 列宽 500");
    }

    /** 查询条件必须落到 SQL：按单号筛时只应命中该单号的记录。 */
    @Test
    void pageQuery_buildsFiltersFromConditions() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsAuditLog> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        when(mapper.selectPage(any(), any())).thenReturn(page);
        org.jeecg.modules.wms.dto.WmsAuditQueryDTO query =
                new org.jeecg.modules.wms.dto.WmsAuditQueryDTO();
        query.setBizNo("MO1");
        query.setChannel("ERP同步");
        query.setResult("失败");

        service.pageQuery(query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsAuditLog>>
                captor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("biz_no"), "实际：" + sql);
        assertTrue(sql.contains("channel"), "实际：" + sql);
        assertTrue(sql.contains("result"), "实际：" + sql);
        // 同秒并列时必须有稳定次序，否则翻页重复/漏行
        assertTrue(sql.toUpperCase().contains("ID DESC"), "缺少 id 兜底排序：" + sql);
        // like 查询会把值包成 %MO1%
        assertTrue(captor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> String.valueOf(v).contains("MO1")),
                "单号条件没有进入参数表：" + captor.getValue().getParamNameValuePairs());
    }

    /** 分页参数越界要夹住，避免 pageSize=100000 拖垮库。 */
    @Test
    void pageQuery_clampsPaging() {
        when(mapper.selectPage(any(), any())).thenReturn(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsAuditLog>());
        org.jeecg.modules.wms.dto.WmsAuditQueryDTO query =
                new org.jeecg.modules.wms.dto.WmsAuditQueryDTO();
        query.setPageNo(0);
        query.setPageSize(100000);

        service.pageQuery(query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsAuditLog>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
        verify(mapper).selectPage(captor.capture(), any());
        assertEquals(1, captor.getValue().getCurrent());
        assertEquals(200, captor.getValue().getSize());
    }

    /**
     * 数字精度差异不算变更：金蝶返回 2000.000、WMS 落库 2000.0，
     * 按字符串比会把每个工单都误报成「planQty 变了」（真账套同步实测踩到的）。
     */
    @Test
    void diffFields_numericScaleDifference_isNotAChange() {
        String before = "{\"planQty\":2000.000,\"workOrderStatus\":\"已下达\"}";
        String after = "{\"planQty\":2000.0,\"workOrderStatus\":\"已下达\"}";

        String diff = WmsAuditLogServiceImpl.diffFields(before, after);

        assertNull(diff, "数值相同只是精度不同，不应记为变更");
    }

    /** 真正变化的数值仍然要报出来。 */
    @Test
    void diffFields_realNumericChange_isReported() {
        String diff = WmsAuditLogServiceImpl.diffFields(
                "{\"planQty\":2000}", "{\"planQty\":1500}");

        assertEquals("planQty", diff);
    }
}
