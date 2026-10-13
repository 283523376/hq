package org.jeecg.modules.wms.constant;

/**
 * WMS 模块常量。
 *
 * <p>集中放置表前缀、模块标识等，避免散落的魔法值（《阿里巴巴Java开发手册》）。
 *
 * @author yongxin
 */
public final class WmsConstants {

    private WmsConstants() {
    }

    /** 业务表统一前缀。 */
    public static final String TABLE_PREFIX = "wms_";

    /** 模块名称，用于日志与 OpenAPI 分组。 */
    public static final String MODULE_NAME = "永鑫WMS";

    /** 树根节点的父ID。 */
    public static final String ROOT_PID_VALUE = "0";

    /** 状态-启用。 */
    public static final String STATUS_ENABLE = "1";

    /** 状态-停用。 */
    public static final String STATUS_DISABLE = "0";

    /** 是。 */
    public static final String YES = "1";

    /** 否。 */
    public static final String NO = "0";

    /** 物料 Excel 导入：标题行 1 + 表头行 1，数据自物理第 3 行开始（行号 = 下标 + 3）。 */
    public static final int IMPORT_ROW_OFFSET = 3;
}
