package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 库存预警单（KC-001）。
 *
 * <p>合并提醒口径（spec 2026-10-28 D6）：同 {@code dedupKey} 且 {@code PENDING} 只保留一条，
 * 再次命中只累加 {@code hitCount} 并刷新 {@code currentQty}；解除后 {@code dedupKey}
 * 追加 {@code #R#时间戳}，不再参与合并。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库存预警单")
@TableName("wms_inventory_warning")
public class WmsInventoryWarning extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "预警单号")
    private String warningNo;

    @Schema(description = "规则ID")
    private String ruleId;

    @Schema(description = "规则名称快照")
    private String ruleName;

    @Schema(description = "规则类型快照")
    private String ruleType;

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "物料编码快照")
    private String materialCode;

    @Schema(description = "物料名称快照")
    private String materialName;

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "库位ID（超期预警）")
    private String locationId;

    @Schema(description = "库位编码快照")
    private String locationCode;

    @Schema(description = "批次号（超期预警）")
    private String batchNo;

    @Schema(description = "容器/料箱编码")
    private String containerCode;

    @Schema(description = "触发时数量：下限=可用量，超期=库存量")
    private BigDecimal currentQty;

    @Schema(description = "阈值快照")
    private BigDecimal thresholdQty;

    @Schema(description = "超期天数快照")
    private Integer overdueDays;

    @Schema(description = "首次入库时间快照")
    private Date firstInboundTime;

    @Schema(description = "状态：PENDING 待处理 / RESOLVED 已解除")
    private String status;

    @Schema(description = "合并去重键；解除后追加 #R#时间戳")
    private String dedupKey;

    @Schema(description = "命中次数")
    private Integer hitCount;

    @Schema(description = "最近命中时间")
    private Date lastHitTime;

    @Schema(description = "处理方式：REPLENISH 补料 / CLEAR 清理 / OTHER 其他")
    private String handleType;

    @Schema(description = "处理说明")
    private String handleRemark;

    @Schema(description = "处理人")
    private String handledBy;

    @Schema(description = "处理时间")
    private Date handleTime;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
