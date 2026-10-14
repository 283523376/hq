package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsSupplier;

import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsSupplierService extends JeecgService<WmsSupplier> {

    /** 新增供应商（编码 trim + 查重）。 */
    void addSupplier(WmsSupplier supplier);

    /** 编辑供应商（编码 trim + 查重）。 */
    void editSupplier(WmsSupplier supplier);

    /** 停用供应商。 */
    void disableSupplier(String id);

    /** 删除供应商（逻辑删除）。 */
    void removeSupplier(String id);

    /** 批量删除供应商（逻辑删除）。 */
    void removeBatch(List<String> ids);

    /** 判断供应商编码是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkCodeExists(String code, String id);

    /** 取全部启用中的供应商，供订单页下拉选择。 */
    List<WmsSupplier> listAllEnabled();
}
