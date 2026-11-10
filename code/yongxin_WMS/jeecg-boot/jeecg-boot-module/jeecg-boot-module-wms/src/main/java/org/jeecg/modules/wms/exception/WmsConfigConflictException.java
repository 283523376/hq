package org.jeecg.modules.wms.exception;

import org.jeecg.modules.wms.vo.WmsConfigConflict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置冲突异常。
 *
 * <p>message **逐字固定**为蓝图原文「配置项存在冲突，请检查」——验收按逐字比对，
 * 因此具体原因不拼进 message，而是由 {@link #getConflicts()} 随响应返回。
 * Controller 捕获后构造 {@code Result.error(getMessage(), 明细)}。
 *
 * @author 永鑫WMS
 */
public class WmsConfigConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 蓝图 YX-WMS-BD-004 异常处理规定的原文，不得改动。 */
    public static final String MESSAGE = "配置项存在冲突，请检查";

    private final transient List<WmsConfigConflict> conflicts;

    public WmsConfigConflictException(List<WmsConfigConflict> conflicts) {
        super(MESSAGE);
        this.conflicts = conflicts == null ? new ArrayList<>() : new ArrayList<>(conflicts);
    }

    public List<WmsConfigConflict> getConflicts() {
        return Collections.unmodifiableList(conflicts);
    }
}
