package org.jeecg.modules.wms.constant;

/**
 * 配置值类型：决定如何解析、如何渲染、如何校验。
 *
 * @author 永鑫WMS
 */
public enum WmsConfigType {
    /** 整数（可含负号，具体值域由校验器按项判定）。 */
    INT,
    /** 布尔，取值恰为 {@code true} / {@code false}。 */
    BOOL,
    /** 自由文本。 */
    TEXT,
    /** 多选，逗号分隔，取值须属于声明的候选集。 */
    MULTI,
    /** 单选，取值须属于候选集；候选集由服务层动态提供（如库区表），不是枚举里的静态 options。 */
    SELECT
}
