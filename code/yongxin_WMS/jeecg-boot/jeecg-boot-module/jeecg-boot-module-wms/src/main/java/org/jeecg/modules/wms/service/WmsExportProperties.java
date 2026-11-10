package org.jeecg.modules.wms.service;

import lombok.Data;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: 导出任务配置（wms.export.*）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms.export")
public class WmsExportProperties {

    /**
     * 导出文件根目录。
     *
     * <p>留空时用 {@code jeecg.path.upload} + {@code /wms-export}。多实例部署要改成共享存储。
     */
    private String dir = "";

    /** 终态任务（含文件）保留天数。 */
    private int retentionDays = WmsConstants.EXPORT_RETENTION_DAYS;

    private Clean clean = new Clean();

    @Data
    public static class Clean {

        /** 是否注册清理定时任务。 */
        private boolean enabled = true;

        /** 清理 cron，默认每天 03:00。 */
        private String cron = WmsConstants.EXPORT_TASK_CLEAN_JOB_CRON;
    }
}
