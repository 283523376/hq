package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
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

    /** 库位编码唯一性校验（蓝图异常①：库位编码重复 → 提示并拒绝保存）。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    /**
     * 手工补录单个库位（补网格空洞）。
     *
     * <p>编码<b>由后端按规则生成</b>，不接受前端传入 —— 否则编码与坐标会脱节，
     * 而整个幂等与唯一性设计都建立在「编码是坐标的函数」之上。
     */
    void addLocation(WmsLocation location);

    /**
     * 编辑库位。
     *
     * <p>坐标不可改（改了编码就与坐标脱节）；可变字段只有承重与状态，
     * 故与 {@link #updateAttr} 共用同一守卫。
     */
    void editLocation(WmsLocation location);

    /** 更新承重 / 状态。库位为「占用」时拒绝（约束②）。 */
    void updateAttr(WmsLocationAttrDTO dto);

    /**
     * 释放库位：占用 → 空闲。
     *
     * <p>存在的理由：若「占用」既表示有物料、又阻断一切状态修改，被误置为占用的库位
     * 将永远无法人工恢复，约束②会变成锁死缺陷。本动作语义明确 —— 人工确认该库位已清空 ——
     * 且不是绕过约束的通用后门（改承重仍被拒）。
     */
    void release(String id);

    /**
     * **物理删除**；仅「空闲」库位可删。
     *
     * <p>不是逻辑删除：`uk_wms_location_grid` 只建在
     * `(warehouse_id, row_no, col_no, layer_no)` 上、不含 `del_flag`，逻辑删除会让
     * 「删掉几个库位后重跑生成」被 `INSERT IGNORE` 静默跳过却不恢复 ——
     * 直接破坏本接口承诺的幂等续做（详见 `WmsLocation` 的 `delFlag` 字段注释）。
     */
    void removeLocation(String id);

    /**
     * 批量删除库位。整体在一个事务里，循环复用单条守卫，任一条被拒则整体回滚。
     */
    void removeBatch(java.util.List<String> ids);

    /**
     * 删除预检：非空闲（占用/故障）库位不可删（只读，供前端禁止弹窗）。
     */
    WmsDeleteCheckVO checkDelete(String id);

    /** 批量删除预检：逐条检查，列出不可删明细（编码 + 原因）。 */
    WmsBatchDeleteCheckVO checkDeleteBatch(java.util.List<String> ids);

    /** 位于库区当前维度之外的库位数。缩维后不为 0，用于提示而非拦截。 */
    long countOutOfEnvelope(String warehouseId);
}