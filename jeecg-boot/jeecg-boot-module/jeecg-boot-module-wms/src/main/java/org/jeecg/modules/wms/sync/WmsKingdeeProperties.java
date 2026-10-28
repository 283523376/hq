package org.jeecg.modules.wms.sync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶云星空业务同步配置（wms.sync.erp.kingdee.*）。
 *
 * <p>组织/单据类型/库存状态等默认值来自测试环境实测，生产需按顾问值覆盖；
 * appSecret 只从环境变量注入，禁止写入仓库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms.sync.erp.kingdee")
public class WmsKingdeeProperties {

    /** 是否启用金蝶业务同步（ERP 目标改为 PRD_INSTOCK Save/Submit）。 */
    private boolean enabled;

    private String baseUrl;
    private String acctId;
    private String username;
    private String appId;
    private String appSecret;
    private int lcid = 2052;
    private int timeoutMs = 30_000;
    private boolean allowHttp;

    /** 生产组织 / 入库组织 / 货主 / 保管者等默认值（测试环境实测）。 */
    private String defaultOrgId;
    /** 组织编码（ORG_Organizations.FNumber），基础资料字典用，测试环境为 100。 */
    private String defaultOrgNumber;
    private String billType;
    private String ownerType;
    private String ownerId;
    private String keeperType;
    private String keeperId;
    private String stockStatusId;
    private String inStockType;

    // ---------------------------------------------------------------- 生产入库下推（半成品 BOM 版本）
    /**
     * 半成品生产入库是否走「生产订单下推」。
     *
     * <p>物料启用 BOM 版本管理时，PRD_INSTOCK 的 FBomId 在 Save 时被服务端忽略
     * （元数据 IsEditVisible=false），只能由生产订单下推时由转换规则带出。
     * false = 退回旧的直接 Save 路径（回滚开关，半成品仍会因 BOM 失败）。
     */
    private boolean pushInboundEnabled = true;

    /**
     * 允许下推的业务流程 → 目标单据类型映射。
     *
     * <p>用**列表**而不是 Map：中文做 Map 的 key 时 Spring 绑定会静默丢条目（2026-10-27 实测，
     * 运行期只剩 `__empty__` 一条）；中文作为「值」绑定是可靠的。
     *
     * <p>流程名为空时用哨兵 {@code __empty__} 匹配（空串键会让 Map 绑定抛 BindException，此前踩过）。
     * 表里没有的流程一律拒绝下推，并把金蝶原文透传给用户。
     */
    private List<FlowBillType> pushFlowBillTypes = new ArrayList<>();

    /** 单条「业务流程 → 目标单据类型」映射。 */
    @Data
    public static class FlowBillType {
        /** 业务流程名称；{@code __empty__} 表示业务流程为空。 */
        private String flow;
        /** 目标单据类型编码，如 SCRKD01_SYS。 */
        private String billType;
    }

    /** 默认业务流程：现网是否统一尚未确认、后续可能扩展；仅用于提示与将来 WMS 建单。 */
    private String defaultBusinessFlow = "生产直接入库流程";

    /** 库区类型 → 金蝶仓库编码，例如 stereo=02.002、agile=02.001、scrap=02.005。 */
    private Map<String, String> stockCode = new LinkedHashMap<>();

    /**
     * 按生产订单组织覆盖仓库编码：键=金蝶组织编码（FNumber，如 100/101），值同 stock-code。
     * 仓库是组织级基础资料，同一编码在不同组织下不通用；命中优先于通用 stock-code。
     */
    private Map<String, Map<String, String>> stockCodeByOrg = new LinkedHashMap<>();

    /**
     * 仓库编码 → 仓位编号（BD_FLEXVALUES 分录 FFlexValueNumber，如 01）。
     * 仅启用仓位管理的仓库需要配置；回写时按仓库的仓位值集解析为分录内码传 FStockLocId。
     */
    private Map<String, String> stockLoc = new LinkedHashMap<>();

    /** WMS 物料编码 → 金蝶物料编码；为空时按相同编码查询。 */
    private Map<String, String> materialMap = new LinkedHashMap<>();

    /**
     * WMS 物料编码 → WMS 产品类型（半成品/成品）。
     * 金蝶 FProductType 是「主产品/联产品/副产品」，不能区分半成品/成品，产品类型必须以物料维度配置。
     */
    private Map<String, String> materialProductType = new LinkedHashMap<>();

    /** 金蝶 FProductType 枚举 → WMS 产品类型。 */
    private Map<String, String> productTypeMap = new LinkedHashMap<>();

    /** 金蝶 FDocumentStatus → WMS 工单状态（@Deprecated：BD-002 V2.2 起不再读取，状态按 gateStatus 门槛）。 */
    private Map<String, String> statusMap = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 出库（CK-001/CK-002）

    /** 生产领料单单据类型（普通生产领料 SCLLD01_SYS）。 */
    private String pickBillType;
    /** 销售订单单据类型（标准销售订单 XSDD01_SYS）。 */
    private String saleOrderBillType;
    /** 销售出库单单据类型（标准销售出库单 XSCKD01_SYS）。 */
    private String outStockBillType;
    /** 销售组织内码（FK3 组织 ID，测试环境 101=209624）。 */
    private String saleOrgId;
    /** 销售组织编码（FNumber）。 */
    private String saleOrgNumber;
    /** 默认销售员编码（BD_Saler.FNumber）。 */
    private String salerId;
    /** 结算币别编码（默认 PRE001 人民币）。 */
    private String settleCurrency;
    /** 发货部门编码（BOS_Department/BD_Department.FNumber）。 */
    private String deliveryDeptId;
    /** 仓管员编码（BD_Empinfo.FNumber）。 */
    private String stockerId;

