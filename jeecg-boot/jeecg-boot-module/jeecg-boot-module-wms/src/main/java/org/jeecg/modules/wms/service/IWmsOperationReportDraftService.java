package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;

/**
 * @Description: 工序汇报 → WMS 半成品入库草稿（阶段 C）。
 *
 * <p>只落草稿：**不分配库位、不下发 WCS、不回写金蝶**。
 * 等仓库/库位方案定了再切 B（归并到真实物理库区并走搬运流程）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface IWmsOperationReportDraftService {

    /**
     * 拉取金蝶工序汇报并落成 WMS 半成品入库草稿。
     *
     * @param startDate 起始日期 yyyy-MM-dd（可空）
     * @param endDate   结束日期 yyyy-MM-dd（可空）
     * @param maxRows   最多处理行数（默认 1000）
     */
    WmsOperationReportSyncResultVO ingest(String startDate, String endDate, Integer maxRows);
}
