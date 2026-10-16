package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

import java.io.Serializable;

/**
 * @Description: 系统配置版本历史（只追加的审计流水）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 *
 * <p><b>无 {@code @TableLogic}</b>：本表只追加、永不删除（回滚也是追加一行新记录），
 * 加逻辑删除会让审计链出现「被删掉的版本」，与「留痕」的诉求相反。
 *
 * <p>继承 {@code JeecgEntity} 是为了复用 {@code WmsUtils.fillCreate}（其泛型约束为
 * {@code T extends JeecgEntity}）；因此建表脚本也带了 {@code update_by/update_time} 两列，
 * 虽然本表从不写它们。
 */
@Data
@TableName("wms_system_config_history")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@Schema(description = "系统配置版本历史")
public class WmsSystemConfigHistory extends JeecgEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置键")
    private String configKey;

    @Schema(description = "该版本的配置值")
    private String configValue;

    @Schema(description = "该配置键的版本号，从 1 递增")
    private Integer versionNo;

    @Schema(description = "变更类型：SAVE=保存 ROLLBACK=回滚")
    private String changeType;
}
