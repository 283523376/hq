package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;

/**
 * 单据打印快照（DY-001）：金蝶来源单据的离线打印底账。
 *
 * <p>查询成功即 upsert 刷新；金蝶不可达时回退快照打印并在预览页标注数据时间。
 * 不做逻辑删除（快照按 uk 覆盖刷新，无终态机）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "单据打印快照")
@TableName("wms_print_document")
public class WmsPrintDocument extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 打印单据类型码（WmsConstants.PRINT_BILL_*）。 */
    @Schema(description = "打印单据类型码")
    private String billType;

    /** 金蝶单号（如 QTCK10126110002）。 */
    @Schema(description = "金蝶单号")
    private String erpBillNo;

    /** 查询原文快照（金蝶分录行 JSON，List&lt;Map&gt;）。 */
    @Schema(description = "查询原文快照")
    private String payload;

    /** 快照时间（每次刷新覆盖）。 */
    @Schema(description = "快照时间")
    private java.util.Date snapshotTime;
}
