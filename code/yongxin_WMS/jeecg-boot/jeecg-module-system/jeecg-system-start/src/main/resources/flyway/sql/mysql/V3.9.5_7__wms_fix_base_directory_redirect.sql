-- ============================================================================
-- 永鑫WMS · 修正「基础管理」目录点击后 404
--
-- 现象：点击左侧「基础管理」目录会导航到 /wms，而该路径没有索引子路由 → 404 页。
--
-- 原因：目录节点（menu_type=0）1910000000000000001 的 url='/wms' 承载着
--   /wms/material、/wms/supplier、… 这些子路由，但自身 redirect 为空 ——
--   于是访问父路径时无处可去。
--
-- 修法依据（对齐平台既有做法，不发明新约定）：平台里较好的那批目录都设了 redirect，
--   例如 首页→/dashboard/analysis、权限管理→/permission/front/page、
--   低代码开发→/online/cgform、图表→/charts/echarts/map、消息中心→/message/manage。
--   本脚本让「基础管理」落到它的第一个子菜单（物料与分类，sort_no=1）。
--
-- 幂等：仅当 redirect 为空时写入，重复执行不会覆盖后续的人工修改。
-- ============================================================================

UPDATE `sys_permission`
   SET `redirect` = '/wms/material'
 WHERE `id` = '1910000000000000001'
   AND (`redirect` IS NULL OR `redirect` = '');