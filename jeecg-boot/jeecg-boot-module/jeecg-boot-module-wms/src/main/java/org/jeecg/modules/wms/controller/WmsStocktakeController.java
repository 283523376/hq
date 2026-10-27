package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.jeecg.modules.wms.service.impl.WmsStocktakeErpWriterImpl;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsStocktakeDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Description: 库存盘点（spec 2026-10-26 §5.3）。
 *
 * <p>盘点**只由 WMS 发起**（2026-10-26 用户澄清）：ERP→WMS 下发盘点任务、AGV 自动盘点
 * 均为后续事项；全部操作为管理端人工完成。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Tag(name = "WMS-库存盘点")
@RestController
@RequestMapping("/wms/stocktake")
@Slf4j
public class WmsStocktakeController {

    @Autowired
    private IWmsStocktakeService stocktakeService;

    @Autowired
    private WmsStocktakeErpWriterImpl stocktakeErpWriter;

    @Operation(summary = "盘点-分页列表（按类型过滤，两个子菜单复用）")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:stocktake:list")
    public Result<IPage<WmsStocktakeOrder>> list(@RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                 @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                 @RequestParam(name = "stocktakeType", required = false) String stocktakeType) {
        return Result.OK(stocktakeService.listPage(pageNo, pageSize, stocktakeType));
    }

    @Operation(summary = "盘点-详情")
    @GetMapping(value = "/queryById")
    @RequiresPermissions("wms:stocktake:list")
    public Result<WmsStocktakeOrder> queryById(@RequestParam("id") String id) {
        try {
            return Result.OK(stocktakeService.requireById(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "盘点-明细列表")
    @GetMapping(value = "/details")
    @RequiresPermissions("wms:stocktake:list")
    public Result<List<WmsStocktakeDetail>> details(@RequestParam("id") String id) {
        return Result.OK(stocktakeService.listDetails(id));
    }

    @AutoLog(value = "盘点-新建", operateType = 2)
    @Operation(summary = "盘点-新建（按库位快照账面）")
    @PostMapping(value = "/create")
    @RequiresPermissions("wms:stocktake:add")
    public Result<WmsStocktakeOrder> create(@RequestBody WmsStocktakeCreateDTO dto) {
        try {
            return Result.OK("创建成功", stocktakeService.create(dto, WmsUtils.currentUsername()));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-开始", operateType = 3)
    @Operation(summary = "盘点-开始（草稿→盘点中，冻结库位）")
    @PostMapping(value = "/start")
    @RequiresPermissions("wms:stocktake:add")
    public Result<String> start(@RequestParam("id") String id) {
        try {
            stocktakeService.start(id);
            return Result.OK("已开始盘点");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-录入实盘", operateType = 3)
    @Operation(summary = "盘点-录入实盘数量（留空=未录入，不当作 0）")
    @PostMapping(value = "/recordActual/{id}")
    @RequiresPermissions("wms:stocktake:record")
    public Result<String> recordActual(@org.springframework.web.bind.annotation.PathVariable("id") String id,
                                       @RequestBody List<WmsStocktakeDetailVO> actuals) {
        try {
            stocktakeService.recordActual(id, actuals);
            return Result.OK("已录入");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-差异确认", operateType = 3)
    @Operation(summary = "盘点-差异确认（超阈值自动转复核中）")
    @PostMapping(value = "/confirmDiff")
    @RequiresPermissions("wms:stocktake:confirm")
    public Result<String> confirmDiff(@RequestParam("id") String id) {
        try {
            stocktakeService.confirmDiff(id, WmsUtils.currentUsername());
            return Result.OK("已确认");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-复核", operateType = 3)
    @Operation(summary = "盘点-复核（复核人不得是发起人）")
    @PostMapping(value = "/review")
    @RequiresPermissions("wms:stocktake:review")
    public Result<String> review(@RequestParam("id") String id) {
        try {
            // 复核人一律取当前登录人，绝不接受前端自由文本（否则"复核人≠发起人"形同虚设）
            stocktakeService.review(id, WmsUtils.currentUsername());
            return Result.OK("复核通过");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-调整库存", operateType = 3)
    @Operation(summary = "盘点-按实盘调整库存（唯一合法的盘点改账路径）")
    @PostMapping(value = "/adjust")
    @RequiresPermissions("wms:stocktake:adjust")
    public Result<String> adjust(@RequestParam("id") String id) {
        try {
            stocktakeService.applyAdjustment(id, WmsUtils.currentUsername());
            return Result.OK("已调整");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-回写ERP", operateType = 3)
    @Operation(summary = "盘点-差异回写金蝶（盘盈 QTRK / 盘亏 QTCK，两侧独立）")
    @PostMapping(value = "/writeback")
    @RequiresPermissions("wms:stocktake:adjust")
    public Result<String> writeback(@RequestParam("id") String id) {
        try {
            WmsStocktakeOrder order = stocktakeService.requireById(id);
            WmsOrderSyncResult gain = stocktakeErpWriter.writeGain(order);
            WmsOrderSyncResult loss = stocktakeErpWriter.writeLoss(order);
            String gainMsg = gain.isSuccess() ? "盘盈回写成功" : "盘盈回写失败：" + gain.getErrorMessage();
            String lossMsg = loss.isSuccess() ? "盘亏回写成功" : "盘亏回写失败：" + loss.getErrorMessage();
            stocktakeService.markErpWriteback(id, gain.isSuccess() && loss.isSuccess(),
                    gain.isSuccess() ? "盘盈已回写" : gain.getErrorMessage());
            return Result.OK(gainMsg + "；" + lossMsg);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "盘点-归档", operateType = 3)
    @Operation(summary = "盘点-归档（终态）")
    @PostMapping(value = "/archive")
    @RequiresPermissions("wms:stocktake:archive")
    public Result<String> archive(@RequestParam("id") String id) {
        try {
            stocktakeService.archive(id);
            return Result.OK("已归档");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
