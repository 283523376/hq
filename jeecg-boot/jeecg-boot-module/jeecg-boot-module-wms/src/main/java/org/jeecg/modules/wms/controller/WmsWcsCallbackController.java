package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.wms.dto.WmsTaskFeedbackDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 堆垛机 WCS 任务回执（占位契约）。
 *
 * <p>外部系统无法持有用户 JWT：方法级 {@code @IgnoreAuth} + 共享令牌头 X-WCS-Token。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-WCS回调")
@RestController
@RequestMapping("/wms/wcs")
@Slf4j
public class WmsWcsCallbackController {

    @Autowired
    private IWmsProductionInboundService productionService;

    @Autowired
    private IWmsProductionSyncService productionSyncService;
    /** 「自动同步」开关：关闭时不即时回写，单据留在「待同步」由页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;


    @Autowired
    private org.jeecg.modules.wms.service.IWmsOutboundService outboundService;

    @Value("${wms.sync.wcs.callback-token:}")
    private String callbackToken;

    @IgnoreAuth
    @Operation(summary = "WCS-任务反馈")
    @PostMapping(value = "/task-feedback")
    public Map<String, Object> taskFeedback(
            @RequestHeader(name = "X-WCS-Token", required = false) String token,
            @RequestBody WmsTaskFeedbackDTO dto) {
        if (!tokenValid(token)) {
            return failed("WCS 回调令牌无效");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int failedCount = 0;
        if (dto != null && dto.getDetails() != null) {
            for (WmsTaskFeedbackDTO.Detail detail : dto.getDetails()) {
                String carrier = oConvertUtils.isNotEmpty(detail.getCarrierCode())
                        ? detail.getCarrierCode() : detail.getContainerCode();
                String stack = oConvertUtils.isNotEmpty(detail.getLocationCode())
                        ? detail.getLocationCode() : detail.getStackCode();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskNo", detail.getTaskNo());
                item.put("carrierCode", carrier);
                WmsProductionInbound record = productionService.findByTaskNo(detail.getTaskNo());
                if (record == null) {
                    // 未命中入库任务时，路由到出库分配行；出库只在确认出库后同步
                    if (outboundService.isOutboundTask(detail.getTaskNo())) {
                        outboundService.onTaskFeedback(detail.getTaskNo(), carrier,
                                detail.getTaskStatus(), detail.getMessage(), false);
                        item.put("code", 0);
                        item.put("msg", "");
                    } else {
                        item.put("code", -1);
                        item.put("msg", "任务号不存在");
                        failedCount++;
                    }
                } else {
                    boolean completed = productionService.onTaskFeedback(detail.getTaskNo(), carrier,
                            detail.getTaskStatus(), stack, detail.getMessage(), false);
                    if (completed) {
                        // 先乐观认领，避免与补偿任务重复投递
                        if (productionService.claimForSync(record.getId(),
                                productionService.syncStaleBefore()) > 0) {
                            // 自动同步关闭时只把记录留在「待同步」，由页面手工推送
                            if (!WmsSyncAutoGate.isManualOnly(systemConfigService)) {
                                WmsOrderSyncAttempt attempt =
                                        productionSyncService.syncInbound(record.getId(), true);
                                productionService.persistSyncResult(record.getId(), attempt);
                            } else {
                                log.info("自动同步已关闭，跳过设备回执后的即时回写：{}", record.getId());
                            }
                        }
                    }
                    item.put("code", 0);
                    item.put("msg", "");
                }
                results.add(item);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", failedCount == 0 ? 0 : 1);
        response.put("msg", failedCount == 0 ? "success" : "partial success");
        response.put("details", results);
        return response;
    }

    private boolean tokenValid(String token) {
        return oConvertUtils.isNotEmpty(callbackToken) && callbackToken.equals(token);
    }

    private Map<String, Object> failed(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 1);
        response.put("msg", message);
        return response;
    }
}
