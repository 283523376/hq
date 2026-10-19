package org.jeecg.modules.wms.mapper;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 防止 Mapper XML 中的中文状态字面量与 WmsConstants 漂移。
 *
 * <p>XML 无法直接引用 Java 常量，这里用「扫描 + 校验」把漂移变成测试失败。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionStatusLiteralTest {

    private static final Pattern CHINESE_LITERAL = Pattern.compile("'([^']*[\\u4e00-\\u9fa5][^']*)'");

    @Test
    void mapperStatusLiteralsExistInConstants() throws Exception {
        Set<String> constants = Arrays.stream(WmsConstants.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toSet());

        for (String path : new String[] {
                "/org/jeecg/modules/wms/mapper/xml/WmsProductionInboundMapper.xml",
                "/org/jeecg/modules/wms/mapper/xml/WmsWorkOrderMapper.xml"}) {
            try (InputStream in = getClass().getResourceAsStream(path)) {
                assertNotNull(in, "找不到 Mapper XML：" + path);
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = CHINESE_LITERAL.matcher(xml);
                while (matcher.find()) {
                    String literal = matcher.group(1);
                    assertTrue(constants.contains(literal),
                            path + " 中的字面量「" + literal + "」未在 WmsConstants 中定义");
                }
            }
        }
    }
}
