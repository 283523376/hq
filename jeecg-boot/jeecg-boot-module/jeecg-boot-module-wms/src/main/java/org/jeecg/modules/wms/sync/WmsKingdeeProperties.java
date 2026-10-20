package org.jeecg.modules.wms.sync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Description: 金蝶云星空业务同步配置（wms.sync.erp.kingdee.*）。
 *
 * <p>组织/单据类型/库存状态等默认值来自测试环境实测，生产需按顾问值覆盖；
 * appSecret 只从环境变量注入，禁止写入仓库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms.sync.erp.kingdee")
public class WmsKingdeeProperties {

    /** 是否启用金蝶业务同步（ERP 目标改为 PRD_INSTOCK Save/Submit）。 */
    private boolean enabled;

    private String baseUrl;
    private String acctId;
    private String username;
    private String appId;
    private String appSecret;
    private int lcid = 2052;
    private int timeoutMs = 30_000;
    private boolean allowHttp;

    /** 生产组织 / 入库组织 / 货主 / 保管者等默认值（测试环境实测）。 */
    private String defaultOrgId;
    /** 组织编码（ORG_Organizations.FNumber），基础资料字典用，测试环境为 100。 */
    private String defaultOrgNumber;
    private String billType;
    private String ownerType;
    private String ownerId;
    private String keeperType;
    private String keeperId;
    private String stockStatusId;
    private String inStockType;

    /** 库区类型 → 金蝶仓库编码，例如 stereo=02.002、agile=02.001、scrap=02.005。 */
    private Map<String, String> stockCode = new LinkedHashMap<>();

    /** WMS 物料编码 → 金蝶物料编码；为空时按相同编码查询。 */
    private Map<String, String> materialMap = new LinkedHashMap<>();

    /**
     * WMS 物料编码 → WMS 产品类型（半成品/成品）。
     * 金蝶 FProductType 是「主产品/联产品/副产品」，不能区分半成品/成品，产品类型必须以物料维度配置。
     */
    private Map<String, String> materialProductType = new LinkedHashMap<>();

    /** 金蝶 FProductType 枚举 → WMS 产品类型。 */
    private Map<String, String> productTypeMap = new LinkedHashMap<>();

    /** 金蝶 FDocumentStatus → WMS 工单状态。 */
    private Map<String, String> statusMap = new LinkedHashMap<>();
}
