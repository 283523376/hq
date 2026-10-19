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

    // ---------------------------------------------------------------- 库区类型

    /** 库区类型-立库（半成品立库，24 层 × 6 列量级）。 */
    public static final String WAREHOUSE_TYPE_STEREO = "立库";

    /** 库区类型-敏捷仓（成品敏捷仓，料箱 10 层堆叠）。 */
    public static final String WAREHOUSE_TYPE_AGILE = "敏捷仓";

    /** 库区类型-线边柜（格子柜，不适用排/列/层网格）。 */
    public static final String WAREHOUSE_TYPE_LINE_SIDE = "线边柜";

    /**
     * 库区树中「库区类型」节点的 key 前缀。
     *
     * <p>类型节点不是真实记录，其 key 由类型名合成；加前缀是为了**避免与库区真实 id 撞键**
     * （否则前端树选中类型节点时会被当成某个库区的 id）。
     */
    public static final String WAREHOUSE_TYPE_NODE_PREFIX = "type:";

    // ---------------------------------------------------------------- 库位状态

    /** 库位状态-空闲。 */
    public static final String LOCATION_STATUS_IDLE = "空闲";

    /** 库位状态-占用（视为库位内有物料，禁止修改属性）。 */
    public static final String LOCATION_STATUS_OCCUPIED = "占用";

    /** 库位状态-故障。 */
    public static final String LOCATION_STATUS_FAULT = "故障";

    // ---------------------------------------------------------------- 库位批量生成

    /** 单次生成请求的库位条数上限。 */
    public static final int LOCATION_GEN_MAX_PER_REQUEST = 50000;

    /** 批量生成时每批 INSERT IGNORE 的条数。 */
    public static final int LOCATION_GEN_BATCH_SIZE = 1000;

    /** 库位编码长度上限，与 wms_location.location_code 列宽一致。 */
    public static final int LOCATION_CODE_MAX_LENGTH = 64;

    // ---------------------------------------------------------------- 设备类型

    /** 设备类型-堆垛机。 */
    public static final String DEVICE_TYPE_STACKER = "堆垛机";

    /** 设备类型-SCR 机器人。 */
    public static final String DEVICE_TYPE_SCR = "SCR";

    /** 设备类型-分拣单元。 */
    public static final String DEVICE_TYPE_SORTING_UNIT = "分拣单元";

    /** 设备类型-充电桩。 */
    public static final String DEVICE_TYPE_CHARGER = "充电桩";

    // ---------------------------------------------------------------- 设备状态

    /** 设备状态-在线。 */
    public static final String DEVICE_STATUS_ONLINE = "在线";

    /** 设备状态-离线。 */
    public static final String DEVICE_STATUS_OFFLINE = "离线";

    /** 设备状态-故障。 */
    public static final String DEVICE_STATUS_FAULT = "故障";

    // ---------------------------------------------------------------- 设备离线告警

    /** 心跳静默超过该秒数即判离线（默认 600 秒 = 10 分钟）。 */
    public static final int DEVICE_OFFLINE_THRESHOLD_SECONDS = 600;

    /** 同一设备两次离线告警之间的最小间隔（分钟），防轰炸。 */
    public static final int DEVICE_ALERT_THROTTLE_MINUTES = 30;

    /** 设备离线告警的消息标题。 */
    public static final String DEVICE_ALERT_TITLE = "WMS设备离线告警";

    // ---------------------------------------------------------------- 系统配置（BD-004）

    /** 配置变更类型：保存。 */
    public static final String CONFIG_CHANGE_SAVE = "SAVE";

    /** 配置变更类型：回滚。 */
    public static final String CONFIG_CHANGE_ROLLBACK = "ROLLBACK";

    /** 设备告警收件人配置键。 */
    public static final String DEVICE_ALERT_TO_USER_KEY = "wms.device.alert.to-user";

    /** 离线扫描每轮最多处理的设备数。 */
    public static final int DEVICE_SCAN_BATCH = 200;

    /** 设备离线扫描任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行、注册器三处一致。 */
    public static final String DEVICE_SCAN_JOB_ID = "1920000000000000102";

    /** 设备离线扫描任务类名；必须与 Flyway 种子行的 job_class_name 一致。 */
    public static final String DEVICE_SCAN_JOB_CLASS = "org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob";

    /**
     * 设备离线扫描任务 cron：每 5 分钟，落在 :07 / :12 / …
     *
     * <p>刻意与订单补偿任务（{@link #SYNC_JOB_CRON}，落在 :03 / :08 / …）错开 4 分钟，
     * 避免两轮任务同时打库。
     */
    public static final String DEVICE_SCAN_JOB_CRON = "0 7/5 * * * ? *";

    /** 设备离线扫描任务启停开关的配置键；默认 true。 */
    public static final String DEVICE_SCAN_JOB_ENABLED_KEY = "wms.device.job.enabled";

    // ---------------------------------------------------------------- 入库域（RK-001）

    /** 入库类型：采购入库。 */
    public static final String INBOUND_TYPE_PURCHASE = "采购入库";

    /** 单据状态：待检验（收料后的初始状态）。 */
    public static final String INBOUND_STATUS_PENDING_INSPECT = "待检验";
    /** 单据状态：待分配（检验合格后）。 */
    public static final String INBOUND_STATUS_PENDING_ALLOCATE = "待分配";
    /** 单据状态：待上架（分配完成或有部分分配后可上架）。 */
    public static final String INBOUND_STATUS_PENDING_PUTAWAY = "待上架";
    /** 单据状态：已入库（上架完成、尚未审核）。 */
    public static final String INBOUND_STATUS_PUTAWAY = "已入库";
    /** 单据状态：已审核（putaway 自动审核后的终态）。 */
    public static final String INBOUND_STATUS_AUDITED = "已审核";
    /** 单据状态：已拒收（外包装损坏等收料即拒的终态）。 */
    public static final String INBOUND_STATUS_REJECTED = "已拒收";
    /** 单据状态：检验不合格（转 YX-WMS-RK-005 的终态）。 */
    public static final String INBOUND_STATUS_INSPECT_FAILED = "检验不合格";
    /** 单据状态：作废（录错单补救的终态，仅未产生库存的单据可进入）。 */
    public static final String INBOUND_STATUS_VOIDED = "作废";

    /** 物料分类类型：半成品（自动分配到立库）。 */
    public static final String CATEGORY_TYPE_HALF_FINISHED = "半成品";
    /** 物料分类类型：辅料（自动分配到线边柜）。 */
    public static final String CATEGORY_TYPE_AUXILIARY = "辅料";

    /** 检验状态：待检验。 */
    public static final String INSPECT_STATUS_PENDING = "待检验";
    /** 检验状态：合格。 */
    public static final String INSPECT_STATUS_PASSED = "合格";
    /** 检验状态：不合格。 */
    public static final String INSPECT_STATUS_FAILED = "不合格";

    /** 入库 ERP 同步补偿任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行（V3.9.5_11）一致。 */
    public static final String INBOUND_SYNC_JOB_ID = "1920000000000000160";
    /** 入库 ERP 同步补偿任务类名；必须与 Flyway 种子行的 job_class_name 一致。 */
    public static final String INBOUND_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsInboundSyncRetryJob";
    /** 入库同步任务 cron：每 5 分钟落在 :09/:14/…，与订单补偿（3/5）、设备扫描（7/5）错峰。 */
    public static final String INBOUND_SYNC_JOB_CRON = "0 9/5 * * * ? *";
    /** 同步候选的僵死阈值（分钟）：同步中超过该时长视为僵死，允许补偿任务重新认领。 */
    public static final int INBOUND_SYNC_STALE_MINUTES = 10;
    /** 入库单同步失败告警标题。 */
    public static final String INBOUND_SYNC_ALERT_TITLE = "WMS入库单ERP同步失败告警";
}
