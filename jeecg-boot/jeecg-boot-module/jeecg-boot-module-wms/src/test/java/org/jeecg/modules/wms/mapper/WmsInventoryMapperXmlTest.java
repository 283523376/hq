package org.jeecg.modules.wms.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 盘点覆盖库存 SQL 的审计列守卫（SB-001 前置修复）。
 *
 * <p>「今日变动」对账按 {@code wms_inventory.update_time} 取数。本项目没有
 * MyBatis-Plus 自动填充，SQL 里不显式写 update_time / update_by，
 * 盘点调整就会在对账里凭空消失。此测试把这两列钉死在 SQL 里，
 * 防止后续改 SQL 时被顺手删掉。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsInventoryMapperXmlTest {

    /** 从 classpath 读 mapper XML：不依赖测试的工作目录（否则换个目录启动就红）。 */
    private String mapperXml() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/org/jeecg/modules/wms/mapper/xml/WmsInventoryMapper.xml")) {
            assertTrue(in != null, "找不到 Mapper XML");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void touchQuantity_overwritesQtyAndRefreshesUpdateTime() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<update id=\"touchQuantity\">");
        assertTrue(start > 0, "touchQuantity 语句应存在");
        String statement = xml.substring(start, xml.indexOf("</update>", start));

        assertTrue(statement.contains("update_time = NOW()"),
                "盘点覆盖库存必须显式刷新 update_time：SB-001「今日变动」按它取数，"
                        + "没有 MetaObjectHandler 自动填充，漏了这列对账就永远看不到盘点调整");
        assertTrue(statement.contains("quantity = #{qty}"),
                "盘点是整行覆盖：quantity 直接写成实盘值，不是增减");
    }
}
