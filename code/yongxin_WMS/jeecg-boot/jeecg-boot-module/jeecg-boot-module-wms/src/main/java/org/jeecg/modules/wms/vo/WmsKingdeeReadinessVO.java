package org.jeecg.modules.wms.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 金蝶对接就绪度（V2.4 RK-001 前置自检）。
 *
 * <p>目的是把「配置到底生效没有」变成一次查询，而不是靠试单看报错。
 * 常见两类误配在这里直接暴露：
 * <ol>
 *   <li>只填了 5 个 formId，忘了 `wms.sync.erp.kingdee.enabled=true`（环境变量未设）；</li>
 *   <li>测试环境走 HTTP，但没设 `allow-http=true`，登录被安全检查拒绝。</li>
 * </ol>
 *
 * <p>本 VO <b>不返回任何密钥原文</b>：appId 只给脱敏值，其余凭据只给「是否已设置」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
public class WmsKingdeeReadinessVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全部检查通过才为 true；为 false 时看 problems。 */
    private boolean ready;

    /** wms.sync.erp.kingdee.enabled。false 时后面配了也不会调用金蝶。 */
    private boolean enabled;

    /** 五项凭据（base-url/acct-id/username/app-id/app-secret）是否齐全。 */
    private boolean credentialsConfigured;

    private String baseUrl;
    private boolean acctIdConfigured;
    private boolean usernameConfigured;
    /** 脱敏后的 appId，例如 abcd12****。 */
    private String appIdMasked;
    private boolean appSecretConfigured;
    private boolean allowHttp;
    /** http / https / 未知。 */
    private String transport;

    /** 5 个单据 formId 的配置状态。 */
    private List<FormId> formIds = new ArrayList<>();

    /** 未就绪原因（逐条人类可读），ready=true 时为空。 */
    private List<String> problems = new ArrayList<>();

    /** 单个单据 formId 的配置状态。 */
    @Data
    public static class FormId implements Serializable {

        private static final long serialVersionUID = 1L;

        private String key;
        private String label;
        private String formId;
        private boolean configured;

        public FormId(String key, String label, String formId) {
            this.key = key;
            this.label = label;
            this.formId = formId;
            this.configured = formId != null && !formId.trim().isEmpty();
        }
    }
}
