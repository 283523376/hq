package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.jeecg.modules.wms.vo.WmsSyncAggregateVO;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 库存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {

    /**
     * 聚合上架：同 (material_id, location_id, batch_no, container_code, ownership_type, consignor_id)
     * 存在则数量累加，否则插入新行。
     *
     * <p>所有权两列必须入 SQL（V2.4 六列唯一键）：漏写会让受托到货落到 DB 默认值 OWNED，
     * 被并入同四元组的自有行。实体上未设置时按 {@code OWNED} / 空串兜底，兼容旧调用方。
     *
     * @return 影响行数（插入=1，累加=2）
     */
    int upsertInventory(WmsInventory inv);

    /**
     * 出库可用库存（按 FIFO 排序）：quantity - locked_qty &gt; 0。
     *
     * <p>**只返回自有（OWNED）库存**：受托材料不得用于自有生产领料/销售出库
     * （spec 2026-11-04 §3.1）。SQL 用正向匹配 {@code ownership_type = 'OWNED'}，
     * 不写 {@code <> 'CONSIGNED'}，否则 ownership_type 为 NULL 的存量行会被漏掉。
     *
     * @param warehouseType   立库/敏捷仓
     * @param requireContainer 是否只取已绑定料箱的行（敏捷仓）
     */
    List<WmsInventoryAvailableVO> selectAvailable(@Param("materialId") String materialId,
                                                  @Param("batchNo") String batchNo,
                                                  @Param("warehouseType") String warehouseType,
                                                  @Param("requireContainer") boolean requireContainer,
                                                  @Param("limit") int limit);

    /** 锁定库存：可用量不足时影响 0 行。 */
    int lockInventory(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 指定库区的可用库存（FIFO）：调拨按「源仓」取数，避免同类型多仓混用。
     *
     * <p>同样**只返回自有（OWNED）库存**（spec 2026-11-04 §3.1）。
     *
     * @param warehouseId      源库区ID
     * @param requireContainer 是否只取已绑定料箱的行（敏捷仓）
     */
    List<WmsInventoryAvailableVO> selectAvailableByWarehouse(@Param("materialId") String materialId,
                                                         @Param("batchNo") String batchNo,
                                                         @Param("warehouseId") String warehouseId,
                                                         @Param("containerCode") String containerCode,
                                                             @Param("requireContainer") boolean requireContainer,
                                                             @Param("limit") int limit);

    /** 释放锁定（关单/复核差额）。 */
    int releaseLocked(@Param("id") String id, @Param("qty") BigDecimal qty);

    /** 扣减库存并释放锁定：锁定不足或库存不足时影响 0 行。 */
    int deductLocked(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 直接扣减库存（不涉及锁定）——报废出库用。
     *
     * @return 影响行数；0 表示库存已被并发改动或不足，调用方应回滚
     */
    int deductQuantity(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 整行覆盖库存数量并显式刷新 update_time / update_by——盘点调整用（SB-001 前置修复）。
     *
     * <p>「今日变动」对账按 {@code update_time} 取数；本项目无 MyBatis-Plus 自动填充，
     * 走通用 {@code updateById} 审计列不会变，对账会漏掉全部盘点调整。
     * 盘点是覆盖不是增减，故不带数量余量条件。
     */
    int touchQuantity(@Param("id") String id, @Param("qty") BigDecimal qty, @Param("updateBy") String updateBy);

    /**
     * 库存台账分页（KC-004）。
     *
     * <p>排序按 `wms_inventory` 唯一键的六列（V2.4 起含 ownership_type / consignor_id），
     * 与 {@link #selectLedgerCursor} 完全一致，这样页面看到的顺序和导出文件里的一致；
     * 少排所有权两列时「同四元组、不同受托方」的行会并列，分页边界不稳定。
     */
    IPage<WmsInventoryLedgerVO> selectLedgerPage(Page<WmsInventoryLedgerVO> page,
                                                 @Param("q") WmsInventoryLedgerQueryDTO q);

    /**
     * 库存台账游标分批（导出专用）。
     *
     * <p>`lastXxx` 全部为 null 时取第一批；否则取严格大于该六元组的下一批。
     * 六列是唯一键 `uk_wms_inv_mat_loc_batch_container`（V2.4 起含 ownership_type / consignor_id），
     * 所以游标不会重复或遗漏——只按前四列比较会让「同四元组、不同受托方」的行被跳过。
     */
    List<WmsInventoryLedgerVO> selectLedgerCursor(@Param("q") WmsInventoryLedgerQueryDTO q,
                                                  @Param("lastMaterialId") String lastMaterialId,
                                                  @Param("lastLocationId") String lastLocationId,
                                                  @Param("lastBatchNo") String lastBatchNo,
                                                  @Param("lastContainerCode") String lastContainerCode,
                                                  @Param("lastOwnershipType") String lastOwnershipType,
                                                  @Param("lastConsignorId") String lastConsignorId,
                                                  @Param("limit") int limit);

    /**
     * SB-001 库存对账聚合：按 仓库+物料+批次 汇总（spec 2026-11-03 §2.3）。
     *
     * <p>仓库键 = COALESCE(erp_stock_code, warehouse_code)，物料键 = COALESCE(kingdee_code, '')；
     * 空批归一为空串；库位/容器聚合掉（101 未启用仓位管理）。lockedQty 仅附带展示，不参与比对。
     *
     * @param windowStart    「今日变动」起点；null 即取全量
     * @param warehouseCodes 指定仓库范围；null 即不限（注意：空集合会拼出 `IN ()`，调用方传 null 或非空）
     * @param ownershipType  库存类型：正常对账传 {@code OWNED}；null 时 SQL 同样兜底为 OWNED
     *                       （受托库存不参与金蝶即时库存对账，spec 2026-11-04 §3.1）
     */
    List<WmsSyncAggregateVO> selectReconcileAggregate(@Param("windowStart") Date windowStart,
                                                      @Param("warehouseCodes") List<String> warehouseCodes,
                                                      @Param("ownershipType") String ownershipType);

    /**
     * SB-001 由差异发起盘点：反查 仓库+物料(ERP 编码)+批次 命中的库位（spec 2026-11-03 §2.8）。
     *
     * <p>仓库键与对账聚合同口径（{@code COALESCE(erp_stock_code, warehouse_code)}），物料按
     * {@code wms_material.kingdee_code} 匹配；只取 {@code quantity > 0} 或 {@code locked_qty > 0}
     * 的行——账面已归零的物料没有可盘的实物，调用方应拒绝发起。
     *
     * <p>与对账聚合一样**不**过滤主数据 {@code deleted_at}：要的是「库存行现在挂在哪个仓库上」。
     * 但**只取自有（OWNED）库存**——对账域本身不含受托，反查库位必须同口径。
     *
     * @param warehouseCode 差异行的仓库键（ERP 仓库编码，或与金蝶 1:1 的 WMS 编码）
     * @param materialCode  差异行的物料编码（ERP/kingdee_code）
     * @param batchNo       批次号；差异行空批次已归一为空串，调用方传空串表示只匹配无批次行
     * @return 去重后的库位ID；无命中返回空列表
     */
    List<String> selectLocationIdsForDiff(@Param("warehouseCode") String warehouseCode,
                                          @Param("materialCode") String materialCode,
                                          @Param("batchNo") String batchNo);
}
