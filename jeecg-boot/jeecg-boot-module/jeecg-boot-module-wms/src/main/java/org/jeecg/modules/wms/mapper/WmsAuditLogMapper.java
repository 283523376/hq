package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsAuditLog;

/**
 * @Description: 审计留痕 Mapper。
 *
 * <p>只有 {@code BaseMapper} 的标准能力：审计表禁止 update/delete（触发器兜底），
 * 清理走保留期任务的专用 SQL。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
public interface WmsAuditLogMapper extends BaseMapper<WmsAuditLog> {
}
