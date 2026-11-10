package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 冲突响应载荷（放在 {@code Result.error(固定文案, 本对象)} 的 result 里）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "配置冲突响应")
public class WmsConfigConflictVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "冲突明细")
    private List<WmsConfigConflict> conflicts = new ArrayList<>();

    public WmsConfigConflictVO() {
    }

    public WmsConfigConflictVO(List<WmsConfigConflict> conflicts) {
        this.conflicts = conflicts == null ? new ArrayList<>() : conflicts;
    }
}
