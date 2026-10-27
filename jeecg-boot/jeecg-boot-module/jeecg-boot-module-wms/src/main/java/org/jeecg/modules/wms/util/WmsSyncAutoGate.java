package org.jeecg.modules.wms.util;

import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;

/**
 * 「自动同步」闸门（配置项 {@code sync.auto}）。
 *
 * <p>口径：{@code sync.auto=false} 时**所有定时同步任务**（回写补偿 + 定时拉取）本轮直接跳过，
 * 由业务页面上的手工「同步金蝶」按钮按需触发；{@code sync.auto=true}（默认）时行为与之前一致。
 *
 * <p>为什么放在任务里而不是注册器里：开关是运行期配置，改完不该要求重启 JVM 才生效；
 * 放在每次执行前判断，改完下一次调度即生效。
 *
 * <p>为什么配置服务为 null 时按「自动同步」处理：单测与降级场景不注入配置服务，
 * 此时不能悄悄把同步全停掉。
 *
 * @author 永鑫WMS
 * @since 2026-10-27
 */
public final class WmsSyncAutoGate {

    private WmsSyncAutoGate() {
    }

    /**
     * @return true = 自动同步已关闭，本轮任务应直接跳过
     */
    public static boolean isManualOnly(IWmsSystemConfigService configService) {
        return configService != null
                && !configService.getBool(WmsConfigKey.SYNC_AUTO.getKey(), true);
    }
}
