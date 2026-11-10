package org.jeecg.modules.wms.service;

import lombok.Data;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: 库存预警配置（wms.warning.*）。
 *
 * <p>与金蝶同步任务默认关闭不同，预警扫描是**纯 WMS 内部计算**、不调 ERP、无外部副作用，
 * 因此默认开启（spec 2026-10-28 D10）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms.warning")
public class WmsWarningProperties {

    private Scan scan = new Scan();

    @Data
    public static class Scan {

        /** 是否注册定时扫描任务。 */
        private boolean enabled = true;

        /** 扫描 cron。 */
        private String cron = WmsConstants.INVENTORY_WARNING_SCAN_JOB_CRON;
    }
}
