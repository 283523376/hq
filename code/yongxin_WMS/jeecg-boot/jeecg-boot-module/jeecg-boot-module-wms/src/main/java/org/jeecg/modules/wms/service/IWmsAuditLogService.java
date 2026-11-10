package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;

/**
 * @Description: 审计留痕写入服务（XT-002 的唯一写入口）。
 *
 * <p>**fail-open**：{@link #record} 内部吞掉所有异常，只记 ERROR 日志。
 * 审计写失败绝不能让业务回滚——尤其回写 ERP 的场景，金蝶那边可能已经落单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
public interface IWmsAuditLogService {

    /**
     * 记一条审计。**永不抛异常**；{@code bizNo} 为空时只 WARN 不落库
     * （没有单号就没有反查入口，落一条查不到的日志没有意义）。
     */
    void record(WmsAuditEvent event);

    /**
     * 条件分页查询（按业务类型/单号/操作人/通道/结果/时间区间组合过滤）。
     */
    IPage<WmsAuditLog> pageQuery(WmsAuditQueryDTO query);

    /**
     * 取实体的若干关键字段转成 JSON，供前后值使用。
     *
     * @param bean   实体或 Map；为 null 时返回 null
     * @param fields 要取的字段名；字段不存在则跳过
     * @return {@code {"field": value}}；没有任何字段命中时返回 null
     */
    String snapshot(Object bean, String... fields);
}
