package org.jeecg.modules.wms.sync;

import org.springframework.http.HttpHeaders;

/**
 * 金蝶云星空第三方系统登录（LoginByAppSecret）会话服务。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
public interface IWmsKingdeeAuthService {

    /** 五项配置（base-url/acct-id/username/app-id/app-secret）齐全才视为启用。 */
    boolean isConfigured();

    /**
     * 给请求头附加金蝶会话 Cookie。
     *
     * @return true=无需鉴权或鉴权成功；false=配置了金蝶但登录失败（调用方应判为同步失败）
     */
    boolean applyAuth(HttpHeaders headers);

    /** 会话失效时清空缓存，下次调用重新登录。 */
    void invalidate();

    /** 脱敏后的 appId，仅用于日志。 */
    String maskedAppId();
}
