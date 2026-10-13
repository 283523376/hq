package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsMaterial;

import java.util.List;

/**
 * @Description: 物料主数据
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
public interface IWmsMaterialService extends JeecgService<WmsMaterial> {

    /**
     * 新增物料（编码查重）。
     */
    void addMaterial(WmsMaterial material);

    /**
     * 编辑物料（编码查重）。
     */
    void editMaterial(WmsMaterial material);

    /**
     * 停用物料。
     */
    void disableMaterial(String id);

    /**
     * 删除物料（逻辑删除）。
     */
    void removeMaterial(String id);

    /**
     * 批量删除物料（逻辑删除）。
     */
    void removeBatch(List<String> ids);

    /**
     * 判断物料编码是否已被占用。
     *
     * @param id 编辑时传入自身 id，用于排除自己
     */
    boolean checkCodeExists(String code, String id);

    /**
     * 回填分类名称/编码（非持久化字段，供列表与导出使用）。
     */
    void fillCategoryInfo(List<WmsMaterial> list);

    /**
     * 按分类编码取分类ID（导入用）。
     */
    String resolveCategoryIdByCode(String categoryCode);
}
