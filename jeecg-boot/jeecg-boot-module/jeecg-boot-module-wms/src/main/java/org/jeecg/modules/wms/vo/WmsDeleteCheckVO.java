package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 删除预检结果（spec 2026-10-16 §3.1）——只读，供前端在确认框前判断
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Data
@Schema(description = "删除预检结果")
public class WmsDeleteCheckVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否可删除")
    private boolean deletable = true;

    @Schema(description = "不可删除原因列表")
    private List<Blocker> blockers = new ArrayList<>();

    /** 记录一条不可删原因，并把 deletable 置为 false。 */
    public void addBlocker(String message, long count, List<String> samples) {
        Blocker blocker = new Blocker();
        blocker.setMessage(message);
        blocker.setCount(count);
        blocker.setSamples(samples == null ? new ArrayList<>() : samples);
        this.blockers.add(blocker);
        this.deletable = false;
    }

    @Data
    @Schema(description = "不可删除原因")
    public static class Blocker implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "原因描述，如「存在关联订单」")
        private String message;

        @Schema(description = "引用数量")
        private long count;

        @Schema(description = "引用样本（业务编号，最多 5 个）")
        private List<String> samples = new ArrayList<>();
    }
}
