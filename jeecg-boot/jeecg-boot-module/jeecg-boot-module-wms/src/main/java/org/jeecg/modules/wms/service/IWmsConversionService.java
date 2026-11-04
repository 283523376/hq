package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.dto.WmsConversionCreateDTO;
import org.jeecg.modules.wms.entity.WmsConversionOrder;

/**
 * 改制单（大改小，V2.4 spec §3.1）。
 *
 * <p>投入原材料<strong>不入 WMS 库存</strong>（登记即台账）；完成时经
 * {@link IWmsProductionInboundService#submit} 生成生产入库单，产出按自有库存入账。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
public interface IWmsConversionService extends JeecgService<WmsConversionOrder> {

    /**
     * 建改制单：校验生产订单已同步且已下达、投入/产出物料存在、产出物料与生产订单成品一致、
     * 数量合法且不超过计划量；状态显式置 DRAFT，并写入投入/产出两条明细。
     *
     * @throws RuntimeException 校验失败时携带面向用户的中文文案
     */
    WmsConversionOrder create(WmsConversionCreateDTO dto, String operator);

    /**
     * 完成改制（DRAFT→DONE，唯一入口）：生成生产入库单（产出物料=生产订单成品、
     * 数量=产出数量、备注=「改制单:改制单号」）。重复完成抛异常且不再生成第二张入库单。
     *
     * @throws RuntimeException 非法状态（含重复完成）或生产入库校验失败；失败时整单回滚回 DRAFT
     */
    void complete(String id, String operator);

    /**
     * 取消改制单（DRAFT→CANCELLED）。投入不入库，取消无库存动作。
     */
    void cancel(String id, String operator);

    /**
     * 分页查询（单号/生产订单号模糊，状态精确）。
     */
    IPage<WmsConversionOrder> listPage(int pageNo, int pageSize, String conversionNo,
                                       String productionOrderNo, String status);
}
