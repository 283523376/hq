package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.util.Date;

/**
 * @Description: 导出任务（KC-004）。
 *
 * <p>**没有 deleted_at**：任务是短生命周期的工作产物，7 天后连记录带文件物理删除，
 * 留一堆逻辑删除的失效行只是噪音。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "导出任务")
@TableName("wms_export_task")
public class WmsExportTask extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 任务类型，见 {@code WmsConstants.EXPORT_TASK_TYPE_*}。 */
    @Schema(description = "任务类型")
    private String taskType;

    /** 查询条件 JSON 快照，形如 {@code {"v":1,"q":{...}}}，带格式版本便于将来兼容旧任务。 */
    @Schema(description = "查询条件JSON快照")
    private String queryParams;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED。 */
    @Schema(description = "状态")
    private String status;

    /** 下载文件名（用户可见）。 */
    @Schema(description = "下载文件名")
    private String fileName;

    /** 相对导出根目录的路径。 */
    @Schema(description = "相对文件路径")
    private String filePath;

    @Schema(description = "文件字节数")
    private Long fileSize;

    @Schema(description = "导出数据行数")
    private Long rowCount;

    /** 一致性快照时点（生成事务开始时间）。 */
    @Schema(description = "快照时点")
    private Date snapshotTime;

    @Schema(description = "完成时间")
    private Date finishTime;

    /** 清理时点：任何终态完成时写 finish_time + 保留天数。 */
    @Schema(description = "清理时点")
    private Date expireTime;

    @Schema(description = "失败原因")
    private String errorMsg;
}
