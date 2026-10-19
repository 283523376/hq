package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 采购入库（RK-001）。收料/分配/上架/作废/同步随计划任务逐步挂载。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-采购入库")
@RestController
@RequestMapping("/wms/inbound")
@Slf4j
public class WmsInboundController extends JeecgController<WmsInboundOrder, IWmsInboundService> {

    @Operation(summary = "入库-扫码收料（生成收料通知单）")
    @PostMapping(value = "/receive")
    public Result<WmsInboundOrder> receive(@RequestBody WmsReceiveDTO dto) {
        try {
            return Result.OK("收料成功", service.receive(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
