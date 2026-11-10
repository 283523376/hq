-- 金蝶仓位作为「属性/分类」同步（用户 2026-10-28 明确：金蝶仓位是逻辑库位，不是物理货位）
-- 见 docs/superpowers/specs/2026-10-28-yx-wms-bin-sync-analysis-design.md
CREATE TABLE wms_kingdee_bin (
    id                VARCHAR(32)  NOT NULL COMMENT '主键',
    org_number        VARCHAR(32)  NOT NULL COMMENT '金蝶组织编码',
    flex_number       VARCHAR(64)  NOT NULL COMMENT '仓位值集编码（BD_FLEXVALUES.FNumber）',
    bin_number        VARCHAR(64)  NOT NULL COMMENT '仓位编码（FLEXVALUESENTRY.Number）',
    bin_name          VARCHAR(128) NULL     COMMENT '仓位名称',
    flex_id           BIGINT       NULL     COMMENT '金蝶仓位内码（回写 FStockLocId 直接用）',
    sync_status       VARCHAR(16)  NOT NULL DEFAULT '正常' COMMENT '正常/金蝶已停用/待处理',
    last_sync_time    DATETIME     NULL     COMMENT '最后同步时间',
    create_by         VARCHAR(50)  NULL,
    create_time       DATETIME     NULL,
    update_by         VARCHAR(50)  NULL,
    update_time       DATETIME     NULL,
    deleted_at        BIGINT       NOT NULL DEFAULT 0 COMMENT '0=未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_kingdee_bin (org_number, flex_number, bin_number, deleted_at),
    KEY idx_wms_kingdee_bin_org (org_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '金蝶仓位属性（逻辑库位/分类）';

-- WMS 物理货位上的「金蝶仓位属性」（可空：大部分货位没有）
ALTER TABLE wms_location
    ADD COLUMN kingdee_bin_id VARCHAR(32) NULL COMMENT '金蝶仓位属性ID（wms_kingdee_bin.id）' AFTER status,
    ADD COLUMN kingdee_bin_number VARCHAR(64) NULL COMMENT '金蝶仓位编码（冗余展示）' AFTER kingdee_bin_id;
