-- =====================================================================
-- V3.9.5_22  CK-004 调拨 ERP 同步任务种子行
-- ---------------------------------------------------------------------
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」；实际调度由
-- WmsTransferSyncJobRegistrar 负责（cron 与常量一致）。
-- id 必须与 WmsConstants.TRANSFER_SYNC_JOB_ID 同值。
-- cron `0 23/10 * * * ? *`：每 10 分钟落在 :23，与订单(:03)、设备(:07)、
-- 采购入库(:09)、生产入库(:13)、出库(:17)、工单(:01/5min) 错峰。
--
-- 回滚：
--   DELETE FROM sys_quartz_job WHERE id = '1920000000000000350';
-- =====================================================================

INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000350','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsTransferSyncJob','0 23/10 * * * ? *',NULL,'WMS调拨ERP同步任务',0);
