-- ============================================================================
-- 永鑫WMS · RK-001 审查 Minor 修复（承接 V3.9.5_11）
-- 内容：
--   1) 采购入库菜单图标改为前端图标集中存在的 inbox-outlined
--   2) 检验任务表 inbound_id 唯一约束（整单级一个任务；先清理历史重复行）
--   3) 终态单据（已拒收/检验不合格/作废）的同步状态回填为「无需同步」
-- 说明：本脚本可重复执行（UPDATE 带条件；索引操作是一次性的，迁移只跑一次）
-- ============================================================================

-- ------------------------------------------------ 1) 菜单图标
UPDATE `sys_permission` SET `icon` = 'ant-design:inbox-outlined'
 WHERE `id` = '1910000000000000102' AND `icon` = 'ant-design:inbox-in';

-- ------------------------------------------------ 2) 检验任务：先清历史重复，再加唯一索引
-- 同一入库单若存在多条未删除任务，仅保留最早一条（其余逻辑删除），否则唯一索引添加会失败
UPDATE `wms_inspection_task` t
  JOIN (
        SELECT `inbound_id`, MIN(`id`) AS keep_id
          FROM `wms_inspection_task`
         WHERE `deleted_at` = 0
         GROUP BY `inbound_id`
        HAVING COUNT(*) > 1
       ) d
    ON d.`inbound_id` = t.`inbound_id` AND t.`id` <> d.`keep_id`
   SET t.`deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000
 WHERE t.`deleted_at` = 0;

-- 普通索引升级为 (inbound_id, deleted_at) 唯一索引
ALTER TABLE `wms_inspection_task`
  DROP INDEX `idx_wms_inspection_inbound`,
  ADD UNIQUE INDEX `uk_wms_inspection_inbound` (`inbound_id`, `deleted_at`) USING BTREE;

-- ------------------------------------------------ 3) 终态单据同步状态回填
UPDATE `wms_inbound_order`
   SET `sync_status` = '无需同步'
 WHERE `inbound_status` IN ('已拒收', '检验不合格', '作废')
   AND `sync_status` = '待同步';
