package org.jeecg.modules.wms.controller;

import org.jeecg.modules.wms.dto.WmsDefectRecordQueryDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.web.bind.WebDataBinder;

import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @Description: 不良品记录查询端点单测。核心回归是「日期字符串绑定不再 500」——
 * 该端点曾直接绑 {@code WmsDefectRecord} 实体，实体的 {@code createTime} 是
 * {@code java.util.Date} 且没有 {@code @DateTimeFormat}，GET 传 {@code 2026-11-04}
 * 就绑定失败（待办 §5-17）。
 *
 * <p>{@code WebDataBinder} + {@code DefaultFormattingConversionService} 与 Spring MVC
 * 对查询参数的绑定走同一套转换管线，等价于真实请求路径，不需要起 Servlet 容器。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsDefectControllerTest {

    private WebDataBinder binderFor() {
        WebDataBinder binder = new WebDataBinder(new WmsDefectRecordQueryDTO(), "query");
        binder.setConversionService(new DefaultFormattingConversionService());
        return binder;
    }

    /** 按天传日期（2026-11-04）必须能绑进两个时间字段——这正是当初 500 的场景。 */
    @Test
    void recordsQuery_bindsDateOnlyStrings() {
        WebDataBinder binder = binderFor();
        MutablePropertyValues values = new MutablePropertyValues();
        values.add("createTimeBegin", "2026-11-04");
        values.add("createTimeEnd", "2026-11-05");

        binder.bind(values);

        WmsDefectRecordQueryDTO target = (WmsDefectRecordQueryDTO) binder.getTarget();
        assertNotNull(target.getCreateTimeBegin(), "createTimeBegin 绑定失败（缺 @DateTimeFormat？）");
        assertNotNull(target.getCreateTimeEnd(), "createTimeEnd 绑定失败（缺 @DateTimeFormat？）");
        assertEquals(0, binder.getBindingResult().getErrorCount());
        Calendar cal = Calendar.getInstance();
        cal.setTime(target.getCreateTimeBegin());
        assertEquals(2026, cal.get(Calendar.YEAR));
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH));
        assertEquals(4, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** 多传的旧参数名（createTime）必须被静默忽略而不是绑定报错。 */
    @Test
    void recordsQuery_ignoresUnknownParams() {
        WebDataBinder binder = binderFor();
        MutablePropertyValues values = new MutablePropertyValues();
        values.add("createTime", "2026-11-04");
        values.add("materialCode", "M001");

        binder.bind(values);

        assertEquals(0, binder.getBindingResult().getErrorCount());
        assertEquals("M001", ((WmsDefectRecordQueryDTO) binder.getTarget()).getMaterialCode());
    }
}
