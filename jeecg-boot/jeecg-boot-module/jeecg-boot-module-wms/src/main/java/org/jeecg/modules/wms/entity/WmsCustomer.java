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
 * @Description: 客户档案（受托方口径，V2.4）
 *
 * <p><b>为什么单独建表而不复用供应商</b>：金蝶里受托方是「客户」（BD_Customer），
 * 受托加工材料入库单头字段为 {@code FCustId}；WMS 若继续把受托方塞进供应商档案，
 * 回写时就要在两张主数据之间做映射，客户编码一旦与供应商编码不同必然错绑。
 * 2026-11-04 用户拍板：受托方与金蝶保持一致，统一引用客户档案。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "客户档案（受托方）")
@TableName("wms_customer")
public class WmsCustomer extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 客户编码（与金蝶 BD_Customer.FNumber 一致，回写受托入库单的 FCustId） */
    @Excel(name = "客户编码", width = 20)
    @Schema(description = "客户编码")
    private String customerCode;

    /** 客户名称 */
    @Excel(name = "客户名称", width = 20)
    @Schema(description = "客户名称")
    private String customerName;

    /** 联系人 */
    @Excel(name = "联系人", width = 15)
    @Schema(description = "联系人")
    private String contact;

    /** 联系电话 */
    @Excel(name = "联系电话", width = 15)
    @Schema(description = "联系电话")
    private String phone;

    /** 状态：1启用 0停用 */
    @Excel(name = "状态", width = 10, replace = {"启用_1", "停用_0"})
    @Schema(description = "状态：1启用 0停用")
    private String status;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p>与 {@link WmsSupplier#deletedAt} 同一方案：唯一索引
     * {@code uk_wms_customer_code} 把删除标记一起纳入，且用时间戳而非固定值，
     * 保证「删除 → 重建 → 再删除」可无限次进行。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
