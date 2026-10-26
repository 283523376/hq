package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.util.Date;

/**
 * @Description: 盘点计划（定期 cron / 周期滚动）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "盘点计划")
@TableName("wms_stocktake_plan")
public class WmsStocktakePlan extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "计划名称")
    private String planName;

    @Schema(description = "计划类型：定期/周期")
    private String planType;

    @Schema(description = "定期计划 cron")
    private String cronExpr;

    @Schema(description = "盘点仓库ID（空=全部）")
    private String warehouseId;

    @Schema(description = "周期计划每期圈定库位数")
    private Integer rollingSize;

    @Schema(description = "上次执行时间")
    private Date lastRunTime;

    @Schema(description = "是否启用：1是 0否")
    private String enabled;

    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳（0=未删除）")
    private Long deletedAt;
}
