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

    // ---------------------------------------------------------------- 工单类型（ERP 同步）

    /**
     * 工单类型四枚举（spec 2026-10-26 §5.3）。
     *
     * <p>与上面的 {@code ORDER_TYPE_*} 是不同维度：前者是 wms_order 的订单类型，
     * 这里描述的是生产工单（{@code wms_work_order.order_type}）的业务来源类型。
     * 前缀用 {@code WORK_ORDER_TYPE_} 以避开同名常量。
     */
    public static final String WORK_ORDER_TYPE_CUSTOMER = "客户订单";

    public static final String WORK_ORDER_TYPE_STOCK = "备库订单";

    public static final String WORK_ORDER_TYPE_OUTSOURCE = "委外订单";

    public static final String WORK_ORDER_TYPE_TRUSTED = "受托订单";

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

    // ---------------------------------------------------------------- 库存盘点（2026-10-26）

    /** 盘点单状态：草稿。 */
    public static final String STOCKTAKE_STATUS_DRAFT = "草稿";
    /** 盘点单状态：盘点中。 */
    public static final String STOCKTAKE_STATUS_COUNTING = "盘点中";
    /** 盘点单状态：差异确认。 */
    public static final String STOCKTAKE_STATUS_DIFF_CONFIRMED = "差异确认";
    /** 盘点单状态：复核中。 */
    public static final String STOCKTAKE_STATUS_REVIEWING = "复核中";
    /** 盘点单状态：已调整。 */
    public static final String STOCKTAKE_STATUS_ADJUSTED = "已调整";
    /** 盘点单状态：已归档（终态）。 */
    public static final String STOCKTAKE_STATUS_ARCHIVED = "已归档";

    /** 盘点类型：定期（日历触发，全仓或指定仓）。 */
    public static final String STOCKTAKE_TYPE_PERIODIC = "定期";
    /** 盘点类型：周期（滚动圈定部分库位）。 */
    public static final String STOCKTAKE_TYPE_CYCLE = "周期";

    /** 库位盘点冻结：是。 */
    public static final String STOCKTAKE_FROZEN = "1";
    /** 库位盘点冻结：否。 */
    public static final String STOCKTAKE_UNFROZEN = "0";

    /** 库区类型-普通仓（初级产成品仓 / 涂层产成品仓：既非立库也非敏捷仓）。 */
    public static final String WAREHOUSE_TYPE_NORMAL = "普通仓";
    /**
     * 外部仓（VMI/供应商仓）：货权不属于自己，不同步库存、不作为入库目标。
     * 由 {@code master-data.external-stock-codes} 标记（默认 CK007 宜昌永鑫…vmi）。
     */
    public static final String WAREHOUSE_TYPE_EXTERNAL = "外部仓";

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
    /** 物料分类类型：初级产成品（未上套环）。 */
    public static final String CATEGORY_TYPE_PRIMARY = "初级产成品";
    /** 物料分类类型：涂层产成品（未上套环）。 */
    public static final String CATEGORY_TYPE_COATED = "涂层产成品";
    /** 物料分类类型：产成品（已上套环）。 */
    public static final String CATEGORY_TYPE_FINISHED = "产成品";

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

    /** 生产入库状态：草稿（工序汇报落进来，未分配库位、未下发 WCS）。 */
    public static final String PROD_STATUS_DRAFT = "草稿";

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
    /** 任务状态：已完成（WMS 内部值，设备原值为 FINISHED）。 */
    public static final String TASK_STATUS_FINISHED = "已完成";

    /** 设备原始状态：完成（RMS/WCS 回执字面值，与 WMS 内部「已完成」区分）。 */
    public static final String DEVICE_STATUS_FINISHED = "FINISHED";

    /** 设备原始状态：失败。 */
    public static final String DEVICE_STATUS_FAILED = "FAILED";

    /** 设备原始状态：执行中。 */
    public static final String DEVICE_STATUS_RUNNING = "RUNNING";
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

    /**
     * 工单终态：ERP 侧作废同步进来（方案 B，XT-002）。
     *
     * <p>与「已关闭」并列终态，但职责分离：已关闭只由 WMS 关单产生，
     * 已作废只由 ERP 作废同步产生（FCancelStatus=B）。
     */
    public static final String WORK_ORDER_STATUS_CANCELLED = "已作废";

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
    /** 工单同步连续失败告警标题。 */
    public static final String WORK_ORDER_SYNC_ALERT_TITLE = "生产工单ERP同步连续失败告警";

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

    /** 出库类型：外协出库（委外订单驱动；本期仅建只读草稿，不拣货不回写）。 */
    public static final String OUTBOUND_TYPE_SUBCONTRACT = "外协出库";

    /** 出库类型：样品出库（对接研发仓，回写形态待 E5 确认）。 */
    public static final String OUTBOUND_TYPE_SAMPLE = "样品出库";

    /**
     * 出库类型：报废出库（XT-001）。
     *
     * <p>从废品仓把不良品彻底出账。与其他出库不同，它**一步到位**：
     * 废品仓是固定库位、无设备搬运，所以不走「分配→下发→复核→确认」。
     */
    public static final String OUTBOUND_TYPE_SCRAP = "报废出库";

    // ---------------------------------------------------------------- 审计留痕域（XT-002）

    /** 审计业务类型：生产工单。 */
    public static final String AUDIT_BIZ_WORK_ORDER = "工单";
    /** 审计业务类型：入库单。 */
    public static final String AUDIT_BIZ_INBOUND = "入库单";
    /** 审计业务类型：出库单（含领料/销售/样品/外协）。 */
    public static final String AUDIT_BIZ_OUTBOUND = "出库单";
    /** 审计业务类型：调拨单。 */
    public static final String AUDIT_BIZ_TRANSFER = "调拨单";
    /** 审计业务类型：盘点单。 */
    public static final String AUDIT_BIZ_STOCKTAKE = "盘点单";
    /** 审计业务类型：系统配置项。 */
    public static final String AUDIT_BIZ_CONFIG = "系统配置";
    /** 审计业务类型：主数据（物料/仓库/库位/分类/设备）。 */
    public static final String AUDIT_BIZ_MASTER_DATA = "主数据";
    /** 审计业务类型：销售订单（ERP 只读快照）。 */
    public static final String AUDIT_BIZ_SALES_ORDER = "销售订单";

    /** 审计动作：新建。 */
    public static final String AUDIT_ACTION_CREATE = "创建";
    /** 审计动作：修改。 */
    public static final String AUDIT_ACTION_UPDATE = "修改";
    /** 审计动作：审核/确认。 */
    public static final String AUDIT_ACTION_AUDIT = "审核";
    /** 审计动作：作废（含 ERP 作废同步进来）。 */
    public static final String AUDIT_ACTION_CANCEL = "作废";
    /** 审计动作：ERP 同步在 WMS 落地。 */
    public static final String AUDIT_ACTION_SYNC_IN = "同步落地";
    /** 审计动作：WMS 回写 ERP。 */
    public static final String AUDIT_ACTION_WRITE_BACK = "回写ERP";
    /** 审计动作：设备/容器绑定。 */
    public static final String AUDIT_ACTION_BIND = "绑定";
    /** 审计动作：解除绑定。 */
    public static final String AUDIT_ACTION_UNBIND = "解绑";

    /** 审计日志清理任务 id。 */
    public static final String AUDIT_CLEAN_JOB_ID = "wmsAuditCleanJob";
    /** 审计日志清理 cron：每天 03:30（与导出任务清理错开）。 */
    public static final String AUDIT_CLEAN_JOB_CRON = "0 30 3 * * ?";

    /** 审计通道：用户在界面上操作。 */
    public static final String AUDIT_CHANNEL_UI = "界面操作";
    /** 审计通道：定时任务/手工触发从 ERP 同步进来。 */
    public static final String AUDIT_CHANNEL_ERP_SYNC = "ERP同步";
    /** 审计通道：WMS 回写 ERP。 */
    public static final String AUDIT_CHANNEL_ERP_WRITE = "回写ERP";
    /** 审计通道：WMS/RMS 设备回调。 */
    public static final String AUDIT_CHANNEL_DEVICE = "设备回调";
    /** 审计通道：系统定时任务（清理、对账等）。 */
    public static final String AUDIT_CHANNEL_JOB = "定时任务";

    /** 审计结果：成功。 */
    public static final String AUDIT_RESULT_SUCCESS = "成功";
    /** 审计结果：失败。 */
    public static final String AUDIT_RESULT_FAILED = "失败";

    /** 无登录用户的通道（定时任务/回写）统一记这个操作人，不留空。 */
    public static final String AUDIT_OPERATOR_SYSTEM = "system";

    /** 销售出库 ↔ 发货通知单 的关联规则编码（金蝶转换规则 RuleId，2026-10-30 真账套实测）。 */
    public static final String LINK_RULE_DELIVERY_OUTSTOCK = "DeliveryNotice-OutStock";
    /** 发货通知单分录表名（Link 子结构的 STableName）。 */
    public static final String LINK_TABLE_NOTICE_ENTRY = "T_SAL_DELIVERYNOTICEENTRY";
    /** 金蝶默认库存状态：可用（销售出库分录 FStockStatusID 必填）。 */
    public static final String STOCK_STATUS_AVAILABLE = "KCZT01_SYS";
    /** 金蝶表单：发货通知单。 */
    public static final String KINGDEE_FORM_DELIVERY_NOTICE = "SAL_DELIVERYNOTICE";
    /** 金蝶单据状态：创建（草稿）。 */
    public static final String KINGDEE_DOC_STATUS_CREATED = "A";

    /** 领料出库单号前缀。 */
    public static final String OUTBOUND_NO_PREFIX_PICK = "LL";
    /** 销售出库单号前缀。 */
    public static final String OUTBOUND_NO_PREFIX_SALES = "XS";

    /** 样品出库单号前缀。 */
    public static final String OUTBOUND_NO_PREFIX_SAMPLE = "YP";

    /** 报废出库单号前缀（XT-001）。 */
    public static final String OUTBOUND_NO_PREFIX_SCRAP = "BF";

    /** 出库单状态：草稿（外协出库同步生成的只读单，不可分配）。 */
    public static final String OUTBOUND_STATUS_DRAFT = "草稿";
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

    // ==================== CK-004 库存调拨 ====================

    /** 调拨单号前缀（DB=调拨）。 */
    public static final String TRANSFER_NO_PREFIX = "DB";
    /** 调拨单类型：同仓区域搬运（走米塔 task/transfer）。 */
    public static final String TRANSFER_MODE_SAME = "同仓区域";
    /** 调拨单类型：跨仓调拨（米塔只同仓，跨仓段走人工/其他物流）。 */
    public static final String TRANSFER_MODE_CROSS = "跨仓";
    /** 调拨来源：WMS 发起。 */
    public static final String TRANSFER_SOURCE_WMS = "WMS";
    /** 调拨来源：ERP 发起（本期不做，留字段）。 */
    public static final String TRANSFER_SOURCE_ERP = "ERP";

    // --- 实物时钟 exec_status ---
    /** 实物时钟：待执行。 */
    public static final String TRANSFER_EXEC_PENDING = "待执行";
    /** 实物时钟：执行中（已下发/已分配）。 */
    public static final String TRANSFER_EXEC_RUNNING = "执行中";
    /** 实物时钟：在途（跨仓已出发）。 */
    public static final String TRANSFER_EXEC_IN_TRANSIT = "在途";
    /** 实物时钟：待入库（跨仓已到达暂存库位）。 */
    public static final String TRANSFER_EXEC_PENDING_INBOUND = "待入库";
    /** 实物时钟：实物完成（库存已按实际动作移动）。 */
    public static final String TRANSFER_EXEC_COMPLETED = "实物完成";
    /** 实物时钟：异常。 */
    public static final String TRANSFER_EXEC_EXCEPTION = "异常";

    // --- ERP 时钟 sync_status（以下三个为调拨新增，其余复用 SYNC_STATUS_*） ---
    /** ERP 时钟：已提交待审核（金蝶 B）。 */
    public static final String TRANSFER_SYNC_SUBMITTED = "已提交待审核";
    /** ERP 时钟：已审核（金蝶 C），与实物完成同时成立才算「已完成」。 */
    public static final String TRANSFER_SYNC_APPROVED = "已审核";
    /** ERP 时钟：ERP 驳回/作废（金蝶 D 或工作流驳回）。 */
    public static final String TRANSFER_SYNC_REJECTED = "ERP驳回";

    // --- 单据总状态 status（只由 deriveStatus 派生，禁止直接赋值） ---
    /** 总状态：待执行。 */
    public static final String TRANSFER_STATUS_PENDING = "待执行";
    /** 总状态：执行中。 */
    public static final String TRANSFER_STATUS_RUNNING = "执行中";
    /** 总状态：待ERP审核（实物已完成，等金蝶审批）。 */
    public static final String TRANSFER_STATUS_PENDING_ERP = "待ERP审核";
    /** 总状态：已完成（实物完成 + 金蝶 C）。 */
    public static final String TRANSFER_STATUS_COMPLETED = "已完成";
    /** 总状态：异常（含 ERP 驳回待处置）。 */
    public static final String TRANSFER_STATUS_EXCEPTION = "异常";
    /** 总状态：已关闭（终态）。 */
    public static final String TRANSFER_STATUS_CLOSED = "已关闭";

    // --- 驳回处置 ---
    /** 驳回处置：待处置。 */
    public static final String TRANSFER_REJECT_PENDING = "待处置";
    /** 驳回处置：已冲销（生成了反向调拨单）。 */
    public static final String TRANSFER_REJECT_REVERSED = "已冲销";
    /** 驳回处置：已人工关闭。 */
    public static final String TRANSFER_REJECT_CLOSED = "已人工关闭";

    // --- 金蝶调拨单据常量（口径见 spec §1） ---
    /** 金蝶直接调拨单 FormId。 */
    public static final String KINGDEE_TRANSFER_FORM_ID = "STK_TransferDirect";
    /** 金蝶直接调拨单标准单据类型。 */
    public static final String KINGDEE_TRANSFER_BILL_TYPE = "ZJDB01_SYS";
    /** 金蝶调拨类型：同组织内部调拨（101 已实测）。 */
    public static final String KINGDEE_TRANSFER_BIZ_TYPE = "InnerOrgTransfer";
    /** 金蝶调拨方向：普通。 */
    public static final String KINGDEE_TRANSFER_DIRECT = "GENERAL";
    /** 金蝶单据状态：已审核（C）。 */
    public static final String KINGDEE_DOC_STATUS_APPROVED = "C";
    /** 金蝶单据状态：审核中（B）。 */
    public static final String KINGDEE_DOC_STATUS_SUBMITTED = "B";
    /** 金蝶单据状态：暂存（A）。 */
    public static final String KINGDEE_DOC_STATUS_SAVED = "A";

    // --- 告警与任务 ---
    /** 调拨审批超时告警标题。 */
    public static final String TRANSFER_APPROVAL_TIMEOUT_ALERT_TITLE = "WMS调拨审批超时告警";
    /** 调拨 ERP 驳回告警标题。 */
    public static final String TRANSFER_REJECT_ALERT_TITLE = "WMS调拨ERP驳回告警";
    /** 调拨设备/库位异常告警标题。 */
    public static final String TRANSFER_DEVICE_ALERT_TITLE = "WMS调拨设备异常告警";
    /** 调拨 ERP 同步失败告警标题。 */
    public static final String TRANSFER_SYNC_ALERT_TITLE = "WMS调拨同步失败告警";

    /** 调拨同步任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String TRANSFER_SYNC_JOB_ID = "1920000000000000350";
    /** 调拨同步任务类名；必须与 Flyway 种子行一致。 */
    public static final String TRANSFER_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsTransferSyncJob";
    /** 调拨同步 cron：每 10 分钟落在 :23，与其它补偿任务错峰。 */
    public static final String TRANSFER_SYNC_JOB_CRON = "0 23/10 * * * ? *";
    /** 调拨同步单批处理条数。 */
    public static final int TRANSFER_SYNC_BATCH_SIZE = 50;
    /** 调拨同步最大重试次数（超过转人工）。 */
    public static final int TRANSFER_SYNC_MAX_RETRY = 3;

    // ==================== CK-002 销售订单（ERP → WMS） ====================

    /** 销售订单拉取任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String SALES_ORDER_SYNC_JOB_ID = "1920000000000000360";

    /** 主数据（仓库/分类）同步任务：默认不注册（{@code master-data.enabled=false}）。 */
    public static final String MASTER_DATA_SYNC_JOB_ID = "1920000000000000370";

    /** 每天 03:00，避开补偿任务的 :03/:08 节奏。 */
    public static final String MASTER_DATA_SYNC_JOB_CRON = "0 0 3 * * ?";
    /** 销售订单拉取任务类名；必须与 Flyway 种子行一致。 */
    public static final String SALES_ORDER_SYNC_JOB_CLASS = "org.jeecg.modules.wms.job.WmsSalesOrderSyncJob";
    /** 销售订单拉取 cron：每 15 分钟落在 :29，与其它任务错峰。 */
    public static final String SALES_ORDER_SYNC_JOB_CRON = "0 29/15 * * * ? *";
    /** 每轮回看天数（拉「近 N 天 + 状态 C」的销售订单）。 */
    public static final int SALES_ORDER_SYNC_DAYS = 7;
    /** 每轮拉取上限。 */
    public static final int SALES_ORDER_SYNC_LIMIT = 200;
    /** 快照拉取状态：待拉取。 */
    public static final String SALES_FETCH_PENDING = "待拉取";
    /** 快照拉取状态：成功。 */
    public static final String SALES_FETCH_SUCCESS = "成功";
    /** 快照拉取状态：失败。 */
    public static final String SALES_FETCH_FAILED = "失败";
    /** 销售订单可出库的金蝶状态：已审核。 */
    public static final String SALES_ORDER_STATUS_APPROVED = "C";
    /** 销售订单拉取失败告警标题。 */
    public static final String SALES_ORDER_SYNC_ALERT_TITLE = "WMS销售订单拉取失败告警";

    // ==================== KC-001 库存预警 ====================

    /** 预警规则类型：库存下限。 */
    public static final String WARNING_TYPE_LOW_STOCK = "LOW_STOCK";
    /** 预警规则类型：存放超期。 */
    public static final String WARNING_TYPE_OVERDUE = "OVERDUE";

    /** 规则作用域：单物料。 */
    public static final String WARNING_SCOPE_MATERIAL = "MATERIAL";
    /** 规则作用域：物料分类。 */
    public static final String WARNING_SCOPE_CATEGORY = "CATEGORY";
    /** 规则作用域：全部物料。 */
    public static final String WARNING_SCOPE_ALL = "ALL";

    /** 预警状态：待处理。 */
    public static final String WARNING_STATUS_PENDING = "PENDING";
    /** 预警状态：已解除。 */
    public static final String WARNING_STATUS_RESOLVED = "RESOLVED";

    /** 处理方式：补料。 */
    public static final String WARNING_HANDLE_REPLENISH = "REPLENISH";
    /** 处理方式：清理。 */
    public static final String WARNING_HANDLE_CLEAR = "CLEAR";
    /** 处理方式：其他。 */
    public static final String WARNING_HANDLE_OTHER = "OTHER";

    /** 预警单号前缀。 */
    public static final String WARNING_NO_PREFIX = "WRN";
    /** 超期天数上限（10 年），超出视为配置错误。 */
    public static final int WARNING_OVERDUE_MAX_DAYS = 3650;
    /** 单次扫描单条规则最多产出的超期行数，防慢 SQL 与大结果集。 */
    public static final int WARNING_SCAN_ROW_LIMIT = 2000;
    /** 合并去重键解除后缀分隔符。 */
    public static final String WARNING_DEDUP_RESOLVED_SEP = "#R#";

    /** 预警扫描任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String INVENTORY_WARNING_SCAN_JOB_ID = "1920000000000000380";
    /** 预警扫描任务类名。 */
    public static final String INVENTORY_WARNING_SCAN_JOB_CLASS =
            "org.jeecg.modules.wms.job.WmsInventoryWarningScanJob";
    /** 默认每天 08:00，避开 ERP 补偿任务的凌晨节奏。 */
    public static final String INVENTORY_WARNING_SCAN_JOB_CRON = "0 0 8 * * ?";

    // ==================== 工序汇报（半成品来源） ====================

    /** 工序汇报同步任务在 sys_quartz_job 中的主键；必须与 Flyway 种子行一致。 */
    public static final String OPERATION_REPORT_SYNC_JOB_ID = "1920000000000000390";
    /** 工序汇报同步任务类名。 */
    public static final String OPERATION_REPORT_SYNC_JOB_CLASS =
            "org.jeecg.modules.wms.job.WmsOperationReportSyncJob";
    /** 每小时 :41（避开工单 :01、订单 :03、设备 :07、入库 :09/:13、出库 :17、调拨 :23、销售 :29）。 */
    public static final String OPERATION_REPORT_SYNC_JOB_CRON = "0 41 * * * ?";

    // ==================== KC-004 库存台账 ====================

    /** 台账分页上限：查询接口是给人看的，导出走异步任务。 */
    public static final int INVENTORY_LEDGER_MAX_PAGE_SIZE = 200;
    /** 台账分页默认条数。 */
    public static final int INVENTORY_LEDGER_DEFAULT_PAGE_SIZE = 20;
    /** 导出每批从库里取的行数。 */
    public static final int INVENTORY_LEDGER_EXPORT_BATCH_SIZE = 5000;
    /**
     * xlsx 单 sheet 数据行上限。
     *
     * <p>xlsx 物理上限 1048576 行，台账文件还要放 2 行标题 + 1 行表头，
     * 所以数据行守卫是 1048573——守卫在前，AutoPoi 的滚动分页就不会先触发。
     */
    public static final int INVENTORY_LEDGER_EXCEL_MAX_ROWS = 1048573;
    /** 单 sheet 物理行上限（xlsx 规范），传给 ExportParams.setMaxNum 禁用滚动分页。 */
    public static final int INVENTORY_LEDGER_SHEET_PHYSICAL_MAX = 1048576;

    // ==================== KC-004 导出任务 ====================

    /** 任务类型：库存台账。 */
    public static final String EXPORT_TASK_TYPE_INVENTORY_LEDGER = "INVENTORY_LEDGER";

    /** 导出任务类型：审计日志（XT-002）。 */
    public static final String EXPORT_TASK_TYPE_AUDIT_LOG = "AUDIT_LOG";

    /** 审计日志导出每批取数。 */
    public static final int AUDIT_EXPORT_BATCH_SIZE = 2000;
    /** 任务状态：排队。 */
    public static final String EXPORT_STATUS_PENDING = "PENDING";
    /** 任务状态：生成中。 */
    public static final String EXPORT_STATUS_RUNNING = "RUNNING";
    /** 任务状态：成功。 */
    public static final String EXPORT_STATUS_SUCCESS = "SUCCESS";
    /** 任务状态：失败。 */
    public static final String EXPORT_STATUS_FAILED = "FAILED";
    /** 导出文件保留天数（终态后用 finish_time + N 天算 expire_time）。 */
    public static final int EXPORT_RETENTION_DAYS = 7;
    /** 失败原因落库长度上限。 */
    public static final int EXPORT_ERROR_MSG_MAX_LEN = 1000;
    /** 导出任务清理任务 ID。 */
    public static final String EXPORT_TASK_CLEAN_JOB_ID = "1920000000000000400";
    /** 每天 03:00 清理过期导出任务。 */
    public static final String EXPORT_TASK_CLEAN_JOB_CRON = "0 0 3 * * ?";
    /** 导出任务列表每页上限（按人过滤量本来就小，这里只是防手滑传个大数）。 */
    public static final int EXPORT_TASK_MAX_PAGE_SIZE = 100;
    /** 清理批大小。 */
    public static final int EXPORT_TASK_CLEAN_BATCH_SIZE = 200;

    /** 同步日志对象类型：库存预警扫描。 */
    public static final String SYNC_LOG_TYPE_INVENTORY_WARNING = "INVENTORY_WARNING";
    /** 同步日志方向：WMS → ERP（SB-001 对账查询金蝶即时库存）。 */
    public static final String SYNC_LOG_DIRECTION_WMS_TO_ERP = "WMS_TO_ERP";
    /** 同步日志方向：WMS 内部计算（非 ERP 交互）。 */
    public static final String SYNC_LOG_DIRECTION_WMS_INTERNAL = "WMS_INTERNAL";
    /** 同步日志对端：WMS 自身。 */
    public static final String SYNC_LOG_TARGET_WMS = "WMS";

    // ==================== SB-001 上位系统同步对账 ====================

    /** 对账批次状态-执行中（spec §2.5 wms_sync_run.status）。 */
    public static final String SYNC_RUN_STATUS_RUNNING = "执行中";
    /** 对账批次状态-成功（spec §2.5）。 */
    public static final String SYNC_RUN_STATUS_SUCCESS = "成功";
    /** 对账批次状态-失败（3 次重试仍失败后的终态，spec §2.6/§2.5）。 */
    public static final String SYNC_RUN_STATUS_FAILED = "失败";

    /** 对账范围-全量库存：101 全组织，产生「仅ERP」（spec §2.2/§2.4）。 */
    public static final String SYNC_SCOPE_FULL = "FULL";
    /** 对账范围-今日变动：比对域 = WMS 快照 key 集合，不产生「仅ERP」（spec §2.2/§2.4）。 */
    public static final String SYNC_SCOPE_TODAY = "TODAY";
    /** 对账范围-指定仓库：所选仓库内全部 key，产生「仅ERP」（spec §2.2/§2.4）。 */
    public static final String SYNC_SCOPE_WAREHOUSE = "WAREHOUSE";

    /** 快照匹配-一致：两边都有且数量相等，不入差异清单（spec §2.4）。 */
    public static final String SNAPSHOT_MATCH_EQUAL = "一致";
    /** 快照匹配-数量差异：两边都有但数量不等，diff_type 细分多账/少账（spec §2.4）。 */
    public static final String SNAPSHOT_MATCH_QTY_DIFF = "数量差异";
    /** 快照匹配-仅WMS：WMS 有 ERP 无；diff_type 复用本值（spec §2.4）。 */
    public static final String SNAPSHOT_MATCH_WMS_ONLY = "仅WMS";
    /** 快照匹配-仅ERP：ERP 有 WMS 无；diff_type 复用本值（spec §2.4）。 */
    public static final String SNAPSHOT_MATCH_ERP_ONLY = "仅ERP";

    /** 差异类型-多账：WMS > ERP，diff = wms - erp（spec §2.4）。 */
    public static final String DIFF_TYPE_MORE = "多账";
    /** 差异类型-少账：WMS < ERP，diff = wms - erp（负数）（spec §2.4）。 */
    public static final String DIFF_TYPE_LESS = "少账";

    /** 差异状态-待核对：差异清单行的初始状态（spec §2.5/§2.8）。 */
    public static final String DIFF_STATUS_PENDING = "待核对";
    /** 差异状态-已核对：待核对后的人工处理终态之一（spec §2.8）。 */
    public static final String DIFF_STATUS_CHECKED = "已核对";
    /** 差异状态-已忽略：待核对后的人工处理终态之二（spec §2.8）。 */
    public static final String DIFF_STATUS_IGNORED = "已忽略";

    /** 同步日志对象类型：库存对账批次（objectNo=run_no，spec §2.9）。 */
    public static final String SYNC_LOG_TYPE_RECONCILE = "INVENTORY_RECONCILE";

    /** 对账失败告警标题（spec §2.7）。 */
    public static final String RECONCILE_ALERT_TITLE = "SB-001 上位系统对账失败告警";

    /**
     * 对账任务 JobKey（spec §2.2）：每日 23:00 定时对账，沿用 {@link #AUDIT_CLEAN_JOB_ID}
     * 的字符串 JobKey 风格（非 sys_quartz_job 数字种子行模式）。
     */
    public static final String SYNC_RECONCILE_JOB_ID = "wmsSyncReconcileJob";
    /** 对账任务 cron：每日 23:00（spec §2.2）。 */
    public static final String SYNC_RECONCILE_JOB_CRON = "0 0 23 * * ?";
    /** 同步数据清理任务 JobKey（spec §2.9.1），字符串 JobKey 风格。 */
    public static final String SYNC_RETENTION_JOB_ID = "wmsSyncRetentionJob";
    /** 清理任务 cron：每日 03:20，与审计清理（03:30）、导出清理（03:00）错峰（spec §2.9.1）。 */
    public static final String SYNC_RETENTION_JOB_CRON = "0 20 3 * * ?";

    /** 审计业务类型：上位系统同步（差异标记/发起盘点等，spec §2.9）。 */
    public static final String AUDIT_BIZ_SYNC = "上位同步";
}
