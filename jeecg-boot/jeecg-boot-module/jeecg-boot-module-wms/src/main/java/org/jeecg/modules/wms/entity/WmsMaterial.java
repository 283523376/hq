package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * @Description: 物料主数据
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "物料主数据")
@TableName("wms_material")
public class WmsMaterial extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 物料编码
     */
    @Excel(name = "物料编码", width = 20)
    @Schema(description = "物料编码")
    private String materialCode;

    /**
     * 物料名称
     */
    @Excel(name = "物料名称", width = 20)
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 分类ID
     */
    @Schema(description = "分类ID")
    private String categoryId;

    /**
     * 分类编码（非持久化）：导入时按此列解析 categoryId，导出时输出
     */
    @TableField(exist = false)
    @Excel(name = "分类编码", width = 20)
    @Schema(description = "分类编码")
    private String categoryCode;

    /**
     * 分类名称（非持久化）：列表展示用，查询后回填
     */
    @TableField(exist = false)
    @Schema(description = "分类名称")
    private String categoryName;

    /**
     * 规格
     */
    @Excel(name = "规格", width = 20)
    @Schema(description = "规格")
    private String spec;

    /**
     * 单位
     */
    @Excel(name = "单位", width = 10)
    @Schema(description = "单位")
    private String unit;

    /**
     * 保质期（到期日）
     */
    @Excel(name = "保质期(到期日)", width = 15, format = "yyyy-MM-dd")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "保质期（到期日）")
    private Date shelfLife;

    /**
     * 状态：1启用 0停用
     */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不是 0/1 的 del_flag</b>：{@code uk_wms_material_code} 必须把删除标记
     * 一起纳入唯一索引，否则被删除的编码会**永久占用**它 —— 表现为
     * 「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」。
     * 用**时间戳**而不是固定值（如 1）是因为固定值会让同一编码**只能删一次**
     * （第二次删除的行与第一次删标记相同，仍撞索引）；时间戳每次不同，
     * 于是「删除 → 重建 → 再删除」可无限次进行，且保留了可恢复/可审计特性。
     *
     * <p>索引已相应改为 {@code (material_code, deleted_at)}；查询过滤由
     * {@code @TableLogic} 自动追加 {@code deleted_at = 0}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
