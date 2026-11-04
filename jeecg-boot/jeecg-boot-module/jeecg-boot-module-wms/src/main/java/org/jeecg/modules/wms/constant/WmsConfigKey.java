package org.jeecg.modules.wms.constant;

import java.util.ArrayList;
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

    /**
     * 上位系统同步范围：候选值与默认值都来自 {@link WmsKingdeeSyncScope}（扩展点），
     * 不要在这里硬编码清单 —— 加一个同步范围只需改那张登记表。
     */
    SYNC_SCOPE("sync.scope", "同步范围", WmsConfigGroup.SYNC, WmsConfigType.MULTI,
            WmsKingdeeSyncScope.defaultValue(), WmsKingdeeSyncScope.labels()),
    SYNC_AUTO("sync.auto", "自动同步", WmsConfigGroup.SYNC, WmsConfigType.BOOL, "true", null),
    /**
     * 自动对账范围（SB-001 spec §2.2）：定时任务每天 23:00 按该项取范围建批次；
     * 候选值与 {@link WmsConstants#SYNC_SCOPE_FULL} 等三个常量逐字同源。
     */
    SYNC_AUTO_SCOPE("sync.auto.scope", "自动对账范围", WmsConfigGroup.SYNC, WmsConfigType.SELECT,
            "TODAY", List.of("FULL", "TODAY", "WAREHOUSE")),
    /** 同步日志保留天数（SB-001 spec §2.9.1）：wms_sync_log 清理依据。 */
    SYNC_LOG_RETENTION_DAYS("sync.log.retention.days", "同步日志保留天数", WmsConfigGroup.SYNC,
            WmsConfigType.INT, "90", null),
    /** 对账快照保留天数（SB-001 spec §2.9.1）：run/snapshot/flow 随批次一起删。 */
    SYNC_RETENTION_DAYS("sync.retention.days", "对账快照保留天数", WmsConfigGroup.SYNC,
            WmsConfigType.INT, "180", null),
    /** 差异清单保留天数（SB-001 spec §2.9.1）：对账证据，留久一点。 */
    SYNC_RETENTION_DIFF_DAYS("sync.retention.diff-days", "差异清单保留天数", WmsConfigGroup.SYNC,
            WmsConfigType.INT, "365", null),

    // 「不良品是否入线边柜」已随方案 A（线边柜）移出范围，2026-10-27 移除该项
    AUDIT_RETENTION_DAYS("audit.retention.days", "审计日志保留天数", WmsConfigGroup.AUDIT,
            WmsConfigType.INT, "365", null),

    DEFECT_WAREHOUSE_CODE("defect.warehouse.code", "不良品默认仓", WmsConfigGroup.DEFECT, WmsConfigType.SELECT, "", null),

    DEVICE_OFFLINE_THRESHOLD_SECONDS("device.offline.threshold.seconds", "设备离线判定阈值（秒）",
            WmsConfigGroup.DEVICE, WmsConfigType.INT,
            String.valueOf(WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS), null),
    DEVICE_ALERT_THROTTLE_MINUTES("device.alert.throttle.minutes", "同一设备告警最小间隔（分钟）",
            WmsConfigGroup.DEVICE, WmsConfigType.INT,
            String.valueOf(WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES), null),

    // ---------------------------------------------------------------- 金蝶单据 formId（V2.4）
    // spec §6：formId 以 BOS 在线控制台确认为准，确认前一律留空（空 = 未配置）。
    // 下游 Reader/Writer 读到空串必须显式报错，不得静默返回空结果或跳过回写 ——
    // 静默会让「假成功」一路走到月末结账。默认值绝不能填猜测的 formId。

    /** 受托加工材料入库单 FormId（WMS→ERP 回写，spec §3.2 受托入库）。 */
    KD_FORM_CONSIGNED_INBOUND("kingdee.form.consigned-inbound", "受托加工材料入库单 FormId",
            WmsConfigGroup.KINGDEE, WmsConfigType.TEXT, "", null),

    /** 采购入库单 FormId（WMS→ERP 回写，spec §3.2 段差棒直入）。 */
    KD_FORM_PURCHASE_INBOUND("kingdee.form.purchase-inbound", "采购入库单 FormId",
            WmsConfigGroup.KINGDEE, WmsConfigType.TEXT, "", null),

    /** 采购退货单 FormId（WMS→ERP 回写，spec §3.3 受托不良退货）。 */
    KD_FORM_PURCHASE_RETURN("kingdee.form.purchase-return", "采购退货单 FormId",
            WmsConfigGroup.KINGDEE, WmsConfigType.TEXT, "", null),

    /** 采购订单 FormId（ERP→WMS 读取，spec §3.2 到货登记校验）。 */
    KD_FORM_PURCHASE_ORDER("kingdee.form.purchase-order", "采购订单 FormId",
            WmsConfigGroup.KINGDEE, WmsConfigType.TEXT, "", null),

    /** 受托加工订单 FormId（ERP→WMS 读取，spec §3.2 受托到货登记校验）。 */
    KD_FORM_CONSIGNED_ORDER("kingdee.form.consigned-order", "受托加工订单 FormId",
            WmsConfigGroup.KINGDEE, WmsConfigType.TEXT, "", null);

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
