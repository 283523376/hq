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

    /**
     * 物料 Excel 导入：与平台导出/模板格式一致 —— 第 1 行标题、第 2 行第二标题（导出人/填写说明）、
     * 第 3 行表头，数据自物理第 4 行开始（行号 = 列表下标 + 4）。
     */
    public static final int IMPORT_ROW_OFFSET = 4;

    // ---------------------------------------------------------------- 订单类型

    /** 订单类型-公司订单。 */
    public static final String ORDER_TYPE_COMPANY = "公司订单";

    /** 订单类型-采购订单。 */
    public static final String ORDER_TYPE_PURCHASE = "采购订单";

    /** 订单类型-委外订单。 */
    public static final String ORDER_TYPE_OUTSOURCE = "委外订单";

    // ---------------------------------------------------------------- 订单状态

    /** 订单状态-待审核。 */
    public static final String ORDER_STATUS_PENDING_AUDIT = "待审核";

    /** 订单状态-已审核。 */
    public static final String ORDER_STATUS_AUDITED = "已审核";

    /** 订单状态-已下达。 */
    public static final String ORDER_STATUS_DISPATCHED = "已下达";

    /** 订单状态-已驳回。 */
    public static final String ORDER_STATUS_REJECTED = "已驳回";

    // ---------------------------------------------------------------- 同步状态

    /** 同步状态-待同步。 */
    public static final String SYNC_STATUS_PENDING = "待同步";

    /** 同步状态-同步中（认领标记，用于互斥并发同步）。 */
    public static final String SYNC_STATUS_RUNNING = "同步中";

    /** 同步状态-同步成功。 */
    public static final String SYNC_STATUS_SUCCESS = "同步成功";

    /** 同步状态-同步失败。 */
    public static final String SYNC_STATUS_FAILED = "同步失败";

    // ---------------------------------------------------------------- 同步重试

    /** 最大重试次数；连同首次调用，最多尝试 {@code SYNC_MAX_RETRY + 1} 次。 */
    public static final int SYNC_MAX_RETRY = 3;

    /** 每次重试前的退避毫秒数；下标即第几次重试（下标 0 → 第 1 次重试之前）。 */
    public static final long[] SYNC_RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

    /** 同步失败原因落库长度上限，与 {@code wms_order.last_sync_error} 列宽一致。 */
    public static final int SYNC_ERROR_MAX_LENGTH = 500;

    /** 同步失败告警的消息标题。 */
    public static final String SYNC_ALERT_TITLE = "WMS订单同步失败告警";

    /** 告警消息的发送人标识。 */
    public static final String ALERT_FROM_USER = "system";

    /**
     * 告警消息的默认收件人（登录账号）。
     *
     * <p><b>不可为 null</b>：平台 {@code SysBaseApiImpl} 会对该值执行 {@code split(",")}，
     * 传 null 会抛 NPE 并被调用方的 try/catch 吞掉，表现为「公告插入成功但收件人零条」——
     * 管理员实际收不到任何告警。可用配置 {@code wms.sync.alert.to-user} 覆盖。
     */
    public static final String ALERT_TO_USER = "admin";

    // ---------------------------------------------------------------- 定时补偿任务

    /** 补偿任务在 {@code sys_quartz_job} 中的主键；必须与 Flyway 种子行、注册器三处一致。 */
    public static final String SYNC_JOB_ID = "1920000000000000101";

    /** 补偿任务类名；必须与 Flyway 种子行的 {@code job_class_name} 一致。 */
    public static final String SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsOrderSyncRetryJob";

    /** 补偿任务 cron：每 5 分钟，落在 :03/:08/…，错开整点避免与平台其它任务堆叠。 */
    public static final String SYNC_JOB_CRON = "0 3/5 * * * ? *";

    /** 补偿任务每轮最多处理的订单数，限制单轮耗时上限。 */
    public static final int SYNC_SCAN_BATCH = 50;

    /** 「同步中」僵死回收阈值（秒），须远大于单次同步最坏耗时（约 7 秒）。 */
    public static final int SYNC_RECLAIM_TIMEOUT_SECONDS = 300;

    /** 补偿任务启停开关的配置键；默认 true。 */
    public static final String SYNC_JOB_ENABLED_KEY = "wms.sync.job.enabled";
}
