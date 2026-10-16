package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;

import java.util.List;

/**
 * @Description: 库区
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsWarehouseService extends IService<WmsWarehouse> {

    /** 两层库区树：库区类型 → 库区。 */
    List<SelectTreeModel> queryTreeList();

    /** 库区编码唯一性校验（蓝图步骤 3）。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    void addWarehouse(WmsWarehouse warehouse);

    void editWarehouse(WmsWarehouse warehouse);

    void disableWarehouse(String id);

    /**
     * **物理删除**；库区下存在库位或已绑定设备时拒绝。
     *
     * <p>不是逻辑删除：`uk_wms_warehouse_code` 只建在 `warehouse_code` 上、不含 `del_flag`，
     * 逻辑删除会让已删除的编码永久不可复用，且查重与保存的结论互相矛盾
     * （详见 `WmsWarehouse` 的 `delFlag` 字段注释与 `V3.9.5_6` 迁移脚本）。
     */
    void removeWarehouse(String id);

    /**
     * 批量删除库区。整体在一个事务里，循环复用单条守卫，任一条被拒则整体回滚。
     */
    void removeBatch(List<String> ids);

    /**
     * 删除预检：一次报全库位与设备两 blocker（只读，供前端禁止弹窗）。
     */
    WmsDeleteCheckVO checkDelete(String id);

    /** 批量删除预检：逐条检查，列出不可删明细（编码 + 原因）。 */
    WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids);

    /** 某库区类型下的全部库区 id，供库位列表按类型过滤。 */
    List<String> queryIdsByType(String warehouseType);

    /** 取启用中的库区，不存在或已停用则抛异常。批量生成与设备绑定共用此守卫。 */
    WmsWarehouse requireEnabledWarehouse(String warehouseId);
}