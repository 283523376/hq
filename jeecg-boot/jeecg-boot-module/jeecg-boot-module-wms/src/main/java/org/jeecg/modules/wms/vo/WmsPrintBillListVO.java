package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 打印工作台单据列表（DY-001）：默认列出该类型全部可打印单据。
 *
 * <p>本地类型（盘盈亏/销售出库）给精确 {@link #total}；金蝶类型只能靠 {@link #hasMore}
 * 判断（ExecuteBillQuery 不返回总数），前端据此显示「上一页/下一页」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Data
@Accessors(chain = true)
@Schema(description = "打印工作台单据列表")
public class WmsPrintBillListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单据项：billNo 单号 / label 展示名 / date 日期 / status 状态 / printable 是否可打印。 */
    private List<Map<String, String>> records = new ArrayList<>();

    /** 总条数；金蝶类型未知时为 -1。 */
    private Long total;

    private Integer pageNo;

    private Integer pageSize;

    /** 是否还有下一页（金蝶类型分页依据）。 */
    private Boolean hasMore;
}
