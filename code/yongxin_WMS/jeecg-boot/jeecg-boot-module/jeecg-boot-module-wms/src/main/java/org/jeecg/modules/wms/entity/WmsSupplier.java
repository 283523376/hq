package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "供应商")
@TableName("wms_supplier")
public class WmsSupplier extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 供应商编码 */
    @Excel(name = "供应商编码", width = 20)
    @Schema(description = "供应商编码")
    private String supplierCode;

    /** 供应商名称 */
    @Excel(name = "供应商名称", width = 20)
    @Schema(description = "供应商名称")
    private String supplierName;

    /** 联系人 */
    @Excel(name = "联系人", width = 15)
    @Schema(description = "联系人")
    private String contact;

    /** 联系电话 */
    @Excel(name = "联系电话", width = 15)
    @Schema(description = "联系电话")
    private String phone;

    /** 供货范围 */
    @Excel(name = "供货范围", width = 20)
    @Schema(description = "供货范围")
    private String supplyScope;

    /** 状态：1启用 0停用 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p><b>为什么不是 0/1 的 del_flag</b>：{@code uk_wms_supplier_code} 必须把删除标记
     * 一起纳入唯一索引，否则被删除的供应商编码会**永久占用**它 —— 表现为
     * 「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」。
     * 用**时间戳**而非固定值（如 1），是因为固定值会让同一编码**只能删一次**；
     * 时间戳每次不同，「删除 → 重建 → 再删除」可无限次进行。
     *
     * <p>索引已相应改为 {@code (supplier_code, deleted_at)}；查询过滤由
     * {@code @TableLogic} 自动追加 {@code deleted_at = 0}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
