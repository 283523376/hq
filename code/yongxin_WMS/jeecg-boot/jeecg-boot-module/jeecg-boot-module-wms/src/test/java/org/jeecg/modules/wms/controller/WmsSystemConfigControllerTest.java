package org.jeecg.modules.wms.controller;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsConfigConflict;
import org.jeecg.modules.wms.vo.WmsConfigConflictVO;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * @Description: 系统配置接口测试（直接调控制器方法，不启 Spring）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
class WmsSystemConfigControllerTest {

    private static WmsSystemConfigController controller(IWmsSystemConfigService service) {
        WmsSystemConfigController c = new WmsSystemConfigController();
        WmsTestSupport.inject(c, "service", service);
        return c;
    }

    /** definitions 透传 service 结果。 */
    @Test
    void definitions_shouldReturnGroups() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        WmsConfigGroupVO g = new WmsConfigGroupVO();
        g.setCode("inventory");
        doReturn(Collections.singletonList(g)).when(service).definitions();

        Result<List<WmsConfigGroupVO>> result = controller(service).definitions();

        assertTrue(result.isSuccess());
        assertEquals("inventory", result.getResult().get(0).getCode());
    }

    /** 保存成功返回成功文案。 */
    @Test
    void save_shouldReturnOk() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        WmsConfigSaveDTO dto = new WmsConfigSaveDTO();
        dto.setGroup("inventory");

        Result<WmsConfigConflictVO> result = controller(service).save(dto);

        assertTrue(result.isSuccess());
        assertEquals("保存成功！", result.getMessage());
    }

    /** 冲突时：文案逐字 + 明细挂 result（两条断言同时成立，见 Review Focus 5）。 */
    @Test
    void save_shouldReturnFixedMessageWithConflicts() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        doThrow(new WmsConfigConflictException(Collections.singletonList(
                new WmsConfigConflict("inv.stock.min", "最低库存", "最低库存(100) 不能大于 安全库存(50)"))))
                .when(service).saveGroup(any());
        WmsConfigSaveDTO dto = new WmsConfigSaveDTO();
        dto.setGroup("inventory");

        Result<WmsConfigConflictVO> result = controller(service).save(dto);

        assertFalse(result.isSuccess());
        assertEquals("配置项存在冲突，请检查", result.getMessage(), "必须逐字等于蓝图原文");
        assertEquals("inv.stock.min", result.getResult().getConflicts().get(0).getKey());
    }

    /** 回滚失败时提示文案原样透出、且与冲突文案不同。 */
    @Test
    void rollback_shouldSurfaceMessage() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        doThrow(new RuntimeException("当前分组没有可回滚的历史版本")).when(service).rollback(any());
        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("device");

        Result<?> result = controller(service).rollback(dto);

        assertFalse(result.isSuccess());
        assertEquals("当前分组没有可回滚的历史版本", result.getMessage());
    }

    /** 回滚成功返回成功文案。 */
    @Test
    void rollback_shouldReturnOk() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("device");

        Result<?> result = controller(service).rollback(dto);

        assertTrue(result.isSuccess());
        assertEquals("回滚成功！", result.getMessage());
    }

    /** 历史查询透传。 */
    @Test
    void history_shouldReturnRows() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);
        WmsSystemConfigHistory row = new WmsSystemConfigHistory();
        row.setChangeType("SAVE");
        doReturn(Arrays.asList(row)).when(service).history("inv.stock.min");

        Result<List<WmsSystemConfigHistory>> result = controller(service).history("inv.stock.min");

        assertTrue(result.isSuccess());
        assertEquals("SAVE", result.getResult().get(0).getChangeType());
    }

    /** list 缺少 group 参数应被拒，不落进 service 的 IllegalArgumentException。 */
    @Test
    void list_shouldRejectBlankGroup() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);

        Result<?> result = controller(service).listByGroup("  ");

        assertFalse(result.isSuccess());
    }

    /** history 缺少 key 参数应被拒。 */
    @Test
    void history_shouldRejectBlankKey() {
        IWmsSystemConfigService service = mock(IWmsSystemConfigService.class);

        Result<?> result = controller(service).history("");

        assertFalse(result.isSuccess());
    }
}
