package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 影响面清单（XT-002）——「这张单在 WMS 碰过什么」。
 *
 * <p>**基于当前库实时查询，不存单据快照**（用户 2026-11-02 拍板）。
 * 单据若已被删除就查不到，此时必须由 {@link #gaps} 明说，不能让页面静默空白。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Accessors(chain = true)
@Schema(description = "影响面清单")
public class WmsImpactVO {

    @Schema(description = "工单本身；WMS 没有这张单时为 null")
    private WmsWorkOrder workOrder;

    @Schema(description = "该工单产生的入库单")
    private List<WmsProductionInbound> productionInbounds = new ArrayList<>();

    @Schema(description = "该工单产生的出库/领料单（含明细）")
    private List<Outbound> outbounds = new ArrayList<>();

    @Schema(description = "受影响的库存行（当前库实时查询）")
    private List<WmsInventoryLedgerVO> inventoryRows = new ArrayList<>();

    @Schema(description = "已回写 ERP 的单据")
    private List<ErpBill> erpBills = new ArrayList<>();

    @Schema(description = "变更时间线（来自审计日志）")
    private List<WmsAuditLog> timeline = new ArrayList<>();

    @Schema(description = "查不到的部分及原因；为空表示没有缺口")
    private List<String> gaps = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    @Schema(description = "出库单及其明细")
    public static class Outbound {
        private WmsOutboundOrder order;
        private List<WmsOutboundDetail> details = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "回写过 ERP 的单据")
    public static class ErpBill {
        private String billNo;
        /** 单据用途，如「生产入库」「生产领料」「销售出库」。 */
        private String type;
        private Date time;
        /** 来源 WMS 单号。 */
        private String sourceNo;
    }
}
