package org.jeecg.modules.wms.constant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 系统配置项元数据（单一事实源）。
 *
 * <p>「加一个配置项 = 加一个枚举值」：中文名、分组、类型、默认值、候选值都在这里，
 * 前端渲染与后端校验都读它，不存在两处口径漂移。值表结构不变。
 *
 * <p>设备两项的默认值**刻意与 {@link WmsConstants} 的常量同值**：
 * DB 里没有配置时应退回常量，而不是换一个数。
 *
 * @author 永鑫WMS
 */
public enum WmsConfigKey {

    INV_STOCK_MIN("inv.stock.min", "最低库存", WmsConfigGroup.INVENTORY, WmsConfigType.INT, "0", null),
    INV_STOCK_SAFE("inv.stock.safe", "安全库存", WmsConfigGroup.INVENTORY, WmsConfigType.INT, "0", null),
    INV_STOCK_MAX("inv.stock.max", "最高库存", WmsConfigGroup.INVENTORY, WmsConfigType.INT, "0", null),

    SYNC_SCOPE("sync.scope", "同步范围", WmsConfigGroup.SYNC, WmsConfigType.MULTI, "库存账,出入库记录",
            Arrays.asList("库存账", "出入库记录", "柜内库存")),
    SYNC_AUTO("sync.auto", "自动同步", WmsConfigGroup.SYNC, WmsConfigType.BOOL, "true", null),

    DEFECT_BIN_ALLOWED("defect.bin.allowed", "不良品是否入线边柜", WmsConfigGroup.DEFECT, WmsConfigType.BOOL, "false", null),
    DEFECT_WAREHOUSE_CODE("defect.warehouse.code", "不良品默认仓", WmsConfigGroup.DEFECT, WmsConfigType.TEXT, "", null),

    DEVICE_OFFLINE_THRESHOLD_SECONDS("device.offline.threshold.seconds", "设备离线判定阈值（秒）",
            WmsConfigGroup.DEVICE, WmsConfigType.INT,
            String.valueOf(WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS), null),
    DEVICE_ALERT_THROTTLE_MINUTES("device.alert.throttle.minutes", "同一设备告警最小间隔（分钟）",
            WmsConfigGroup.DEVICE, WmsConfigType.INT,
            String.valueOf(WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES), null);

    private final String key;
    private final String label;
    private final WmsConfigGroup group;
    private final WmsConfigType type;
    private final String defaultValue;
    private final List<String> options;

    WmsConfigKey(String key, String label, WmsConfigGroup group, WmsConfigType type,
                 String defaultValue, List<String> options) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.type = type;
        this.defaultValue = defaultValue;
        this.options = options;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public WmsConfigGroup getGroup() {
        return group;
    }

    public WmsConfigType getType() {
        return type;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    /** 多选项的候选值；非多选返回 null。 */
    public List<String> getOptions() {
        return options;
    }

    /** 按 key 取配置项；未知 key 直接拒绝。 */
    public static WmsConfigKey of(String key) {
        for (WmsConfigKey item : values()) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知配置项：" + key);
    }

    /** 某分组的全部配置项（顺序即表单顺序）。 */
    public static List<WmsConfigKey> ofGroup(WmsConfigGroup group) {
        List<WmsConfigKey> result = new ArrayList<>();
        for (WmsConfigKey item : values()) {
            if (item.group == group) {
                result.add(item);
            }
        }
        return result;
    }

    public static WmsConfigKey[] ordered() {
        return values();
    }
}
