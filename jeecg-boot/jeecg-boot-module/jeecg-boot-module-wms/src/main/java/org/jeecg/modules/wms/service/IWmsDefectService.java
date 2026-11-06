package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.wms.dto.WmsDefectInboundDTO;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.vo.WmsDefectLedgerVO;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;

/**
 * @Description: 不良品处置（XT-001）——查询废品仓台账、执行报废出库。
 *
 * <p>RK-005（2026-11-04）扩展两类不良来源：生产不良（入自有废品仓）与
 * 受托原材料不良（扣受托库存，处置二选一：入废品仓不转自有 / 退货生成受托加工材料退料单）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
public interface IWmsDefectService {

    /**
     * 不良品台账：只返回**废品仓**的当前库存行（实时查询，不存快照）。
     *
     * <p>支持按库存类型筛选：默认 {@code OWNED}（自有），显式传 {@code CONSIGNED} 才看受托行
     * ——受托不良入废品仓后仍记受托所有权，不筛出来就永远无法在本模块内处置。
     *
     * @param materialKeyword 物料编码/名称模糊
     * @param batchNo         批次模糊
     * @param locationCode    库位编码模糊
     * @param ownershipType   库存类型：OWNED（默认）/ CONSIGNED
     * @param consignorId     受托方ID（仅 CONSIGNED 时生效）
     */
    IPage<WmsDefectLedgerVO> pageLedger(int pageNo, int pageSize, String materialKeyword,
                                        String batchNo, String locationCode,
                                        String ownershipType, String consignorId);

    /**
     * 报废出库：扣减库存 + 建「报废出库」单（一步到位已完成）+ 审计留痕。
     *
     * @throws RuntimeException 数量非法 / 超可用量 / 非废品仓 / 并发被抢
     */
    WmsScrapResultVO scrap(WmsScrapDTO dto);

    /**
     * 不良品入库登记（RK-005 两类来源统一入口）。
     *
     * <ul>
     *   <li>PRODUCTION：校验生产入库单存在；SCRAP → 写自有废品仓库存并记录（已处置）；
     *       RETURN 不支持（退货仅适用受托）；</li>
     *   <li>CONSIGNED：必须能按来源单号反查受托入库单与受托方（供应商）；
     *       SCRAP → 扣减**受托库存**（物料+批次+受托方匹配，不足拒绝）后入废品仓
     *       （仍记 CONSIGNED + 受托方，不转自有）；RETURN → 只登记记录（待处理），
     *       不动库存。</li>
     * </ul>
     *
     * @throws RuntimeException 来源/处置非法、单据或物料不存在、受托库存不足
     */
    void recordInbound(WmsDefectInboundDTO dto, String operator);

    /**
     * 受托不良退货：反查受托方 → 查重/聚合 → 回写金蝶受托加工材料退料单 → Submit →
     * 回存 {@code return_bill_no} 并置已退货。
     *
     * <p>同一来源单的多条 RETURN 记录聚合共用一张退货单：物料+批次相同的聚合成一条分录
     * （数量合计）开一张单；后续对同来源单再退货时若已有 {@code return_bill_no} 直接沿用。
     *
     * @return 更新后的不良品记录（含退货单号与状态）
     * @throws RuntimeException 记录不存在 / 非受托或非 RETURN / 状态不允许 /
     *                          受托方反查失败 / 金蝶未启用或回写失败（重试后仍失败）
     */
    WmsDefectRecord returnToConsignor(String recordId, String operator);

    /**
     * 不良品记录分页：按来源/处置/状态/物料筛选（物料为任意连续子串模糊）。
     */
    IPage<WmsDefectRecord> listPage(int pageNo, int pageSize, WmsDefectRecord query);
}
