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

    /** 连续同步失败达到该次数时，补偿任务补发一次告警（成功后计数清零）。 */
    public static final int SYNC_ALERT_AFTER_FAILS = 3;

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

    /**
     * 同步状态：无需同步。
     *
     * <p>已拒收 / 检验不合格 / 作废 的入库单不会同步 ERP，界面应显示「无需同步」
     * 而不是一直停留在「待同步」。
     */
    public static final String SYNC_STATUS_NOT_REQUIRED = "无需同步";

    // ---------------------------------------------------------------- 生产入库域（RK-002）

    /** 产品类型：半成品（立库 + 堆垛机 + 周转盘 RFID）。 */
    public static final String PRODUCT_TYPE_SEMI = "半成品";
    /** 产品类型：成品（敏捷仓 + SCR 机器人 + 料箱二维码）。 */
    public static final String PRODUCT_TYPE_FINISHED = "成品";

    /** 标识类型：RFID（贴在周转盘上）。 */
    public static final String IDENT_TYPE_RFID = "RFID";
    /** 标识类型：二维码（贴在料箱上）。 */
    public static final String IDENT_TYPE_QRCODE = "二维码";

    /** 生产入库状态：待上架。 */
    public static final String PROD_STATUS_PENDING_PUTAWAY = "待上架";
    /** 生产入库状态：执行中。 */
    public static final String PROD_STATUS_EXECUTING = "执行中";
    /** 生产入库状态：已入库。 */
    public static final String PROD_STATUS_INBOUND = "已入库";
    /** 生产入库状态：异常待人工。 */
    public static final String PROD_STATUS_EXCEPTION = "异常待人工";
    /** 生产入库状态：已关闭。 */
    public static final String PROD_STATUS_CLOSED = "已关闭";

    /** 任务状态：未下发。 */
    public static final String TASK_STATUS_NOT_DISPATCHED = "未下发";
    /** 任务状态：已受理。 */
    public static final String TASK_STATUS_ACCEPTED = "已受理";
    /** 任务状态：执行中。 */
    public static final String TASK_STATUS_EXECUTING = "执行中";
    /** 任务状态：已完成。 */
    public static final String TASK_STATUS_FINISHED = "已完成";
    /** 任务状态：失败。 */
    public static final String TASK_STATUS_FAILED = "失败";
    /** 任务状态：已关闭。 */
    public static final String TASK_STATUS_CLOSED = "已关闭";

    /** 工单状态：待生产（金蝶 FDocumentStatus=A/B，未审核，不可入库/领料）。 */
    public static final String WORK_ORDER_STATUS_PENDING = "待生产";
    /** 工单状态：已下达（金蝶 FDocumentStatus=C 已审核，同步落地即可执行）。 */
    public static final String WORK_ORDER_STATUS_RELEASED = "已下达";
    /** 工单状态：生产中。 */
    public static final String WORK_ORDER_STATUS_IN_PROGRESS = "生产中";
    /** 工单状态：已完成。 */
    public static final String WORK_ORDER_STATUS_FINISHED = "已完成";
    /** 工单状态：已关闭。 */
    public static final String WORK_ORDER_STATUS_CLOSED = "已关闭";

    /** 工单来源：MES。 */
    public static final String WORK_ORDER_SOURCE_MES = "MES";
    /** 工单来源：ERP（金蝶 PRD_MO 同步，BD-002 V2.2 起为唯一业务来源）。 */
    public static final String WORK_ORDER_SOURCE_ERP = "ERP";
    /** 工单来源：手工。 */
    public static final String WORK_ORDER_SOURCE_MANUAL = "手工";

    /** 工单 ERP 同步任务 ID（sys_quartz_job 种子同值）。 */
    public static final String WORK_ORDER_SYNC_JOB_ID = "1920000000000000340";
    /** 工单 ERP 同步任务 cron：与订单(3/5)、设备(7/5)、采购入库(9/5)、生产入库(13/5)、出库(17/5) 错峰。 */
    public static final String WORK_ORDER_SYNC_JOB_CRON = "0 1/5 * * * ? *";
    /** 同步日志对象类型：生产工单。 */
    public static final String SYNC_LOG_TYPE_WORK_ORDER = "WORK_ORDER";
    /** 同步日志方向：ERP → WMS。 */
    public static final String SYNC_LOG_DIRECTION_ERP_TO_WMS = "ERP_TO_WMS";
    /** 同步日志对端：金蝶。 */
    public static final String SYNC_LOG_TARGET_KINGDEE = "KINGDEE";

    /** 库区类型：废品仓（不良品直接写库存，不做库位分配）。 */
    public static final String WAREHOUSE_TYPE_SCRAP = "废品仓";
    /** 废品仓默认库位编码。 */
    public static final String LOCATION_CODE_SCRAP_DEFAULT = "FPC-DEFAULT";
    /** 敏捷仓在库默认位编码（成品库存聚合位）。 */
    public static final String LOCATION_CODE_AGILE_DEFAULT = "MJ-DEFAULT";

    /** 生产入库单号前缀。 */
    public static final String INBOUND_NO_PREFIX_PRODUCTION = "SC";
    /** 成品任务号前缀（米塔 RMS）。 */
    public static final String TASK_NO_PREFIX_RMS = "RMS";
    /** 半成品任务号前缀（堆垛机 WCS）。 */
    public static final String TASK_NO_PREFIX_WCS = "WCS";

    /** 未下发补偿阈值（分钟）：提交后超过该时长仍「未下发」即重新下发。 */
    public static final int UNDISPATCHED_TIMEOUT_MINUTES = 2;

    /** RMS 错误码：任务号已存在（重复下发按已受理处理）。 */
    public static final int RMS_ERROR_TASK_EXISTS = 300001;
    /** RMS 错误码：容器编码已存在（转人工）。 */
    public static final int RMS_ERROR_CONTAINER_EXISTS = 300006;
    /** RMS 错误码：接口调用失败，可重试。 */
    public static final int RMS_ERROR_RETRYABLE = 300000;

    /** 生产入库同步补偿任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String PRODUCTION_SYNC_JOB_ID = "1920000000000000230";
    /** 生产入库同步补偿任务类名；必须与 Flyway 种子行一致。 */
    public static final String PRODUCTION_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsProductionSyncRetryJob";
    /** 生产入库补偿 cron：每 5 分钟落在 :13/:18/…，与其它补偿任务错峰。 */
    public static final String PRODUCTION_SYNC_JOB_CRON = "0 13/5 * * * ? *";
    /** 生产入库同步失败告警标题。 */
    public static final String PRODUCTION_SYNC_ALERT_TITLE = "WMS生产入库同步失败告警";
    /** 生产入库设备异常告警标题。 */
    public static final String PRODUCTION_DEVICE_ALERT_TITLE = "WMS生产入库设备异常告警";
    /** 生产入库库位不足告警标题。 */
    public static final String PRODUCTION_LOCATION_ALERT_TITLE = "WMS生产入库库位不足告警";

    // ---------------------------------------------------------------- 出库域（CK-001/CK-002）

    /** 出库类型：生产领料（半成品立库）。 */
    public static final String OUTBOUND_TYPE_PICK = "生产领料";
    /** 出库类型：销售出库（成品敏捷仓）。 */
    public static final String OUTBOUND_TYPE_SALES = "销售出库";

    /** 领料出库单号前缀。 */
    public static final String OUTBOUND_NO_PREFIX_PICK = "LL";
    /** 销售出库单号前缀。 */
    public static final String OUTBOUND_NO_PREFIX_SALES = "XS";

    /** 出库单状态：待分配。 */
    public static final String OUTBOUND_STATUS_PENDING_ALLOCATE = "待分配";
    /** 出库单状态：已分配。 */
    public static final String OUTBOUND_STATUS_ALLOCATED = "已分配";
    /** 出库单状态：执行中。 */
    public static final String OUTBOUND_STATUS_EXECUTING = "执行中";
    /** 出库单状态：待复核（CK-001 等价于待取料确认）。 */
    public static final String OUTBOUND_STATUS_PENDING_REVIEW = "待复核";
    /** 出库单状态：复核异常。 */
    public static final String OUTBOUND_STATUS_REVIEW_EXCEPTION = "复核异常";
    /** 出库单状态：异常待人工。 */
    public static final String OUTBOUND_STATUS_EXCEPTION = "异常待人工";
    /** 出库单状态：已完成。 */
    public static final String OUTBOUND_STATUS_COMPLETED = "已完成";
    /** 出库单状态：已关闭。 */
    public static final String OUTBOUND_STATUS_CLOSED = "已关闭";

    /** 复核状态：待复核。 */
    public static final String REVIEW_STATUS_PENDING = "待复核";
    /** 复核状态：一致。 */
    public static final String REVIEW_STATUS_MATCHED = "一致";
    /** 复核状态：不一致。 */
    public static final String REVIEW_STATUS_MISMATCHED = "不一致";
    /** 复核状态：已放行。 */
    public static final String REVIEW_STATUS_RELEASED = "已放行";

    /** 需求来源：金蝶用料清单。 */
    public static final String REQUIREMENT_SOURCE_PPBOM = "金蝶用料清单";
    /** 需求来源：手工录入。 */
    public static final String REQUIREMENT_SOURCE_MANUAL = "手工";

    /** 出库同步补偿任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String OUTBOUND_SYNC_JOB_ID = "1920000000000000300";
    /** 出库同步补偿任务类名；必须与 Flyway 种子行一致。 */
    public static final String OUTBOUND_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsOutboundSyncRetryJob";
    /** 出库补偿 cron：每 5 分钟落在 :17/:22/…，与其它补偿任务错峰。 */
    public static final String OUTBOUND_SYNC_JOB_CRON = "0 17/5 * * * ? *";
    /** 出库同步候选的僵死阈值（分钟）。 */
    public static final int OUTBOUND_SYNC_STALE_MINUTES = 10;
    /** 出库未下发补偿阈值（分钟）：分配后超过该时长仍已分配即重新下发。 */
    public static final int UNDISPATCHED_OUTBOUND_TIMEOUT_MINUTES = 2;

    /** 出库库存不足告警标题。 */
    public static final String OUTBOUND_STOCK_ALERT_TITLE = "WMS出库库存不足告警";
    /** 出库库位/料箱异常告警标题。 */
    public static final String OUTBOUND_LOCATION_ALERT_TITLE = "WMS出库库位异常告警";
    /** 出库设备异常告警标题。 */
    public static final String OUTBOUND_DEVICE_ALERT_TITLE = "WMS出库设备异常告警";
    /** 出库复核不一致告警标题。 */
    public static final String OUTBOUND_REVIEW_ALERT_TITLE = "WMS出库复核不一致告警";
    /** 出库单同步失败告警标题。 */
    public static final String OUTBOUND_SYNC_ALERT_TITLE = "WMS出库同步失败告警";
    /** 销售订单同步失败告警标题。 */
    public static final String SALE_ORDER_SYNC_ALERT_TITLE = "WMS销售订单同步失败告警";
}
