package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Description: SB-001 上位系统同步对账常量守卫。
 *
 * <p>对账链路（批次/快照/差异清单）的中文状态值写错一个字，页面筛选与差异统计就漏；
 * 这里把设计文档 §2.2/§2.4/§2.5/§2.7/§2.9 定义的枚举值逐字钉死，任何引用必须走常量。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncConstantsTest {

    /** 批次状态：spec §2.5 wms_sync_run.status 三值。 */
    @Test
    void runStatuses_coverSpecSection2Dot5() {
        assertEquals("执行中", WmsConstants.SYNC_RUN_STATUS_RUNNING);
        assertEquals("成功", WmsConstants.SYNC_RUN_STATUS_SUCCESS);
        assertEquals("失败", WmsConstants.SYNC_RUN_STATUS_FAILED);
    }

    /** 对账范围：spec §2.2，scope_type 存英文 token（FULL/TODAY/WAREHOUSE）。 */
    @Test
    void scopeTypes_coverSpecSection2Dot2() {
        assertEquals("FULL", WmsConstants.SYNC_SCOPE_FULL);
        assertEquals("TODAY", WmsConstants.SYNC_SCOPE_TODAY);
        assertEquals("WAREHOUSE", WmsConstants.SYNC_SCOPE_WAREHOUSE);
    }

    /** 快照匹配：spec §2.4 比对规则四情形，与 §2.5 match_status 注释一致。 */
    @Test
    void snapshotMatchStatuses_coverFourCases() {
        assertEquals("一致", WmsConstants.SNAPSHOT_MATCH_EQUAL);
        assertEquals("数量差异", WmsConstants.SNAPSHOT_MATCH_QTY_DIFF);
        assertEquals("仅WMS", WmsConstants.SNAPSHOT_MATCH_WMS_ONLY);
        assertEquals("仅ERP", WmsConstants.SNAPSHOT_MATCH_ERP_ONLY);
    }

    /** 差异类型：spec §2.4 —— 仅WMS/仅ERP 复用快照常量，这里只钉多账/少账。 */
    @Test
    void diffTypes_coverMoreAndLess() {
        assertEquals("多账", WmsConstants.DIFF_TYPE_MORE);
        assertEquals("少账", WmsConstants.DIFF_TYPE_LESS);
    }

    /** 差异状态：spec §2.8，状态机只有「待核对 → 已核对/已忽略」。 */
    @Test
    void diffStatuses_coverThreeStates() {
        assertEquals("待核对", WmsConstants.DIFF_STATUS_PENDING);
        assertEquals("已核对", WmsConstants.DIFF_STATUS_CHECKED);
        assertEquals("已忽略", WmsConstants.DIFF_STATUS_IGNORED);
    }

    /** 日志对象类型与告警标题：spec §2.9（objectType）与 §2.7（标题逐字）。 */
    @Test
    void logObjectType_andAlertTitle_matchSpec() {
        assertEquals("INVENTORY_RECONCILE", WmsConstants.SYNC_LOG_TYPE_RECONCILE);
        assertEquals("SB-001 上位系统对账失败告警", WmsConstants.RECONCILE_ALERT_TITLE);
    }

    /** 定时任务：沿用 AUDIT_CLEAN_JOB_ID 的字符串 JobKey 风格；cron 见 spec §2.2 与 §2.9.1。 */
    @Test
    void jobKeys_andCrons_matchSpec() {
        assertEquals("wmsSyncReconcileJob", WmsConstants.SYNC_RECONCILE_JOB_ID);
        assertEquals("0 0 23 * * ?", WmsConstants.SYNC_RECONCILE_JOB_CRON);
        assertEquals("wmsSyncRetentionJob", WmsConstants.SYNC_RETENTION_JOB_ID);
        assertEquals("0 20 3 * * ?", WmsConstants.SYNC_RETENTION_JOB_CRON);
    }

    /** 审计业务类型：spec §2.9 —— 差异标记/发起盘点走 @WmsAudit。 */
    @Test
    void auditBizType_forSync() {
        assertEquals("上位同步", WmsConstants.AUDIT_BIZ_SYNC);
    }

    /** 配置项：key/默认值/分组三项都钉死；范围候选值必须与 SYNC_SCOPE_* 常量同源。 */
    @Test
    void configKeys_keyDefaultAndGroup() {
        assertSyncConfig(WmsConfigKey.SYNC_AUTO_SCOPE, "sync.auto.scope", "TODAY");
        assertSyncConfig(WmsConfigKey.SYNC_LOG_RETENTION_DAYS, "sync.log.retention.days", "90");
        assertSyncConfig(WmsConfigKey.SYNC_RETENTION_DAYS, "sync.retention.days", "180");
        assertSyncConfig(WmsConfigKey.SYNC_RETENTION_DIFF_DAYS, "sync.retention.diff-days", "365");

        assertEquals(List.of("FULL", "TODAY", "WAREHOUSE"), WmsConfigKey.SYNC_AUTO_SCOPE.getOptions(),
                "自动对账范围候选值必须与 SYNC_SCOPE_* 常量一致");
    }

    private void assertSyncConfig(WmsConfigKey item, String key, String defaultValue) {
        assertEquals(key, item.getKey());
        assertEquals(defaultValue, item.getDefaultValue());
        assertEquals(WmsConfigGroup.SYNC, item.getGroup());
    }
}
