package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsLocationService extends IService<WmsLocation> {

    /**
     * 按闭区间范围批量生成库位。
     *
     * <p><b>幂等</b>：靠 {@code wms_location} 的两个唯一索引与 {@code INSERT IGNORE}，
     * 重跑同一范围是安全的 —— 已存在的被跳过并计入 {@link WmsLocationGenResultVO#getSkipped()}。
     *
     * <p><b>不做整体回滚</b>：某批失败即停止，异常文案里带上**已生成条数**与恢复方式。
     * 单次最多 5 万行，开一个大事务会把连接池拖死。
     *
     * @param dto 库区与范围
     * @return 实际生成数与跳过数
     */
    WmsLocationGenResultVO generate(WmsLocationGenDTO dto);
}