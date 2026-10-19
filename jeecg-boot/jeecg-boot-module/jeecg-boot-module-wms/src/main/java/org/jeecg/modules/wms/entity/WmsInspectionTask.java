package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * 来料检验任务（整单级：一张入库单一个任务）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "来料检验任务")
@TableName("wms_inspection_task")
public class WmsInspectionTask extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 检验任务号（JY 前缀） */
    @Schema(description = "检验任务号")
    private String taskNo;

    /** 入库单ID（整单一个任务） */
    @Schema(description = "入库单ID")
    private String inboundId;

    /** 检验状态：待检验/合格/不合格 */
    @Schema(description = "检验状态")
    private String inspectStatus;

    /** 检验结果说明（不合格时必填原因） */
    @Schema(description = "检验结果说明")
    private String resultRemark;

    /** 检验员 */
    @Schema(description = "检验员")
    private String inspector;

    /** 检验时间 */
    @Schema(description = "检验时间")
    private java.util.Date inspectTime;

    /** 删除时间戳（epoch 毫秒）：0=未删除。 */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
