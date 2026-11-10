package org.jeecg.modules.wms.sync;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * @Description: 金蝶一次 WebAPI 调用的结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Getter
public class WmsKingdeeResult {

    private final boolean success;
    private final String message;
    private final String raw;
    /** ExecuteBillQuery 的行数据；其他接口为空列表。 */
    private final List<List<Object>> rows;
    /** Save 返回的单据编号。 */
    private final String number;
    /** Save 返回的单据 Id。 */
    private final String id;

    private WmsKingdeeResult(boolean success, String message, String raw,
                             List<List<Object>> rows, String number, String id) {
        this.success = success;
        this.message = message;
        this.raw = raw;
        this.rows = rows == null ? Collections.emptyList() : rows;
        this.number = number;
        this.id = id;
    }

    public static WmsKingdeeResult ok(String raw, List<List<Object>> rows, String number, String id) {
        return new WmsKingdeeResult(true, "success", raw, rows, number, id);
    }

    public static WmsKingdeeResult fail(String message) {
        return new WmsKingdeeResult(false, message, null, null, null, null);
    }

    public static WmsKingdeeResult fail(String message, String raw) {
        return new WmsKingdeeResult(false, message, raw, null, null, null);
    }
}
