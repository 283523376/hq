package org.jeecg.modules.wms.sync;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 金蝶单据打印只读 Reader（DY-001）：按打印单据类型查**已审核**金蝶单据。
 *
 * <p>只读——本接口禁止任何 Save/Submit/Push/Delete；失败抛 {@link RuntimeException}
 * 供上层回退快照，查无（含未审核被过滤）返回 empty 由上层给合并文案。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
public interface IWmsKingdeePrintReader {

    /** 金蝶同步是否启用（未启用时上层走快照或显式报错）。 */
    boolean isEnabled();

    /**
     * 查已审核单据。
     *
     * @param billType 打印单据类型码（WmsConstants.PRINT_BILL_*）
     * @param erpBillNo 金蝶单号（FBillNo）
     * @return 命中返回聚合单；查无返回 empty；金蝶调用失败抛 RuntimeException
     */
    Optional<WmsKingdeePrintBill> queryApproved(String billType, String erpBillNo);

    /**
     * 已审核单据列表（打印工作台默认列表 + 单号模糊查询）。
     *
     * @param billType 打印单据类型码
     * @param keyword  单号模糊关键词（空则不筛选）
     * @param startRow 起始行（0 起）
     * @param limit    本页条数（实现内部多取 1 条判断是否还有下一页）
     * @return 头字段行（FBillNo/FDate），最多 limit 条
     * @throws RuntimeException 金蝶调用失败
     */
    List<Map<String, String>> queryApprovedPage(String billType, String keyword,
                                                          int startRow, int limit);
}
