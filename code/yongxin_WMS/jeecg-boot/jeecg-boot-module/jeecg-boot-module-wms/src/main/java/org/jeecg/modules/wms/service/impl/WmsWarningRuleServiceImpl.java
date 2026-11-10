package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsWarningRule;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.mapper.WmsWarningRuleMapper;
import org.jeecg.modules.wms.service.IWmsWarningRuleService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * @Description: 库存预警规则校验与保存。
 *
 * <p>校验顺序照 spec 2026-10-28 第 4 节；命中即抛 {@link RuntimeException}，
 * 由控制器 `try/catch → Result.error` 逐字透出（不依赖全局异常处理器加前缀）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsWarningRuleServiceImpl extends ServiceImpl<WmsWarningRuleMapper, WmsWarningRule>
        implements IWmsWarningRuleService {

    private static final int NAME_MAX_LENGTH = 64;
    private static final int REMARK_MAX_LENGTH = 255;
    private static final int THRESHOLD_SCALE = 3;

    private final WmsMaterialMapper materialMapper;
    private final WmsMaterialCategoryMapper categoryMapper;
    private final WmsWarehouseMapper warehouseMapper;

    public WmsWarningRuleServiceImpl(WmsMaterialMapper materialMapper,
                                     WmsMaterialCategoryMapper categoryMapper,
                                     WmsWarehouseMapper warehouseMapper) {
        this.materialMapper = materialMapper;
        this.categoryMapper = categoryMapper;
        this.warehouseMapper = warehouseMapper;
    }

    @Override
    public WmsWarningRule createRule(WmsWarningRule rule) {
        validate(rule, null);
        rule.setEnabled(rule.getEnabled() == null ? 1 : rule.getEnabled());
        save(rule);
        return rule;
    }

    @Override
    public WmsWarningRule updateRule(WmsWarningRule rule) {
        if (!StringUtils.hasText(rule.getId())) {
            throw new RuntimeException("规则ID不能为空");
        }
        WmsWarningRule existing = getById(rule.getId());
        if (existing == null) {
            throw new RuntimeException("规则不存在或已删除");
        }
        validate(rule, rule.getId());
        rule.setEnabled(rule.getEnabled() == null ? existing.getEnabled() : rule.getEnabled());
        baseMapper.updateById(rule);
        // 物料/分类/库区在编辑时可能被清空，必须显式落 NULL（MyBatis-Plus 默认忽略 null）
        clearNullableColumns(rule);
        return rule;
    }

    @Override
    public void removeRule(String id) {
        WmsWarningRule existing = id == null ? null : getById(id);
        if (existing == null) {
            throw new RuntimeException("规则不存在或已删除");
        }
        removeById(id);
    }

    /**
     * 显式把可空列写回 NULL。
     *
     * <p>`updateById` 会跳过 null 字段，切作用域（如 MATERIAL → ALL）时必须单独 UPDATE，
     * 否则旧的 material_id 会残留，导致规则同时命中两个作用域。
     */
    private void clearNullableColumns(WmsWarningRule rule) {
        baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WmsWarningRule>()
                .eq(WmsWarningRule::getId, rule.getId())
                .set(rule.getMaterialId() == null, WmsWarningRule::getMaterialId, null)
                .set(rule.getCategoryId() == null, WmsWarningRule::getCategoryId, null)
                .set(rule.getWarehouseId() == null, WmsWarningRule::getWarehouseId, null)
                .set(rule.getThresholdQty() == null, WmsWarningRule::getThresholdQty, null)
                .set(rule.getOverdueDays() == null, WmsWarningRule::getOverdueDays, null));
    }

    /** 校验（非法一律拒绝保存）。 */
    void validate(WmsWarningRule rule, String selfId) {
        if (rule == null) {
            throw new RuntimeException("规则不能为空");
        }
        String name = rule.getRuleName() == null ? "" : rule.getRuleName().trim();
        if (name.isEmpty()) {
            throw new RuntimeException("规则名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new RuntimeException("规则名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        rule.setRuleName(name);

        if (rule.getRemark() != null && rule.getRemark().length() > REMARK_MAX_LENGTH) {
            throw new RuntimeException("备注不能超过 " + REMARK_MAX_LENGTH + " 个字符");
        }

        boolean lowStock = WmsConstants.WARNING_TYPE_LOW_STOCK.equals(rule.getRuleType());
        boolean overdue = WmsConstants.WARNING_TYPE_OVERDUE.equals(rule.getRuleType());
        if (!lowStock && !overdue) {
            throw new RuntimeException("规则类型必须是 LOW_STOCK（库存下限）或 OVERDUE（存放超期）");
        }

        boolean scopeMaterial = WmsConstants.WARNING_SCOPE_MATERIAL.equals(rule.getScopeType());
        boolean scopeCategory = WmsConstants.WARNING_SCOPE_CATEGORY.equals(rule.getScopeType());
        boolean scopeAll = WmsConstants.WARNING_SCOPE_ALL.equals(rule.getScopeType());
        if (!scopeMaterial && !scopeCategory && !scopeAll) {
            throw new RuntimeException("作用域必须是 MATERIAL（物料）、CATEGORY（分类）或 ALL（全部）");
        }

        // LOW_STOCK：阈值必填且 > 0，超期天数必须为空
        if (lowStock) {
            BigDecimal threshold = rule.getThresholdQty();
            if (threshold == null) {
                throw new RuntimeException("库存下限规则必须填写下限数量");
            }
            if (threshold.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("下限数量必须大于 0");
            }
            if (threshold.stripTrailingZeros().scale() > THRESHOLD_SCALE) {
                throw new RuntimeException("下限数量最多保留 " + THRESHOLD_SCALE + " 位小数");
            }
            if (rule.getOverdueDays() != null) {
                throw new RuntimeException("库存下限规则不允许填写超期天数，请改用存放超期规则");
            }
        }

        // OVERDUE：天数必填且 1..3650，下限必须为空
        if (overdue) {
            Integer days = rule.getOverdueDays();
            if (days == null) {
                throw new RuntimeException("存放超期规则必须填写超期天数");
            }
            if (days < 1 || days > WmsConstants.WARNING_OVERDUE_MAX_DAYS) {
                throw new RuntimeException("超期天数必须在 1 ~ "
                        + WmsConstants.WARNING_OVERDUE_MAX_DAYS + " 天之间");
            }
            if (rule.getThresholdQty() != null) {
                throw new RuntimeException("存放超期规则不允许填写下限数量，请改用库存下限规则");
            }
        }

        // 作用域与目标必须匹配
        if (scopeMaterial) {
            if (!StringUtils.hasText(rule.getMaterialId())) {
                throw new RuntimeException("作用域为「物料」时必须选择物料");
            }
            if (materialMapper.selectById(rule.getMaterialId()) == null) {
                throw new RuntimeException("所选物料不存在或已删除");
            }
            rule.setCategoryId(null);
        } else if (scopeCategory) {
            if (!StringUtils.hasText(rule.getCategoryId())) {
                throw new RuntimeException("作用域为「分类」时必须选择物料分类");
            }
            if (categoryMapper.selectById(rule.getCategoryId()) == null) {
                throw new RuntimeException("所选物料分类不存在或已删除");
            }
            rule.setMaterialId(null);
        } else {
            rule.setMaterialId(null);
            rule.setCategoryId(null);
        }

        if (StringUtils.hasText(rule.getWarehouseId())
                && warehouseMapper.selectById(rule.getWarehouseId()) == null) {
            throw new RuntimeException("所选库区不存在或已删除");
        }
        if (!StringUtils.hasText(rule.getWarehouseId())) {
            rule.setWarehouseId(null);
        }

        // 名称未删除内唯一
        Long sameName = baseMapper.selectCount(new LambdaQueryWrapper<WmsWarningRule>()
                .eq(WmsWarningRule::getRuleName, rule.getRuleName())
                .ne(selfId != null, WmsWarningRule::getId, selfId));
        if (sameName != null && sameName > 0) {
            throw new RuntimeException("规则名称「" + rule.getRuleName() + "」已存在");
        }

        // 同类型 + 同作用域目标 + 同库区 未删除内唯一
        LambdaQueryWrapper<WmsWarningRule> dup = new LambdaQueryWrapper<WmsWarningRule>()
                .eq(WmsWarningRule::getRuleType, rule.getRuleType())
                .eq(WmsWarningRule::getScopeType, rule.getScopeType())
                .ne(selfId != null, WmsWarningRule::getId, selfId);
        applyScopeFilter(dup, rule);
        Long sameTarget = baseMapper.selectCount(dup);
        if (sameTarget != null && sameTarget > 0) {
            throw new RuntimeException("同一" + (scopeMaterial ? "物料" : scopeCategory ? "分类" : "范围")
                    + "已有同类型的预警规则，请直接修改原规则");
        }
    }

    private void applyScopeFilter(LambdaQueryWrapper<WmsWarningRule> wrapper, WmsWarningRule rule) {
        if (StringUtils.hasText(rule.getMaterialId())) {
            wrapper.eq(WmsWarningRule::getMaterialId, rule.getMaterialId());
        } else {
            wrapper.isNull(WmsWarningRule::getMaterialId);
        }
        if (StringUtils.hasText(rule.getCategoryId())) {
            wrapper.eq(WmsWarningRule::getCategoryId, rule.getCategoryId());
        } else {
            wrapper.isNull(WmsWarningRule::getCategoryId);
        }
        if (StringUtils.hasText(rule.getWarehouseId())) {
            wrapper.eq(WmsWarningRule::getWarehouseId, rule.getWarehouseId());
        } else {
            wrapper.isNull(WmsWarningRule::getWarehouseId);
        }
    }
}
