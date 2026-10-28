package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsKingdeeCategorySyncService;
import org.jeecg.modules.wms.service.IWmsKingdeeMaterialSyncService;
import org.jeecg.modules.wms.service.IWmsKingdeeWarehouseSyncService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Description: 主数据（仓库 / 物料分类）ERP 同步任务。
 *
 * <p>默认**关闭**（{@code wms.sync.erp.kingdee.master-data.enabled=false}）：验收与日常以
 * Controller 手动触发为主，避免定时任务反复刷测试账套数据。
 *
 * <p>两道跳过守卫且关系：配置开关 AND 金蝶鉴权是否就绪。任一不满足直接返回，不写失败日志
 * （未配置不是故障，照 {@code WmsWorkOrderSyncJob} 的评审 I9 口径）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsMasterDataSyncJob implements Job {

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsKingdeeWarehouseSyncService warehouseSyncService;

    @Autowired
    private IWmsKingdeeCategorySyncService categorySyncService;

    @Autowired
    private IWmsKingdeeMaterialSyncService materialSyncService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsKingdeeBinSyncService binSyncService;

    @Autowired
    private WmsKingdeeProperties properties;

    @Autowired
    private IWmsKingdeeAuthService kingdeeAuthService;

    /** Quartz 需要可访问的无参构造器（JDBC 存储与「立即执行」）。 */
    public WmsMasterDataSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsMasterDataSyncJob(IWmsKingdeeWarehouseSyncService warehouseSyncService,
                         IWmsKingdeeCategorySyncService categorySyncService,
                         IWmsKingdeeMaterialSyncService materialSyncService,
                         WmsKingdeeProperties properties,
                         IWmsKingdeeAuthService kingdeeAuthService) {
        this.warehouseSyncService = warehouseSyncService;
        this.categorySyncService = categorySyncService;
        this.materialSyncService = materialSyncService;
        this.properties = properties;
        this.kingdeeAuthService = kingdeeAuthService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    void syncForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），本轮跳过，请在业务页面手工同步");
            return;
        }

        if (properties == null || !properties.getMasterData().isEnabled()) {
            log.debug("主数据同步任务跳过：master-data.enabled=false");
            return;
        }
        // 全局总闸：wms.sync.erp.kingdee.enabled 关闭时，master-data 单独开着也不得打金蝶
        if (!properties.isEnabled()) {
            log.debug("主数据同步任务跳过：wms.sync.erp.kingdee.enabled=false");
            return;
        }
        if (kingdeeAuthService != null && !kingdeeAuthService.isConfigured()) {
            log.warn("主数据同步任务跳过：金蝶未配置");
            return;
        }
        // 两个同步互不阻断：仓库失败不影响分类
        try {
            WmsKingdeeStockSyncResultVO vo = warehouseSyncService.syncStock();
            log.info("主数据同步-仓库：{}", vo.getMessage());
        } catch (Exception e) {
            log.error("主数据同步-仓库失败：{}", e.getMessage(), e);
        }
        try {
            WmsKingdeeStockSyncResultVO vo = categorySyncService.syncCategory();
            log.info("主数据同步-分类：{}", vo.getMessage());
        } catch (Exception e) {
            log.error("主数据同步-分类失败：{}", e.getMessage(), e);
        }
        try {
            WmsKingdeeStockSyncResultVO vo = materialSyncService.syncMaterial();
            log.info("主数据同步-物料：{}", vo.getMessage());
        } catch (Exception e) {
            log.error("主数据同步-物料失败：{}", e.getMessage(), e);
        }
        try {
            WmsKingdeeStockSyncResultVO vo = binSyncService.syncBins();
            log.info("主数据同步-仓位属性：{}", vo.getMessage());
        } catch (Exception e) {
            log.error("主数据同步-仓位属性失败：{}", e.getMessage(), e);
        }
    }
}
