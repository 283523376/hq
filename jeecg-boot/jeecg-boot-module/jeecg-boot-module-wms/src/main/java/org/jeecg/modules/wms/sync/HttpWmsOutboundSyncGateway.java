package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
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
 * @Description: 出库同步分流：金蝶 Writer 优先；未启用时可选 MES HTTP；都没有则 mock。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Component
public class HttpWmsOutboundSyncGateway implements IWmsOutboundSyncGateway {

    private final WmsKingdeeProperties properties;
    private final IWmsKingdeePickWriter pickWriter;
    private final IWmsKingdeeSaleOrderWriter saleOrderWriter;
    private final IWmsKingdeeOutstockWriter outstockWriter;
    private final String mesUrl;
    private final RestTemplate restTemplate;

    public HttpWmsOutboundSyncGateway(WmsKingdeeProperties properties,
                                      IWmsKingdeePickWriter pickWriter,
                                      IWmsKingdeeSaleOrderWriter saleOrderWriter,
                                      IWmsKingdeeOutstockWriter outstockWriter,
                                      @Value("${wms.sync.mes.outbound-url:}") String mesUrl) {
        this.properties = properties;
        this.pickWriter = pickWriter;
        this.saleOrderWriter = saleOrderWriter;
        this.outstockWriter = outstockWriter;
        this.mesUrl = mesUrl == null ? "" : mesUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsOrderSyncResult sync(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        if (properties.isEnabled()) {
            return WmsConstants.OUTBOUND_TYPE_PICK.equals(order.getOutboundType())
                    ? pickWriter.write(order, details)
                    : outstockWriter.write(order, details);
        }
        if (oConvertUtils.isNotEmpty(mesUrl)) {
            return postMes(order, details);
        }
        log.info("金蝶与 MES 出库地址均未配置，出库单[{}]按 mock 同步成功处理", order.getOutboundNo());
        return WmsOrderSyncResult.okMock();
    }

    @Override
    public WmsOrderSyncResult syncSaleOrder(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        if (properties.isEnabled()) {
            return saleOrderWriter.write(order, details);
        }
        if (oConvertUtils.isNotEmpty(mesUrl)) {
            return postMes(order, details);
        }
        return WmsOrderSyncResult.okMock();
    }

    private WmsOrderSyncResult postMes(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outboundNo", order.getOutboundNo());
            payload.put("outboundType", order.getOutboundType());
            payload.put("workOrderNo", order.getWorkOrderNo());
            payload.put("salesOrderNo", order.getSalesOrderNo());
            payload.put("customerCode", order.getCustomerCode());
            List<Map<String, Object>> items = new ArrayList<>();
            for (WmsOutboundDetail detail : details) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("materialCode", detail.getMaterialCode());
                item.put("batchNo", detail.getBatchNo());
                item.put("quantity", detail.getActualQty());
                items.add(item);
            }
            payload.put("details", items);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.postForEntity(mesUrl,
                    new HttpEntity<>(payload, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return WmsOrderSyncResult.fail("MES 返回非成功状态：" + response.getStatusCode());
            }
            return WmsOrderSyncResult.ok();
        } catch (Exception e) {
            log.warn("出库单[{}]同步 MES 异常：{}", order.getOutboundNo(), e.getMessage());
            return WmsOrderSyncResult.fail("同步 MES 异常：" + e.getMessage());
        }
    }
}