    // ---------------------------------------------------------------- 库存调拨（CK-004）

    /** 调拨单单据类型（标准直接调拨单 ZJDB01_SYS）。 */
    private String transferBillType;
    /** 调拨类型（同组织内部调拨 InnerOrgTransfer；跨组织未实测）。 */
    private String transferBizType;
    /** 调拨方向（普通 GENERAL）。 */
    private String transferDirect;
    /** 调拨单仓管员编码（BD_WAREHOUSEWORKERS.FNumber，必须对目标组织启用）。 */
    private String transferStockerNumber;
    /** 审批超时告警阈值（小时，默认 24）；0 表示不告警。 */
    private int transferApprovalTimeoutHours = 24;

    // ---------------------------------------------------------------- 主数据同步（2026-10-26）

    /** 主数据同步配置（仓库 / 物料分类 / 工单类型）。 */
    private MasterData masterData = new MasterData();

    /** 委外订单同步配置（外协出库，spec 2026-10-26 §5.1）。 */
    private Subcontract subcontract = new Subcontract();

    /** 样品出库回写配置（spec §5.2）。 */
    private Sample sample = new Sample();

    /** 盘点差异回写配置（spec §5.3）。 */
    private Stocktake stocktake = new Stocktake();

    /** 纯不良品入库回写配置（spec 2026-10-28-yx-wms-defect-outbound-erp-design）。 */
    private Defect defect = new Defect();

    /**
     * 纯不良品 → 金蝶其他入库单（STK_Miscellaneous）。
     *
     * <p>报文为 2026-10-27 真机验证过的结构（Save+Submit 通过；Audit 挂工作流不可 WebAPI）。
     * 入库仓库复用 {@code stock-code.scrap}（含 stock-code-by-org 按组织覆盖），不另设键。
     */
    public static class Defect {
        /** 其他入库单单据类型（QTRKD01_SYS 标准 / QTRKD03_SYS 废品其他入库）。 */
        private String billType = "QTRKD01_SYS";
        /** 部门编码；留空则用工单上的车间编号。 */
        private String deptNumber;

        public String getBillType() { return billType; }
        public void setBillType(String billType) { this.billType = billType; }
        public String getDeptNumber() { return deptNumber; }
        public void setDeptNumber(String deptNumber) { this.deptNumber = deptNumber; }
    }

    /**
     * 委外订单同步配置。
     *
     * <p>{@code orgFilter} 是**原始 filter 片段**（如 {@code FPrdOrgId.FNumber='101'}）：
     * 委外订单的组织字段名在测试账套尚未 pin（外部依赖 E4），因此不做字段名硬编码；
     * 留空即拒绝同步（与仓库同步同款 fail-safe）。
     */
    @Data
    public static class Subcontract {

        /** 委外订单组织过滤片段；空 = 不同步。 */
        private String orgFilter;

        /** 金蝶 FPickMtrlStatus 表示「已领料完成」的取值；空 = 不自动作废草稿。 */
        private String pickStatusDone;
    }

    /** 样品出库回写配置。 */
    @Data
    public static class Sample {

        /** 回写用的金蝶仓库编码（测试期 YF 研发仓，生产切新仓）；空 = 回写明确报错。 */
        private String stockCode;
    }

    /**
     * 盘点差异回写配置。
     *
     * <p>注意：绑定键是 {@code wms.sync.erp.kingdee.stocktake.*}——
     * spec §7 写的 {@code wms.stocktake.*} 前缀是笔误，两者不一致会让运维白配。
     */
    @Data
    public static class Stocktake {

        /** 回写用的金蝶仓库编码；空 = 回写明确报错。 */
        private String stockCode;

        /** 回写单据形态：MIS=其他出入库单；预留 STOCK_COUNT。 */
        private String writebackMode = "MIS";
    }

    /**
     * 主数据同步配置。
     *
     * <p>与 {@code wms.kingdee.*} 总开关及鉴权 {@code isConfigured()} 取 AND：
     * 本组只控制「是否同步主数据」，不重复表达连接是否可用。
     */
    @Data
    public static class MasterData {

        /** 主数据同步总开关（默认关，联调时开）。 */
        private boolean enabled = false;

        /** BD_STOCK 组织过滤（101 宜昌永鑫；100 江西兆鑫为历史组织）。 */
        private String orgId = "101";

        /** 仓库白名单（金蝶仓库编码）；空 = 不拉任何仓。 */
        private List<String> stockWhitelist = new ArrayList<>();

        /** 金蝶仓库编码 → 既有 WMS 仓库编码（命中则只改名不改码）。 */
        private Map<String, String> stockCodeMap = new LinkedHashMap<>();

        /** 分类映射规则，格式「金蝶分组前缀=WMS 分类编码」，如 {@code BC.1012=CP}。 */
        private List<String> categoryMap = new ArrayList<>();

        /** PRD_MO 上承载工单类型的字段名（外部依赖 E1 就位后配置）；空 = 不读类型。 */
        private String orderTypeField;

        /** 金蝶字段值 → WMS 工单类型，如 {@code 客户订单=客户订单}。 */
        private Map<String, String> orderTypeValueMap = new LinkedHashMap<>();
    }
}
