package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsPrintBillListVO;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;

import java.util.List;
import java.util.Map;

/**
 * 单据打印编排（DY-001）：入口解析 → 金蝶只读查询/快照回退 → 统一打印载荷。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
public interface IWmsPrintService {

    /**
     * 组装打印数据。
     *
     * @param billType 打印单据类型码（WmsConstants.PRINT_BILL_*）
     * @param billNo   本地单号（PD…/XS…）或金蝶单号（QTRK/QTCK/XSCK/XSTHD/WMLL…）
     * @return 统一打印载荷（分页已切好）
     * @throws RuntimeException 类型/单号非法、未回写、单据不存在或未审核、金蝶不可达且无快照
     */
    WmsPrintDataVO printData(String billType, String billNo);

    /**
     * 本地下拉（打印工作台的单号选择器）。
     *
     * @return 每项 {billNo, label, printable}；该类型无本地单据时返回空列表
     */
    List<Map<String, String>> localBills(String billType, String keyword);

    /**
     * 工作台单据列表：默认列出该类型全部可打印单据，单号模糊搜索，每页默认 20 条。
     *
     * @param billType 打印单据类型码
     * @param keyword  单号模糊关键词（本地类型匹配本地单号，金蝶类型匹配金蝶单号）
     * @param pageNo   页码（1 起）
     * @param pageSize 每页条数（默认 20，上限 100）
     */
    WmsPrintBillListVO bills(String billType, String keyword, int pageNo, int pageSize);
}
