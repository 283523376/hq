package org.jeecg.modules.wms.controller;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsKingdeeReadinessVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶对接就绪度自检（V2.4 RK-001 前置）。
 *
 * <p>这个自检存在的意义：配置金蝶有 4 类前提（开关、凭据、HTTP 放行、5 个 formId），
 * 少任何一项都表现为「试单失败」，而失败文案各不相同、很像业务问题。自检把它们一次性摊开。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsKingdeeReadinessTest {

    private static final String[] FORM_ID_KEYS = {
            WmsConfigKey.KD_FORM_PURCHASE_ORDER.getKey(),
            WmsConfigKey.KD_FORM_CONSIGNED_ORDER.getKey(),
            WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey(),
            WmsConfigKey.KD_FORM_CONSIGNED_INBOUND.getKey(),
            WmsConfigKey.KD_FORM_CONSIGNED_RETURN.getKey()
    };

    private IWmsSystemConfigService configService;
    private IWmsKingdeeAuthService authService;
    private WmsKingdeeProperties properties;
    private WmsSystemConfigController controller;

    @BeforeEach
    void setUp() {
        configService = mock(IWmsSystemConfigService.class);
        authService = mock(IWmsKingdeeAuthService.class);
        properties = new WmsKingdeeProperties();

        controller = new WmsSystemConfigController();
        WmsTestSupport.inject(controller, "service", configService);
        WmsTestSupport.inject(controller, "kingdeeProperties", properties);
        WmsTestSupport.inject(controller, "kingdeeAuthService", authService);
    }

    /** 五个 formId 全部返回空串（未配置）。 */
    private void noFormIds() {
        when(configService.getString(anyString(), eq(""))).thenReturn("");
    }

    private void allFormIds() {
        when(configService.getString(anyString(), eq(""))).thenReturn("SOME_FORM_ID");
    }

    private void credentialsConfigured() {
        properties.setBaseUrl("https://kd.example.com");
        properties.setAcctId("acct");
        properties.setUsername("wms");
        properties.setAppSecret("secret");
        when(authService.isConfigured()).thenReturn(true);
        when(authService.maskedAppId()).thenReturn("abcd12****");
    }

    @Test
    void reportsEveryMissingPrerequisiteWhenNothingConfigured() {
        noFormIds();
        when(authService.isConfigured()).thenReturn(false);
        when(authService.maskedAppId()).thenReturn("****");

        WmsKingdeeReadinessVO vo = controller.kingdeeReadiness().getResult();

        assertNotNull(vo);
        assertFalse(vo.isReady());
        // 开关 + 凭据 + 5 个 formId = 7 条
        assertEquals(7, vo.getProblems().size(), String.join(" | ", vo.getProblems()));
        assertTrue(vo.getProblems().get(0).contains("KINGDEE_ERP_ENABLED"), vo.getProblems().get(0));
        assertTrue(vo.getProblems().get(1).contains("KINGDEE_ERP_APP_SECRET"), vo.getProblems().get(1));
        assertEquals(5, vo.getFormIds().size());
        vo.getFormIds().forEach(f -> assertFalse(f.isConfigured(), f.getKey()));
    }

    /** 测试环境最容易漏的一条：目标是 HTTP 但 allow-http 没开，登录会被安全检查直接拒。 */
    @Test
    void flagsHttpWithoutAllowHttp() {
        allFormIds();
        credentialsConfigured();
        properties.setBaseUrl("http://219.139.47.222:2021");
        properties.setEnabled(true);
        properties.setAllowHttp(false);

        WmsKingdeeReadinessVO vo = controller.kingdeeReadiness().getResult();

        assertFalse(vo.isReady());
        assertEquals("http", vo.getTransport());
        assertEquals(1, vo.getProblems().size(), String.join(" | ", vo.getProblems()));
        assertTrue(vo.getProblems().get(0).contains("KINGDEE_ERP_ALLOW_HTTP"), vo.getProblems().get(0));
    }

    @Test
    void readyOnlyWhenEverythingIsSet() {
        allFormIds();
        credentialsConfigured();
        properties.setEnabled(true);
        properties.setAllowHttp(true);
        properties.setBaseUrl("http://219.139.47.222:2021");

        WmsKingdeeReadinessVO vo = controller.kingdeeReadiness().getResult();

        assertTrue(vo.isReady(), String.join(" | ", vo.getProblems()));
        assertTrue(vo.getProblems().isEmpty());
        assertEquals(5, vo.getFormIds().size());
        vo.getFormIds().forEach(f -> assertTrue(f.isConfigured(), f.getKey()));
    }

    /** 安全底线：响应里不能出现密钥原文，appId 只能是脱敏值。 */
    @Test
    void neverEchoesRawCredentials() {
        allFormIds();
        credentialsConfigured();
        properties.setEnabled(true);
        properties.setAllowHttp(true);

        WmsKingdeeReadinessVO vo = controller.kingdeeReadiness().getResult();

        assertEquals("abcd12****", vo.getAppIdMasked());
        assertTrue(vo.isAppSecretConfigured());
        // 没有任何字段承载 secret 原文
        assertFalse(vo.toString().contains("secret"), vo.toString());
    }

    /** 每个 formId 都要带上配置键，否则用户不知道去改哪一项。 */
    @Test
    void eachFormIdCarriesItsConfigKey() {
        noFormIds();
        when(authService.maskedAppId()).thenReturn("****");

        WmsKingdeeReadinessVO vo = controller.kingdeeReadiness().getResult();

        for (String key : FORM_ID_KEYS) {
            assertTrue(vo.getFormIds().stream().anyMatch(f -> key.equals(f.getKey())),
                    "缺少配置项：" + key);
        }
    }

    /** 自检本身不能因为组件缺失就 500：裁剪部署时 properties/authService 可能不在。 */
    @Test
    void survivesMissingOptionalBeans() {
        WmsSystemConfigController bare = new WmsSystemConfigController();
        WmsTestSupport.inject(bare, "service", configService);
        when(configService.getString(anyString(), eq(""))).thenReturn("");

        Result<WmsKingdeeReadinessVO> result = bare.kingdeeReadiness();

        assertEquals(200, result.getCode());
        assertNotNull(result.getResult());
        assertFalse(result.getResult().isReady());
    }
}
