package org.jeecg.modules.wms.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动分配结果出参（部分分配生效 + 告警与未分配明细，spec 口径 9）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
public class WmsAllocateResultVO {

    /** 成功分配的明细行数 */
    private int allocatedCount;

    /** 告警（如「库位不足，请人工介入」） */
    private List<String> warnings = new ArrayList<>();

    /** 未分配明细 */
    private List<Unallocated> unallocated = new ArrayList<>();

    /** 未分配明细行 */
    @Data
    public static class Unallocated {
        /** 明细ID */
        private String detailId;
        /** 物料名称 */
        private String materialName;
        /** 未分配原因 */
        private String reason;
    }
}
