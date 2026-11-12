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

    private String statement(String id, String tag) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<update id=\"" + id + "\">");
        assertTrue(start > 0, tag + " 语句应存在");
        return xml.substring(start, xml.indexOf("</update>", start));
    }

    /** D2：锁定必须带「可用量足够」条件——条件被删，并发分配会超发且把 locked_qty 加成负数。 */
    @Test
    void lockInventory_requiresSufficientAvailable() throws Exception {
        String s = statement("lockInventory", "lockInventory");
        assertTrue(s.contains("quantity - locked_qty &gt;= #{qty}"),
                "lockInventory 必须带 quantity - locked_qty >= qty 条件，0 行=不足，由调用方回滚");
    }

    /** D2：确认出库必须同时校验锁定量与总量——少一个都会把别人的锁一起扣掉或扣成负数。 */
    @Test
    void deductLocked_requiresBothLockAndQuantity() throws Exception {
        String s = statement("deductLocked", "deductLocked");
        assertTrue(s.contains("locked_qty &gt;= #{qty}") && s.contains("quantity &gt;= #{qty}"),
                "deductLocked 必须同时带 locked_qty >= qty 与 quantity >= qty 两个条件");
    }

    /** D2：释放锁定只准释放自己锁的量——条件被删，取消时会抹掉其它出库单的锁定。 */
    @Test
    void releaseLocked_neverOverReleases() throws Exception {
        String s = statement("releaseLocked", "releaseLocked");
        assertTrue(s.contains("locked_qty &gt;= #{qty}"),
                "releaseLocked 必须带 locked_qty >= qty 条件，防止释放别人的锁");
    }

    /** D2：直接扣减（报废）不允许扣成负数。 */
    @Test
    void deductQuantity_neverGoesNegative() throws Exception {
        String s = statement("deductQuantity", "deductQuantity");
        assertTrue(s.contains("quantity &gt;= #{qty}"),
                "deductQuantity 必须带 quantity >= qty 条件，0 行由调用方回滚");
    }

    /** D1：入库聚合必须是累加语义，且所有权两列必须显式在列清单里。 */
    @Test
    void upsertInventory_accumulatesAndCarriesOwnership() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<insert id=\"upsertInventory\"");
        assertTrue(start > 0, "upsertInventory 语句应存在");
        String s = xml.substring(start, xml.indexOf("</insert>", start));
        assertTrue(s.contains("quantity = quantity + VALUES(quantity)"),
                "入库必须累加（ON DUPLICATE KEY quantity = quantity + VALUES(quantity)），覆盖会丢账");
        assertTrue(s.contains("ownership_type") && s.contains("consignor_id"),
                "列清单必须显式含 ownership_type/consignor_id，漏写受托货会落默认 OWNED 并入自有行");
    }
}
