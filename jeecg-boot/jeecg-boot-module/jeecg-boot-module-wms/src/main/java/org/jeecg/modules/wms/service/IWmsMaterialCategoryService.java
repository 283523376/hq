package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;

import java.util.List;

/**
 * @Description: 物料分类
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
public interface IWmsMaterialCategoryService extends JeecgService<WmsMaterialCategory> {

    /**
     * 全量分类树（含 children）。
     */
    List<SelectTreeModel> queryTreeList();

    /**
     * 新增分类（编码查重 + 回写父节点 has_child）。
     */
    void addCategory(WmsMaterialCategory category);

    /**
     * 编辑分类（编码查重）。
     */
    void editCategory(WmsMaterialCategory category);

    /**
     * 停用分类。
     */
    void disableCategory(String id);

    /**
     * 删除分类；分类下存在物料或子分类时抛出异常。
     */
    void removeCategory(String id);

    /**
     * 判断分类编码是否已被占用。
     *
     * @param id 编辑时传入自身 id，用于排除自己
     */
    boolean checkCodeExists(String code, String id);

    /**
     * 分类下是否存在物料。
     */
    boolean hasMaterial(String categoryId);

    /**
     * 按分类编码取分类ID。
     */
    String queryIdByCode(String categoryCode);
}
