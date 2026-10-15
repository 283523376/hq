package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.math.BigDecimal;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库位")
@TableName("wms_location")
public class WmsLocation extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库位编码，如 LB-R01-C06-L24。全局唯一。 */
    @Excel(name = "库位编码", width = 24)
    @Schema(description = "库位编码")
    private String locationCode;

    /** 所属库区ID。 */
    @Schema(description = "所属库区ID")
    private String warehouseId;

    /** 排。 */
    @Excel(name = "排", width = 8)
    @Schema(description = "排")
    private Integer rowNo;

    /** 列。 */
    @Excel(name = "列", width = 8)
    @Schema(description = "列")
    private Integer colNo;

    /** 层。 */
    @Excel(name = "层", width = 8)
    @Schema(description = "层")
    private Integer layerNo;

    /** 承重（kg）。 */
    @Excel(name = "承重", width = 12)
    @Schema(description = "承重")
    private BigDecimal maxWeight;

    /** 状态：空闲 / 占用 / 故障。{@code 占用} 视为库位内有物料，禁止修改属性。 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：空闲/占用/故障")
    private String status;

    /** 所属库区名称（非持久化，列表展示用，交给 QueryGenerator 前必须先置空）。 */
    @TableField(exist = false)
    @Schema(description = "所属库区名称")
    private String warehouseName;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不复用 0/1 的 del_flag</b>：两个唯一索引都必须把删除标记一起纳入 ——
     * {@code uk_wms_location_code(location_code, deleted_at)} 与
     * {@code uk_wms_location_grid(warehouse_id, row_no, col_no, layer_no, deleted_at)}。
     * 否则被删除的库位会**永久占用**编码与网格位：删掉几个库位后重跑批量生成，
     * 那几行会被 {@code INSERT IGNORE} <b>静默跳过</b>（计入 skipped）却**不会被恢复**，
     * 列表里也看不见 —— 直接破坏「重跑同范围可安全续做」这条承诺。
     *
     * <p>用**时间戳**而非固定值（如 1），是因为固定值会让同一网格位**只能删一次**；
     * 时间戳每次不同，「删除 → 重建 → 再删除」可无限次进行，且保留可恢复/可审计。
     *
     * <p>本表原先是「物理删除」，于 V3.9.5_9 并入统一方案。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}