package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Description: 基于可配置 HTTP 地址的订单同步实现。
 *
 * <p>读取配置 {@code wms.sync.erp.url}；<b>未配置时直接返回失败</b>——于是「重试 + 告警」
 * 这条链路在未接入真实 ERP/MES 时也能开箱跑通、可用于验收。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
public class HttpWmsOrderSyncGateway implements WmsOrderSyncGateway {

    /** 连接与读取超时（毫秒）；必须小于单次同步的预期耗时，避免拖垮下达接口。 */
    private static final int TIMEOUT_MS = 3000;

    private final String syncUrl;
    private final RestTemplate restTemplate;

    public HttpWmsOrderSyncGateway(@Value("${wms.sync.erp.url:}") String syncUrl) {
        this.syncUrl = syncUrl == null ? "" : syncUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsOrder order) {
        if (syncUrl.isEmpty()) {
            return WmsOrderSyncResult.fail("未配置 ERP/MES 同步地址（wms.sync.erp.url）");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(buildPayload(order), headers);

            ResponseEntity<String> response = restTemplate.postForEntity(syncUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return WmsOrderSyncResult.ok();
            }
            return WmsOrderSyncResult.fail("ERP/MES 返回非成功状态：" + response.getStatusCode()
                    + "，响应：" + abbreviate(response.getBody()));
        } catch (Exception e) {
            // 约定：不抛异常，全部转成失败结果交给重试编排
            log.warn("订单[{}]同步 ERP/MES 调用异常：{}", order.getOrderNo(), e.getMessage());
            return WmsOrderSyncResult.fail("调用 ERP/MES 异常：" + e.getMessage());
        }
    }

    /**
     * 组装提交报文。字段与蓝图 BD-002 的同步要素对齐。
     */
    private Map<String, Object> buildPayload(WmsOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType());
        payload.put("supplierId", order.getSupplierId());
        payload.put("supplierCode", order.getSupplierCode());
        payload.put("supplierName", order.getSupplierName());
        payload.put("orderStatus", order.getOrderStatus());
        payload.put("auditTime", order.getAuditTime());
        payload.put("dispatchTime", order.getDispatchTime());
        return payload;
    }

    /**
     * 截断过长的响应体，避免把一整页 HTML 灌进日志与数据库字段。
     */
    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截断)";
    }
}
