package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于可配置 HTTP 地址的入库单同步实现。
 *
 * <p>读取配置 {@code wms.sync.erp.inbound-url}；<b>未配置时模拟成功</b>（spec §7）——
 * 与订单网关相反：本期 ERP 侧尚无真实入库接口，模拟成功保证采购入库流程可闭环演示；
 * 配置一个不可达地址即可真实演练「重试 + 告警」链路。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class HttpWmsInboundErpSyncGateway implements WmsInboundErpSyncGateway {

    /** 连接与读取超时（毫秒）；必须小于单次同步的预期耗时。 */
    private static final int TIMEOUT_MS = 3000;

    private final String syncUrl;
    private final RestTemplate restTemplate;

    public HttpWmsInboundErpSyncGateway(@Value("${wms.sync.erp.inbound-url:}") String syncUrl) {
        this.syncUrl = syncUrl == null ? "" : syncUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsInboundOrder order, List<WmsInboundDetail> details) {
        if (syncUrl.isEmpty()) {
            log.info("入库单[{}]未配置 ERP 入库同步地址（wms.sync.erp.inbound-url），按模拟成功处理",
                    order.getOrderNo());
            return WmsOrderSyncResult.ok();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(buildPayload(order, details), headers);

            ResponseEntity<String> response = restTemplate.postForEntity(syncUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return WmsOrderSyncResult.ok();
            }
            return WmsOrderSyncResult.fail("ERP 返回非成功状态：" + response.getStatusCode()
                    + "，响应：" + abbreviate(response.getBody()));
        } catch (Exception e) {
            // 约定：不抛异常，全部转成失败结果交给重试编排
            log.warn("入库单[{}]同步 ERP 调用异常：{}", order.getOrderNo(), e.getMessage());
            return WmsOrderSyncResult.fail("调用 ERP 异常：" + e.getMessage());
        }
    }

    /** 组装提交报文（入库单头 + 明细）。 */
    private Map<String, Object> buildPayload(WmsInboundOrder order, List<WmsInboundDetail> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("inboundType", order.getInboundType());
        payload.put("purchaseOrderNo", order.getPurchaseOrderNo());
        payload.put("supplierId", order.getSupplierId());
        payload.put("supplierCode", order.getSupplierCode());
        payload.put("supplierName", order.getSupplierName());
        payload.put("inboundStatus", order.getInboundStatus());
        payload.put("auditTime", order.getAuditTime());
        List<Map<String, Object>> lines = new ArrayList<>();
        if (details != null) {
            for (WmsInboundDetail d : details) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("materialId", d.getMaterialId());
                line.put("materialCode", d.getMaterialCode());
                line.put("batchNo", d.getBatchNo());
                line.put("actualQuantity", d.getActualQuantity());
                line.put("locationId", d.getLocationId());
                lines.add(line);
            }
        }
        payload.put("details", lines);
        return payload;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截断)";
    }
}
