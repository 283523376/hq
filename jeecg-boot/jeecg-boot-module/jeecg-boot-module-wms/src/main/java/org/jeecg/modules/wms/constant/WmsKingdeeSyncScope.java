package org.jeecg.modules.wms.constant;

import java.util.ArrayList;
import java.util.List;

/**
 * 上位系统（金蝶云星空）同步范围登记表 —— **扩展点**。
 *
 * <p>「上位系统同步范围」（配置项 {@code sync.scope}）的候选值**只从这张表生成**：
 * 新增/下线一个同步范围 = 改这一个枚举，前端下拉自动跟着变，不需要改前端代码。
 *
 * <p>每条记录都绑定了真实的金蝶单据（{@link #getKingdeeForm()}），
 * 前端显示的就是「这个范围同步的是哪张单据」，避免配置项与实际行为两张皮。
 *
 * <p>现状（2026-10-27）：该配置目前**只用于展示与对账，尚未参与运行时开关判断**——
 * 各同步链路是否执行由各自的 {@code wms.sync.erp.kingdee.*} 开关与调用点决定。
 * 将来要按配置真正拦截某个范围时，拿 {@link #getCode()} 做判断即可：
 * 例如「销售出库」被取消勾选就跳过 {@code SAL_OUTSTOCK} 回写。
 *
 * @author 永鑫WMS
 * @since 2026-10-27
 */
public enum WmsKingdeeSyncScope {

    // ---------------------------------------------------------------- WMS → 金蝶（单据回写）
    PRODUCTION_INBOUND("production-inbound", "生产入库回写（PRD_INSTOCK）", "PRD_INSTOCK",
            Direction.UP, Status.ENABLED),
    PICK_OUTBOUND("pick-outbound", "生产领料出库回写（PRD_PickMtrl）", "PRD_PickMtrl",
            Direction.UP, Status.ENABLED),
    SALES_OUTBOUND("sales-outbound", "销售出库回写（SAL_OUTSTOCK）", "SAL_OUTSTOCK",
            Direction.UP, Status.ENABLED),
    STOCKTAKE_ADJUST("stocktake-adjust", "盘点差异回写（QTRK/QTCK）",
            "STK_Miscellaneous / STK_MisDelivery", Direction.UP, Status.ENABLED),
    DEFECT_INBOUND("defect-inbound", "纯不良品入库回写（QTRK）", "STK_Miscellaneous",
            Direction.UP, Status.ENABLED),
    SAMPLE_OUTBOUND("sample-outbound", "样品出库回写（单据形态待定）", "待客户确认",
            Direction.UP, Status.PENDING),
    TRANSFER("transfer", "调拨单回写（STK_TransferDirect，暂不启用）", "STK_TransferDirect",
            Direction.UP, Status.SUSPENDED),
    SUBCONTRACT_PICK("subcontract-pick", "委外领料/退料回写（未实现）",
            "SUB_PickMtrl / SUB_ReturnMtrl", Direction.UP, Status.NOT_IMPLEMENTED),
    // V2.4 新增：单据标识来自配置（kingdee.form.*），控制台确认前为 PENDING。
    // 代码骨架已就位，但 formId 未确认 → 不进「默认同步范围」，避免默认勾选一个必然失败的链路。
    CONSIGNED_INBOUND("consigned-inbound", "受托加工材料入库单回写（formId 待确认）",
            "待控制台确认（kingdee.form.consigned-inbound）", Direction.UP, Status.PENDING),
    PURCHASE_INBOUND("purchase-inbound", "采购收料单回写（段差棒直入，formId 待配置）",
            "PUR_ReceiveBill（kingdee.form.purchase-inbound）", Direction.UP, Status.PENDING),
    CONSIGNED_RETURN("consigned-return", "受托加工材料退料单回写（受托不良退货）",
            "STK_OEMInStockRETURN", Direction.UP, Status.PENDING),

    // ---------------------------------------------------------------- 金蝶 → WMS（读取主数据/单据）
    WORK_ORDER("work-order", "生产工单同步（PRD_MO）", "PRD_MO",
            Direction.DOWN, Status.ENABLED),
    WAREHOUSE("warehouse", "仓库档案同步（BD_STOCK）", "BD_STOCK",
            Direction.DOWN, Status.ENABLED),
    // 2026-10-28 修正：物料同步服务尚未实现（此前误标 ENABLED），物料同步落地时翻回
    MATERIAL("material", "物料与分类同步（BD_MATERIAL）", "BD_MATERIAL",
            Direction.DOWN, Status.NOT_IMPLEMENTED),
    SUBCONTRACT_ORDER("subcontract-order", "委外订单同步（SUB_SUBREQORDER）", "SUB_SUBREQORDER",
            Direction.DOWN, Status.ENABLED),
    SALES_ORDER("sales-order", "销售订单同步（SAL_SaleOrder）", "SAL_SaleOrder",
            Direction.DOWN, Status.ENABLED);

    /** 同步方向。 */
    public enum Direction {
        /** WMS 回写金蝶。 */
        UP("回写"),
        /** 从金蝶读取。 */
        DOWN("读取");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 上线状态：只有 ENABLED 的才会进「默认同步范围」。 */
    public enum Status {
        /** 已启用且可用。 */
        ENABLED("已启用"),
        /** 口径未定，等客户/外部依赖。 */
        PENDING("待确认"),
        /** 代码已就绪但业务上暂不启用。 */
        SUSPENDED("暂不启用"),
        /** 有接口定义但写入器未实现。 */
        NOT_IMPLEMENTED("未实现");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String code;
    private final String optionLabel;
    private final String kingdeeForm;
    private final Direction direction;
    private final Status status;

    WmsKingdeeSyncScope(String code, String optionLabel, String kingdeeForm,
                        Direction direction, Status status) {
        this.code = code;
        this.optionLabel = optionLabel;
        this.kingdeeForm = kingdeeForm;
        this.direction = direction;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public String getOptionLabel() {
        return optionLabel;
    }

    public String getKingdeeForm() {
        return kingdeeForm;
    }

    public Direction getDirection() {
        return direction;
    }

    public Status getStatus() {
        return status;
    }

    /** 配置项「同步范围」的候选值（顺序即枚举声明顺序：先回写、后读取）。 */
    public static List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (WmsKingdeeSyncScope scope : values()) {
            labels.add(scope.optionLabel);
        }
        return labels;
    }

    /** 默认勾选的同步范围：只包含当前**已启用**的链路。 */
    public static String defaultValue() {
        List<String> enabled = new ArrayList<>();
        for (WmsKingdeeSyncScope scope : values()) {
            if (scope.status == Status.ENABLED) {
                enabled.add(scope.optionLabel);
            }
        }
        return String.join(",", enabled);
    }

    /** 按 code 查（给将来「按配置拦截」用）；找不到返回 null，不抛异常。 */
    public static WmsKingdeeSyncScope ofCode(String code) {
        for (WmsKingdeeSyncScope scope : values()) {
            if (scope.code.equals(code)) {
                return scope;
            }
        }
        return null;
    }
}
