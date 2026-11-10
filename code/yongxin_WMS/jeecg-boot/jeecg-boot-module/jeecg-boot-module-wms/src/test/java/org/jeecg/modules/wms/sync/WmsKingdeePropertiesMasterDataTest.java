package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: master-data 配置绑定默认值单测——默认关闭、组织默认 101、集合非 null（spec §7）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeePropertiesMasterDataTest {

    @Test
    void shouldDefaultToDisabledWithOrg101() {
        WmsKingdeeProperties properties = new WmsKingdeeProperties();
        WmsKingdeeProperties.MasterData masterData = properties.getMasterData();

        assertNotNull(masterData);
        assertFalse(masterData.isEnabled());
        assertEquals("101", masterData.getOrgId());
    }

    @Test
    void shouldInitializeCollectionsToEmptyNotNull() {
        WmsKingdeeProperties.MasterData masterData = new WmsKingdeeProperties().getMasterData();

        assertNotNull(masterData.getStockWhitelist());
        assertNotNull(masterData.getStockCodeMap());
        assertNotNull(masterData.getCategoryMap());
        assertNotNull(masterData.getOrderTypeValueMap());
        assertTrue(masterData.getStockWhitelist().isEmpty());
        assertTrue(masterData.getStockCodeMap().isEmpty());
        assertTrue(masterData.getCategoryMap().isEmpty());
        assertTrue(masterData.getOrderTypeValueMap().isEmpty());
    }

    @Test
    void shouldBindMutatedValues() {
        WmsKingdeeProperties.MasterData masterData = new WmsKingdeeProperties().getMasterData();

        masterData.setEnabled(true);
        masterData.getStockWhitelist().add("X.2.4");
        masterData.getStockCodeMap().put("X.2.4", "TC");
        masterData.setOrderTypeField("F_UNW_Text_hp8");

        assertTrue(masterData.isEnabled());
        assertEquals("X.2.4", masterData.getStockWhitelist().get(0));
        assertEquals("TC", masterData.getStockCodeMap().get("X.2.4"));
        assertEquals("F_UNW_Text_hp8", masterData.getOrderTypeField());
    }
}
