package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.wms.dto.WmsContainerLocationDTO;
import org.jeecg.modules.wms.dto.WmsTaskFeedbackDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
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
 * @Description: 米塔 RMS 回调（RMS任务接口文档 V1.6 §4.1 / §4.5）。
 *
 * <p>外部系统无法持有用户 JWT：方法级 {@code @IgnoreAuth} + 共享令牌头 X-RMS-Token。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-RMS回调")
@RestController
@RequestMapping("/wms/rms")
@Slf4j
public class WmsRmsCallbackController {

    @Autowired
    private IWmsProductionInboundService productionService;

    @Autowired
    private IWmsProductionSyncService productionSyncService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsOutboundService outboundService;

    @Value("${wms.sync.rms.callback-token:}")
    private String callbackToken;

    @IgnoreAuth
    @Operation(summary = "RMS-任务反馈")
    @PostMapping(value = "/task-feedback")
    public Map<String, Object> taskFeedback(
            @RequestHeader(name = "X-RMS-Token", required = false) String token,
            @RequestBody WmsTaskFeedbackDTO dto) {
        if (!tokenValid(token)) {
            return failed("RMS 回调令牌无效");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int failedCount = 0;
        if (dto != null && dto.getDetails() != null) {
            for (WmsTaskFeedbackDTO.Detail detail : dto.getDetails()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskNo", detail.getTaskNo());
                item.put("containerCode", detail.getContainerCode());
                WmsProductionInbound record = productionService.findByTaskNo(detail.getTaskNo());
                if (record == null) {
                    // 未命中入库任务时，路由到出库分配行；出库只在确认出库后同步，不在此处触发
                    if (outboundService.isOutboundTask(detail.getTaskNo())) {
                        outboundService.onTaskFeedback(detail.getTaskNo(), detail.getContainerCode(),
                                detail.getTaskStatus(), detail.getMessage(), true);
                        item.put("code", 0);
                        item.put("msg", "");
                    } else {
                        item.put("code", -1);
                        item.put("msg", "任务号不存在");
                        failedCount++;
                    }
                } else {
                    boolean completed = productionService.onTaskFeedback(detail.getTaskNo(),
                            detail.getContainerCode(), detail.getTaskStatus(), detail.getStackCode(),
                            detail.getMessage(), true);
                    if (completed) {
                        // 回执事务已提交，此处再触发同步；先乐观认领，避免与补偿任务重复投递
                        if (productionService.claimForSync(record.getId(),
                                productionService.syncStaleBefore()) > 0) {
                            WmsOrderSyncAttempt attempt =
                                    productionSyncService.syncInbound(record.getId(), true);
                            productionService.persistSyncResult(record.getId(), attempt);
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

    @IgnoreAuth
    @Operation(summary = "RMS-料箱位置更新")
    @PostMapping(value = "/container-location")
    public Map<String, Object> containerLocation(
            @RequestHeader(name = "X-RMS-Token", required = false) String token,
            @RequestBody WmsContainerLocationDTO dto) {
        if (!tokenValid(token)) {
            return failed("RMS 回调令牌无效");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int failedCount = 0;
        if (dto != null && dto.getDetails() != null) {
            for (WmsContainerLocationDTO.Detail detail : dto.getDetails()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("containerCode", detail.getContainerCode());
                int affected = productionService.onContainerLocation(detail.getContainerCode(), detail.getStackCode());
                item.put("code", affected > 0 ? 0 : -1);
                item.put("msg", affected > 0 ? "" : "容器编码不存在或未绑定");
                if (affected == 0) {
                    failedCount++;
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
