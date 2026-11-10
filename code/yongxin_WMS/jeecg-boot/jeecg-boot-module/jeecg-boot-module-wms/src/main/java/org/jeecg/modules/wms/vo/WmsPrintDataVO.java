package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一打印数据载荷（DY-001）：7 类单据共用一套结构，模板只认本 VO 不认单据表。
 *
 * <p>分页口径（spec §3.4）：后端按 {@link #rowsPerPage} 固定行数切段放进 {@link #pages}，
 * 每页重复表头；合计只在末页（{@link #totalsOnLastPage}）；末页与单页补空行到 rowsPerPage。
 * 「第x页，共y页」由前端按 {@link #totalPages} 生成，后端不放页码。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Data
@Accessors(chain = true)
@Schema(description = "打印数据载荷")
public class WmsPrintDataVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 打印单据类型码（WmsConstants.PRINT_BILL_*）。 */
    private String billType;

    /** 单据标题（如「其他出库单」）。 */
    private String title;

    /** 金蝶单号（打印编号）。 */
    private String erpBillNo;

    /** 单头字段：标签 → 值（如 部门/日期/领用类型）。 */
    private Map<String, String> headers = new LinkedHashMap<>();

    /** 表格列标题（含序号列时由组装器决定是否加）。 */
    private List<String> columns = new ArrayList<>();

    /** 分页后的行数据：页 → 行 → 列（已补空行到 rowsPerPage）。 */
    private List<List<List<String>>> pages = new ArrayList<>();

    /** 每页固定行数（样单版式，默认 12）。 */
    private Integer rowsPerPage;

    /** 总页数（前端据此生成「第x页，共y页」）。 */
    private Integer totalPages;

    /** 合计行（只在末页渲染）：列名 → 合计值。 */
    private Map<String, String> totalsOnLastPage = new LinkedHashMap<>();

    /** 签名/落款行（如 审核：xx、保管员：xx）。 */
    private List<String> footers = new ArrayList<>();

    /** 版式附加项：联次行、公司信息块、logo、warn 等按模板取用。 */
    private Map<String, Object> extras = new LinkedHashMap<>();

    /** 快照时间（金蝶不可达回退时非空，前端据此标注数据时间）。 */
    private Date snapshotTime;
}
