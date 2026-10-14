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

    /** 删除状态：0正常 1已删除 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}
