package org.jeecg.modules.wms.util;

/**
 * 库位编码生成器。
 *
 * <p><b>纯函数</b>：不依赖 Spring、不依赖库区配置、不做任何 IO，因此可被穷举测试。
 *
 * <p><b>规则</b>：{@code {库区编码}-R{排}-C{列}-L{层}}，每段补零到
 * {@code max(2, 该数字自身的位数)}，形如 {@code LB-R01-C06-L24}。
 *
 * <p><b>为什么不引用库区维度上限</b>：本设计的初稿曾用
 * {@code max(2, 库区该维度上限的位数)} 计算段宽，于是「库区配置」被绑进了编码 ——
 * 配置一改，同一个库位的编码就变了，为保住编码稳定只好反过来禁止改配置
 * （禁缩维、禁跨段宽扩容）。需求方明确要求<b>所有维度可自由增减</b>，
 * 故把库区维度从编码规则里彻底移除：段宽只由库位自身的数字决定。
 *
 * <p>补零是 {@code %0Nd} 语义，<b>只补不截</b>：值 6 → {@code "06"}，值 106 → {@code "106"}。
 * 该映射对坐标是单射，故不同坐标必得不同编码 —— <b>唯一性不依赖任何外部配置</b>，
 * 幂等性也随之免费获得（同一坐标在任何时刻、任何请求范围下都算出同一编码）。
 *
 * @author 永鑫WMS
 */
public final class WmsLocationCodeGenerator {

    /** 每段最小宽度。低于 2 位会让 {@code C1} 与 {@code C01} 这类写法混用。 */
    public static final int MIN_SEGMENT_WIDTH = 2;

    private WmsLocationCodeGenerator() {
    }

    /**
     * 把单个坐标数字补零到 {@code max(MIN_SEGMENT_WIDTH, 自身位数)}。只补不截。
     *
     * @param value 坐标值，不可为 null
     * @return 补零后的字符串
     * @throws IllegalArgumentException value 为 null 时
     */
    public static String padSegment(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("库位坐标不能为空");
        }
        String raw = String.valueOf(value);
        int width = Math.max(MIN_SEGMENT_WIDTH, raw.length());
        StringBuilder padded = new StringBuilder();
        for (int i = raw.length(); i < width; i++) {
            padded.append('0');
        }
        return padded.append(raw).toString();
    }

    /**
     * 生成库位编码。
     *
     * @param warehouseCode 库区编码，不可为空
     * @param rowNo         排
     * @param colNo         列
     * @param layerNo       层
     * @return 形如 {@code LB-R01-C06-L24} 的编码
     * @throws IllegalArgumentException 库区编码为空、或任一坐标段为 null 时
     */
    public static String locationCode(String warehouseCode, Integer rowNo, Integer colNo, Integer layerNo) {
        if (warehouseCode == null || warehouseCode.trim().isEmpty()) {
            throw new IllegalArgumentException("库区编码不能为空");
        }
        return warehouseCode
                + "-R" + padSegment(rowNo)
                + "-C" + padSegment(colNo)
                + "-L" + padSegment(layerNo);
    }
}