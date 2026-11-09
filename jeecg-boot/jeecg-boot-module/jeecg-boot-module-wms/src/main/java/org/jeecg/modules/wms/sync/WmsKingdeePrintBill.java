package org.jeecg.modules.wms.sync;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 金蝶单据打印聚合载体（DY-001）：一张单 = 头字段（取首行）+ 分录行（键=字段键原名）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Data
@Accessors(chain = true)
public class WmsKingdeePrintBill {

    /** 打印单据类型码（WmsConstants.PRINT_BILL_*）。 */
    private String billType;

    /** 金蝶 formId。 */
    private String formId;

    /** 金蝶单号（FBillNo）。 */
    private String billNo;

    /** 分录行：一行一 Map，键=FIELD_KEYS 原名（头字段在每行重复出现，组装器取首行当头）。 */
    private List<Map<String, String>> rows;
}
