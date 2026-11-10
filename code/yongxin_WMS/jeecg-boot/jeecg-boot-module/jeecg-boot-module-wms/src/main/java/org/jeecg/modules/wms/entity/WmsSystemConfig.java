package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.io.Serializable;

/**
 * @Description: 系统配置（蓝图 YX-WMS-BD-004）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@TableName("wms_system_config")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@Schema(description = "系统配置")
public class WmsSystemConfig extends JeecgEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置键")
    private String configKey;

    @Schema(description = "配置值（统一按字符串存）")
    private String configValue;

    @Schema(description = "配置说明")
    private String configDesc;

    /**
     * 删除时间戳（epoch 毫秒）：0 = 未删除。
     *
     * <p>与本模块其余表同形；唯一索引为 {@code (config_key, deleted_at)}，
     * 过滤由 {@code @TableLogic} 自动追加 {@code deleted_at = 0}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}
