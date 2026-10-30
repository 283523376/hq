package org.jeecg.modules.wms.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 回执完成 SQL 的同步口径守卫。
 *
 * <p>方案 B：工序汇报来源（erp_entry_key 非空）的入库单由 ERP 车间汇报生成生产入库单，
 * WMS 只记账本仓库存、永不进入回写队列。设备回执把状态改成「已入库」时，
 * 若把 sync_status 一律刷成「待同步」，就会把这些单据塞进同步队列，
 * 每轮撞「ERP 尚未汇报入库」，还会把推进时设的「无需同步」冲掉。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-30
 */
class WmsProductionInboundMapperXmlTest {

    /** 从 classpath 读 mapper XML：不依赖测试的工作目录（否则换个目录启动就红）。 */
    private String mapperXml() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/org/jeecg/modules/wms/mapper/xml/WmsProductionInboundMapper.xml")) {
            assertTrue(in != null, "找不到 Mapper XML");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void claimFinished_keepsReportSourcedOutOfSyncQueue() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<update id=\"claimFinished\">");
        assertTrue(start > 0, "claimFinished 语句应存在");
        String statement = xml.substring(start, xml.indexOf("</update>", start));

        assertTrue(statement.contains("erp_entry_key"),
                "回执完成必须区分工序汇报来源：这类单据的 sync_status 不能刷成「待同步」");
        assertTrue(statement.contains("无需同步"),
                "工序汇报来源回执后应保持「无需同步」，永不进入回写队列");
    }

    @Test
    void reconcileCandidates_doesNotHardcodeBillPrefix() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("id=\"selectReportSourcedPendingReconcile\"");
        assertTrue(start > 0, "对账候选查询应存在");
        String statement = xml.substring(start, xml.indexOf("</select>", start));

        assertTrue(!statement.contains("LIKE 'GXHB%'"),
                "对账键不能写死单号前缀：金蝶编码规则一改，新单就永远进不了候选（静默失败）");
        assertTrue(statement.contains("SUBSTRING_INDEX"),
                "用 erp_bill_no 是否仍等于 erp_entry_key 的单号段来判断「尚未回填」，回填后自动退出候选");
        assertTrue(statement.contains("update_time DESC"),
                "候选按最近更新排序：老单沉底但不阻断新单，避免批次被永久占满");
    }
}
