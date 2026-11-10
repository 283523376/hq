package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;

import java.util.List;

/**
 * @Description: 客户档案（受托方，V2.4）
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
public interface IWmsCustomerService extends JeecgService<WmsCustomer> {

    /** 新增客户（编码 trim + 查重）。 */
    void addCustomer(WmsCustomer customer);

    /** 编辑客户（编码 trim + 查重）。 */
    void editCustomer(WmsCustomer customer);

    /** 停用客户。 */
    void disableCustomer(String id);

    /** 删除客户（逻辑删除）；存在受托库存或受托入库单时拒绝。 */
    void removeCustomer(String id);

    /**
     * 批量删除客户（逻辑删除）。整体在一个事务里，循环复用单条守卫，
     * 任一条被拒则整体回滚。
     */
    void removeBatch(List<String> ids);

    /**
     * 删除预检：只读收集全部不可删原因（一次报全，供前端禁止弹窗）。
     *
     * <p>引用来源：{@code wms_inventory.consignor_id}（委托库存行）与
     * {@code wms_inbound_order.consignor_id}（受托入库单）；
     * 入库单经 {@code @TableLogic} 自动只统计未删除行。
     */
    WmsDeleteCheckVO checkDelete(String id);

    /** 批量删除预检：逐条检查，列出不可删明细（编码 + 原因）。 */
    WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids);

    /** 判断客户编码是否已被占用；编辑时传 id 以排除自身。 */
    boolean checkCodeExists(String code, String id);

    /** 取全部启用中的客户，供受托方下拉选择。 */
    List<WmsCustomer> listAllEnabled();
}
