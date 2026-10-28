package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.entity.WmsWarningRule;

/**
 * @Description: 库存预警规则（KC-001）。
 *
 * <p>保存前一律走校验，非法即抛可读中文原因（spec 2026-10-28 第 4 节）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
public interface IWmsWarningRuleService extends IService<WmsWarningRule> {

    /** 新建规则（含校验）。 */
    WmsWarningRule createRule(WmsWarningRule rule);

    /** 编辑规则（含校验）。 */
    WmsWarningRule updateRule(WmsWarningRule rule);

    /** 逻辑删除规则。历史预警保留规则快照，不受影响。 */
    void removeRule(String id);
}
