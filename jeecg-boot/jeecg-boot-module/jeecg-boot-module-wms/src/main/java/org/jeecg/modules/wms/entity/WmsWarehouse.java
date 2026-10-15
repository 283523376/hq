package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "库区")
@TableName("wms_warehouse")
public class WmsWarehouse extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 库区编码，如 LB / MJ / XB。 */
    @Excel(name = "库区编码", width = 20)
    @Schema(description = "库区编码")
    private String warehouseCode;

    /** 库区名称。 */
    @Excel(name = "库区名称", width = 20)
    @Schema(description = "库区名称")
    private String warehouseName;

    /** 库区类型：立库 / 敏捷仓 / 线边柜。 */
    @Excel(name = "库区类型", width = 15)
    @Schema(description = "库区类型：立库/敏捷仓/线边柜")
    private String warehouseType;

    /**
     * 生成上限-排数。
     *
     * <p><b>只用于限制批量生成能开到哪</b>，不参与库位编码计算、也不约束任何已有数据，
     * 故可随时自由增减。
     */
    @Schema(description = "生成上限-排数")
    private Integer rowCount;

    /** 生成上限-列数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-列数")
    private Integer colCount;

    /** 生成上限-层数，含义同 {@link #rowCount}。 */
    @Schema(description = "生成上限-层数")
    private Integer layerCount;

    /** 状态：1启用 0停用。 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 位于当前维度之外的库位数（非持久化，由控制器回填）。
     *
     * <p>缩维后已有库位可能落在新声明的包络之外。系统**不拦截**这种不一致，
     * 而是把它显示出来，由运维决定是补回维度还是删库位。
     */
    @TableField(exist = false)
    @Schema(description = "位于当前维度之外的库位数")
    private Long outOfEnvelopeCount;

    /**
     * 删除状态。**始终为 0** —— 本表刻意<b>不用逻辑删除</b>。
     *
     * <p>原因：{@code uk_wms_warehouse_code} 只建在 {@code warehouse_code} 上、不含
     * {@code del_flag}，而 {@code @TableLogic} 会让唯一性校验自动加 {@code del_flag=0}
     * 从而看不见被逻辑删除的行 —— 于是「查重说可用、保存撞唯一索引」，
     * 用户看到的是原始 SQL 异常，且被删除的编码**永久不可复用**。
     *
     * <p>保留该列只为与其它 {@code wms_} 表结构对齐；删除是物理删除。
     * 安全由删除守卫承担（有库位/有设备不许删库区），留痕由 {@code @AutoLog} 操作日志承担。
     */
    @Schema(description = "删除状态，恒为 0（本表不使用逻辑删除）")
    private Integer delFlag;
}