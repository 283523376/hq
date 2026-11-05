package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialGroupMap;
import org.jeecg.modules.wms.service.IWmsMaterialGroupMapService;
import org.jeecg.modules.wms.vo.WmsMaterialGroupMapSaveResultVO;
import org.jeecg.modules.wms.vo.WmsUnmappedGroupVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @Description: 金蝶物料分组 → WMS 分类类型映射（方案 A：独立映射表）。
 *
 * <p>本期只做接口，前端页面下期补；菜单与 4 个按钮权限已随
 * {@code V3.9.5_51__wms_material_group_map.sql} 建好。
 *
 * <p>写端点（saveBatch / delete）一律 {@code @RequiresPermissions} + {@code @WmsAudit}，
 * 且显式 catch → {@code Result.error}（不经全局异常处理器加前缀）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Tag(name = "WMS-物料分组映射")
@RestController
@RequestMapping("/wms/materialGroupMap")
@Slf4j
public class WmsMaterialGroupMapController extends JeecgController<WmsMaterialGroupMap, IWmsMaterialGroupMapService> {

    /**
     * 分页列表（支持分组编码/名称任意连续子串模糊，分类类型等值）。
     */
    @Operation(summary = "物料分组映射-分页列表查询")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:materialGroupMap:list")
    public Result<IPage<WmsMaterialGroupMap>> queryPageList(
            WmsMaterialGroupMap wmsMaterialGroupMap,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            HttpServletRequest req) {
        // 查询窗口模糊匹配：QueryGenerator 对 String 拼的是等值，文本字段置空后手写 like，
        // 让「任意连续子串」都能命中（2026-11-02 全项目口径）
        String groupNumberKeyword = wmsMaterialGroupMap.getGroupNumber();
        String groupNameKeyword = wmsMaterialGroupMap.getGroupName();
        wmsMaterialGroupMap.setGroupNumber(null);
        wmsMaterialGroupMap.setGroupName(null);
        QueryWrapper<WmsMaterialGroupMap> queryWrapper =
                QueryGenerator.initQueryWrapper(wmsMaterialGroupMap, req.getParameterMap());
        queryWrapper.like(oConvertUtils.isNotEmpty(groupNumberKeyword), "group_number",
                groupNumberKeyword == null ? null : groupNumberKeyword.trim());
        queryWrapper.like(oConvertUtils.isNotEmpty(groupNameKeyword), "group_name",
                groupNameKeyword == null ? null : groupNameKeyword.trim());
        // 全局关键词：一次跨字段模糊，与单字段框是 AND 关系；OR 必须包在 and(...) 里
        if (oConvertUtils.isNotEmpty(keyword)) {
            final String kw = keyword.trim();
            queryWrapper.and(w -> w.like("group_number", kw)
                    .or().like("group_name", kw)
                    .or().like("category_type", kw)
                    .or().like("remark", kw));
        }
        queryWrapper.orderByAsc("group_number");
        Page<WmsMaterialGroupMap> page = new Page<>(pageNo, pageSize);
        return Result.OK(service.page(page, queryWrapper));
    }

    /**
     * 全部映射（给下拉/导出用）。
     */
    @Operation(summary = "物料分组映射-全部列表")
    @GetMapping(value = "/listAll")
    @RequiresPermissions("wms:materialGroupMap:list")
    public Result<List<WmsMaterialGroupMap>> listAll() {
        return Result.OK(service.listAll());
    }

    /**
     * 批量 upsert：按分组编码幂等；分类类型非法时整批拒绝。
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_CREATE,
            bizNoSpEL = "'" + WmsConstants.AUDIT_BIZ_NO_MATERIAL_GROUP_MAP + "'")
    @Operation(summary = "物料分组映射-批量保存（按分组编码 upsert）")
    @PostMapping(value = "/saveBatch")
    // upsert 同时覆盖新增与编辑：只给 create 会让「只管维护既有映射」的角色无法保存
    @RequiresPermissions(value = {"wms:materialGroupMap:create", "wms:materialGroupMap:edit"},
            logical = Logical.OR)
    public Result<WmsMaterialGroupMapSaveResultVO> saveBatch(
            @RequestBody List<WmsMaterialGroupMap> maps) {
        try {
            return Result.OK("保存成功！", service.saveBatchUpsert(maps));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除映射（逻辑删除）。
     *
     * <p>只删映射本身，**不回写**已同步的分类类型：分类上的既有值保持原样，
     * 下次分组同步因映射不到而不动它（同步只在有映射时才覆盖）。
     */
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_MASTER_DATA, action = WmsConstants.AUDIT_ACTION_DELETE,
            bizNoSpEL = "#id")
    @Operation(summary = "物料分组映射-删除")
    @DeleteMapping(value = "/{id}")
    @RequiresPermissions("wms:materialGroupMap:delete")
    public Result<?> delete(@PathVariable(name = "id") String id) {
        try {
            if (oConvertUtils.isEmpty(id)) {
                return Result.error("参数不识别！");
            }
            service.removeById(id);
            return Result.OK("删除成功！");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 已同步但尚未映射的分组（供业务认领），按物料数从多到少排序。
     */
    @Operation(summary = "物料分组映射-未映射分组")
    @GetMapping(value = "/unmappedGroups")
    @RequiresPermissions("wms:materialGroupMap:list")
    public Result<List<WmsUnmappedGroupVO>> unmappedGroups() {
        return Result.OK(service.listUnmappedGroups());
    }
}
