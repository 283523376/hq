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
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsProductionInbound inbound) {
        List<Target> targets = new ArrayList<>();
        if (!erpUrl.isEmpty()) {
            targets.add(new Target("ERP", erpUrl, erpUsername, erpPassword));
        }
        if (!mesUrl.isEmpty()) {
            targets.add(new Target("MES", mesUrl, mesUsername, mesPassword));
        }
        if (!wmsUrl.isEmpty()) {
            targets.add(new Target("WMS", wmsUrl, "", ""));
        }
        if (targets.isEmpty()) {
            log.info("生产入库[{}]未配置任何同步地址，按模拟成功处理", inbound.getInboundNo());
            return WmsOrderSyncResult.okMock();
        }

        Map<String, Object> payload = buildPayload(inbound);
        List<String> errors = new ArrayList<>();
        for (Target target : targets) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!target.username.isEmpty()) {
                    headers.setBasicAuth(target.username, target.password);
                }
                ResponseEntity<String> response = restTemplate.postForEntity(target.url,
                        new HttpEntity<>(payload, headers), String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    errors.add(target.name + " 返回 " + response.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("生产入库[{}]同步 {} 异常：{}", inbound.getInboundNo(), target.name, e.getMessage());
                errors.add(target.name + " 调用异常：" + e.getMessage());
            }
        }
        if (errors.isEmpty()) {
            return WmsOrderSyncResult.ok();
        }
        return WmsOrderSyncResult.fail(String.join("；", errors));
    }

    private Map<String, Object> buildPayload(WmsProductionInbound inbound) {
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

        private Target(String name, String url, String username, String password) {
            this.name = name;
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }
}
