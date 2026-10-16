package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 批量删除预检结果——逐条检查后的不可删明细
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "批量删除预检结果")
public class WmsBatchDeleteCheckVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否全部可删除")
    private boolean deletable = true;

    @Schema(description = "不可删除明细")
    private List<BlockedItem> blockedItems = new ArrayList<>();

    public void addBlocked(String id, String code, String reason) {
        BlockedItem item = new BlockedItem();
        item.setId(id);
        item.setCode(code);
        item.setReason(reason);
        this.blockedItems.add(item);
        this.deletable = false;
    }

    @Data
    @Schema(description = "不可删除明细项")
    public static class BlockedItem implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "记录 id")
        private String id;

        @Schema(description = "业务编码")
        private String code;

        @Schema(description = "不可删原因（多条用「；」拼接）")
        private String reason;
    }
}
