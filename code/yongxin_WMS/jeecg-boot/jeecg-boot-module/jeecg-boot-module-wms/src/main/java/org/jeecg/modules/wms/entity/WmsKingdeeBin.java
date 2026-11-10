package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.util.Date;

/**
 * @Description: 金蝶仓位属性（逻辑库位/分类）。
 *
 * <p>用户 2026-10-28 明确：金蝶仓位**不是物理货位**，而是挂在单据上的一种分类属性
 * （如「研发仓」），大部分金蝶单据仓位为空。因此本表是**属性字典**，
 * WMS 物理货位（{@code wms_location}）可按需挂一个属性，而不是由金蝶生成货位。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wms_kingdee_bin")
@Schema(description = "金蝶仓位属性")
public class WmsKingdeeBin extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "金蝶组织编码")
    private String orgNumber;

    @Schema(description = "仓位值集编码（BD_FLEXVALUES.FNumber）")
    private String flexNumber;

    @Schema(description = "仓位编码")
    private String binNumber;

    @Schema(description = "仓位名称")
    private String binName;

    @Schema(description = "金蝶仓位内码（回写 FStockLocId 直接用）")
    private Long flexId;

    @Schema(description = "同步状态：正常/金蝶已停用/待处理")
    private String syncStatus;

    @Schema(description = "最后同步时间")
    private Date lastSyncTime;

    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
