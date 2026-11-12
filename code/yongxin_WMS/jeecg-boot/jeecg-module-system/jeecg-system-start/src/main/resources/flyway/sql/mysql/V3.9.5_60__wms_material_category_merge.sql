-- 物料分类合并：同名同类型分类去重，物料统一挂到存活分类（分类→物料 1:N）。
-- 设计：docs/superpowers/specs/2026-11-11-yx-wms-物料分类合并-design.md
-- 回滚：docs/superpowers/plans/2026-11-11-物料分类合并-回滚.sql
--
-- 口径（2026-11-11 用户确认）：
--   合并键 = category_name + IFNULL(category_type,'')
--   存活分类 = 组内物料数最多 → create_time 最早 → id 最小
--   保留存活分类原编码；其余分类逻辑删除；组号→分类关系写入 wms_material_category_group
--
-- 幂等：可重复执行；第二次执行时已无可合并项，各 INSERT 均为空操作。

-- 统一会话排序规则：新建表/字符串字面量默认 collation 与业务表保持一致（general_ci），
-- 否则 JOIN 比较 category_name 会报 Illegal mix of collations。
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

-- ---------- 1. 新表 ----------
CREATE TABLE IF NOT EXISTS `wms_material_category_group` (
  `id`           varchar(32)  NOT NULL COMMENT '主键',
  `group_number` varchar(100) NOT NULL COMMENT '金蝶物料分组编码（分类无组号时用原 category_code）',
  `group_name`   varchar(100) DEFAULT NULL COMMENT '分组名称（冗余，便于查询）',
  `category_id`  varchar(32)  NOT NULL COMMENT '合并后归属的分类ID（wms_material_category.id）',
  `deleted_at`   bigint       NOT NULL DEFAULT '0' COMMENT '删除时间戳(ms)，0=未删除',
  `create_by`    varchar(50)  DEFAULT NULL,
  `create_time`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wms_cat_group` (`group_number`,`deleted_at`),
  KEY `idx_wms_cat_group_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物料分组→分类映射（分类合并后 1:N）';

CREATE TABLE IF NOT EXISTS `wms_material_category_merge_bak` (
  `category_id`      varchar(32) NOT NULL COMMENT '被合并的分类ID',
  `survivor_id`      varchar(32) NOT NULL COMMENT '存活分类ID',
  `category_code`    varchar(64) DEFAULT NULL,
  `category_name`    varchar(100) DEFAULT NULL,
  `category_type`    varchar(20) DEFAULT NULL,
  `erp_group_number` varchar(100) DEFAULT NULL,
  `create_time`      datetime    DEFAULT NULL,
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分类合并备份（回滚依据）';

CREATE TABLE IF NOT EXISTS `wms_material_merge_bak` (
  `material_id`     varchar(32) NOT NULL,
  `old_category_id` varchar(32) DEFAULT NULL,
  `new_category_id` varchar(32) DEFAULT NULL,
  `create_time`     datetime    DEFAULT NULL,
  PRIMARY KEY (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物料改挂备份（回滚依据）';

-- ---------- 2. 计算存活映射（临时表，脚本末尾删除） ----------
DROP TABLE IF EXISTS `wms_cat_merge_tmp`;
CREATE TABLE `wms_cat_merge_tmp` (
  `category_id` varchar(32) NOT NULL,
  `survivor_id` varchar(32) NOT NULL,
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `wms_cat_merge_tmp` (`category_id`, `survivor_id`)
SELECT c.id, s.survivor_id
FROM `wms_material_category` c
JOIN (
  SELECT category_name, ty, id AS survivor_id
  FROM (
    SELECT c2.category_name,
           IFNULL(c2.category_type, '') AS ty,
           c2.id,
           ROW_NUMBER() OVER (
             PARTITION BY c2.category_name, IFNULL(c2.category_type, '')
             ORDER BY IFNULL(mc.cnt, 0) DESC, c2.create_time ASC, c2.id ASC
           ) AS rn
    FROM `wms_material_category` c2
    LEFT JOIN (
      SELECT category_id, COUNT(*) AS cnt
      FROM `wms_material`
      WHERE deleted_at = 0 AND category_id IS NOT NULL AND category_id <> ''
      GROUP BY category_id
    ) mc ON mc.category_id = c2.id
    WHERE c2.deleted_at = 0
  ) r WHERE r.rn = 1
) s ON s.category_name = c.category_name AND s.ty = IFNULL(c.category_type, '')
WHERE c.deleted_at = 0;

-- ---------- 3. 备份被合并分类 ----------
INSERT IGNORE INTO `wms_material_category_merge_bak`
  (category_id, survivor_id, category_code, category_name, category_type, erp_group_number, create_time)
SELECT t.category_id, t.survivor_id, c.category_code, c.category_name,
       c.category_type, c.erp_group_number, NOW()
FROM `wms_cat_merge_tmp` t
JOIN `wms_material_category` c ON c.id = t.category_id
WHERE t.category_id <> t.survivor_id;

-- ---------- 4. 备份并改挂物料 ----------
INSERT IGNORE INTO `wms_material_merge_bak`
  (material_id, old_category_id, new_category_id, create_time)
SELECT m.id, m.category_id, t.survivor_id, NOW()
FROM `wms_material` m
JOIN `wms_cat_merge_tmp` t ON t.category_id = m.category_id
WHERE m.deleted_at = 0 AND m.category_id IS NOT NULL AND m.category_id <> ''
  AND t.category_id <> t.survivor_id;

UPDATE `wms_material` m
JOIN `wms_material_merge_bak` b ON b.material_id = m.id
SET m.category_id = b.new_category_id
WHERE m.category_id = b.old_category_id;

-- ---------- 5. 组号 → 存活分类 映射 ----------
INSERT INTO `wms_material_category_group`
  (id, group_number, group_name, category_id, deleted_at, create_by, create_time)
SELECT REPLACE(UUID(), '-', ''),
       COALESCE(NULLIF(c.erp_group_number, ''), c.category_code),
       c.category_name, t.survivor_id, 0, 'merge', NOW()
FROM `wms_material_category` c
JOIN `wms_cat_merge_tmp` t ON t.category_id = c.id
WHERE c.deleted_at = 0
ON DUPLICATE KEY UPDATE
  category_id = VALUES(category_id),
  group_name  = VALUES(group_name);

-- ---------- 6. 逻辑删除被合并分类 ----------
UPDATE `wms_material_category` c
JOIN `wms_material_category_merge_bak` b ON b.category_id = c.id
SET c.deleted_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)
WHERE c.deleted_at = 0;

DROP TABLE IF EXISTS `wms_cat_merge_tmp`;
