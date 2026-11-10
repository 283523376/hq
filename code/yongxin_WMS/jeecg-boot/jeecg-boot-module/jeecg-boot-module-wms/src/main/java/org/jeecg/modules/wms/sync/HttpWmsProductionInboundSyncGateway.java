package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 生产入库同步实现：依次投递已配置的 ERP / MES / WMS 地址。
 *
 * <p>全部地址未配置时模拟成功（mocked），保证无外部系统环境可闭环演示；
 * 任一已配置地址失败即整体失败，由上层重试编排处理。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class HttpWmsProductionInboundSyncGateway implements IWmsProductionInboundSyncGateway {

    private static final int TIMEOUT_MS = 3000;

    private final String erpUrl;
    private final String erpUsername;
    private final String erpPassword;
    private final String mesUrl;
    private final String mesUsername;
    private final String mesPassword;
    private final String wmsUrl;
    private final RestTemplate restTemplate;

    @Autowired
    private WmsLocationMapper locationMapper;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    /** 金蝶云星空第三方登录会话；未配置时 applyAuth 直接放行。 */
    @Autowired
    private IWmsKingdeeAuthService kingdeeAuthService;

    /** 金蝶业务同步配置；enabled=true 时 ERP 目标改为 PRD_INSTOCK Save/Submit。 */
    @Autowired
    private WmsKingdeeProperties kingdeeProperties;

    @Autowired
    private IWmsKingdeeInboundWriter kingdeeInboundWriter;

    @Autowired
    public HttpWmsProductionInboundSyncGateway(
            @Value("${wms.sync.erp.production-inbound-url:}") String erpUrl,
            @Value("${wms.sync.erp.username:}") String erpUsername,
            @Value("${wms.sync.erp.password:}") String erpPassword,
            @Value("${wms.sync.mes.production-inbound-url:}") String mesUrl,
            @Value("${wms.sync.mes.username:}") String mesUsername,
            @Value("${wms.sync.mes.password:}") String mesPassword,
            @Value("${wms.sync.wms.production-inbound-url:}") String wmsUrl) {
        this.erpUrl = trim(erpUrl);
        this.erpUsername = trim(erpUsername);
        this.erpPassword = erpPassword == null ? "" : erpPassword;
        this.mesUrl = trim(mesUrl);
        this.mesUsername = trim(mesUsername);
        this.mesPassword = mesPassword == null ? "" : mesPassword;
        this.wmsUrl = trim(wmsUrl);
        this.restTemplate = buildRestTemplate();
    }

    /** 仅供单测注入 MockRestServiceServer 绑定的 RestTemplate。 */
    HttpWmsProductionInboundSyncGateway(String erpUrl, String erpUsername, String erpPassword,
                                        String mesUrl, String mesUsername, String mesPassword,
                                        String wmsUrl, RestTemplate restTemplate) {
        this.erpUrl = trim(erpUrl);
        this.erpUsername = trim(erpUsername);
        this.erpPassword = erpPassword == null ? "" : erpPassword;
        this.mesUrl = trim(mesUrl);
        this.mesUsername = trim(mesUsername);
        this.mesPassword = mesPassword == null ? "" : mesPassword;
        this.wmsUrl = trim(wmsUrl);
        this.restTemplate = restTemplate;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsProductionInbound inbound) {
        boolean kingdeeEnabled = kingdeeProperties != null && kingdeeProperties.isEnabled();
        List<String> errors = new ArrayList<>();
        if (kingdeeEnabled) {
            if (kingdeeInboundWriter == null) {
                errors.add("ERP(金蝶)：未注入回写器");
            } else {
                WmsOrderSyncResult erpResult = kingdeeInboundWriter.write(inbound);
                if (!erpResult.isSuccess()) {
                    errors.add("ERP(金蝶)：" + erpResult.getErrorMessage());
                }
            }
        }

        List<Target> targets = new ArrayList<>();
        // 启用金蝶时 ERP 由 PRD_INSTOCK 回写器负责，不再走通用 HTTP 地址，避免重复
        if (!kingdeeEnabled && !erpUrl.isEmpty()) {
            targets.add(new Target("ERP", erpUrl, erpUsername, erpPassword, true));
        }
        if (!mesUrl.isEmpty()) {
            // MES 是金蝶云星空 ERP 的下属子模块，同样使用金蝶会话
            targets.add(new Target("MES", mesUrl, mesUsername, mesPassword, true));
        }
        if (!wmsUrl.isEmpty()) {
            targets.add(new Target("WMS", wmsUrl, "", "", false));
        }
        if (targets.isEmpty() && !kingdeeEnabled) {
            log.info("生产入库[{}]未配置任何同步地址，按模拟成功处理", inbound.getInboundNo());
            return WmsOrderSyncResult.okMock();
        }

        Map<String, Object> payload = buildPayload(inbound);
        for (Target target : targets) {
            postTarget(target, payload, errors, inbound.getInboundNo());
        }
        if (errors.isEmpty()) {
            return WmsOrderSyncResult.ok();
        }
        return WmsOrderSyncResult.fail(String.join("；", errors));
    }

    /** 投递单个目标；金蝶目标带会话，401/403 时重新登录并重试一次。 */
    private void postTarget(Target target, Map<String, Object> payload,
                            List<String> errors, String inboundNo) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!target.username.isEmpty()) {
                headers.setBasicAuth(target.username, target.password);
            }
            if (target.kingdee && kingdeeAuthService != null) {
                if (!kingdeeAuthService.applyAuth(headers)) {
                    errors.add(target.name + " 金蝶鉴权失败");
                    return;
                }
            }
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(target.url,
                        new HttpEntity<>(payload, headers), String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return;
                }
                if (target.kingdee && isAuthError(response.getStatusCode().value()) && attempt == 0) {
                    if (kingdeeAuthService != null) {
                        kingdeeAuthService.invalidate();
                    }
                    continue;
                }
                errors.add(target.name + " 返回 " + response.getStatusCode());
                return;
            } catch (HttpStatusCodeException e) {
                if (target.kingdee && isAuthError(e.getStatusCode().value()) && attempt == 0) {
                    if (kingdeeAuthService != null) {
                        kingdeeAuthService.invalidate();
                    }
                    continue;
                }
                log.warn("生产入库[{}]同步 {} 异常：{}", inboundNo, target.name, e.getMessage());
                errors.add(target.name + " 调用异常：" + e.getMessage());
                return;
            } catch (Exception e) {
                log.warn("生产入库[{}]同步 {} 异常：{}", inboundNo, target.name, e.getMessage());
                errors.add(target.name + " 调用异常：" + e.getMessage());
                return;
            }
        }
    }

    private boolean isAuthError(int status) {
        return status == 401 || status == 403;
    }

    /** 组装同步报文；包级可见便于单测直接断言字段（尤其是不良品相关字段）。 */
    Map<String, Object> buildPayload(WmsProductionInbound inbound) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inboundNo", inbound.getInboundNo());
        payload.put("workOrderNo", inbound.getWorkOrderNo());
        payload.put("productType", inbound.getProductType());
        payload.put("materialCode", inbound.getMaterialCode());
        payload.put("materialName", inbound.getMaterialName());
        payload.put("batchNo", inbound.getBatchNo());
        payload.put("quantity", inbound.getQuantity());
        payload.put("goodQty", inbound.getGoodQty());
        payload.put("badQty", inbound.getBadQty());
        payload.put("warehouseType", resolveWarehouseType(inbound.getLocationId()));
        payload.put("locationCode", resolveLocationCode(inbound.getLocationId()));
        payload.put("scrapLocationCode", resolveLocationCode(inbound.getScrapLocationId()));
        payload.put("defectRemark", inbound.getDefectRemark());
        payload.put("carrierCode", inbound.getCarrierCode());
        payload.put("identType", inbound.getIdentType());
        payload.put("completedAt", inbound.getCompleteTime() == null ? null
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(inbound.getCompleteTime()));
        return payload;
    }

    private String resolveLocationCode(String locationId) {
        if (oConvertUtils.isEmpty(locationId)) {
            return null;
        }
        WmsLocation location = locationMapper.selectById(locationId);
        return location == null ? null : location.getLocationCode();
    }

    private String resolveWarehouseType(String locationId) {
        if (oConvertUtils.isEmpty(locationId)) {
            return null;
        }
        WmsLocation location = locationMapper.selectById(locationId);
        if (location == null) {
            return null;
        }
        WmsWarehouse warehouse = warehouseMapper.selectById(location.getWarehouseId());
        return warehouse == null ? null : warehouse.getWarehouseType();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Target {
        private final String name;
        private final String url;
        private final String username;
        private final String password;
        private final boolean kingdee;

        private Target(String name, String url, String username, String password, boolean kingdee) {
            this.name = name;
            this.url = url;
            this.username = username;
            this.password = password;
            this.kingdee = kingdee;
        }
    }
}
