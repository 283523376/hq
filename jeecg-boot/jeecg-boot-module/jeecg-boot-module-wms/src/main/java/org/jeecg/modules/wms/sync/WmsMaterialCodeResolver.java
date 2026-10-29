package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @Description: WMS 物料编码 → 金蝶物料编码 / 产品类型 解析器。
 *
 * <p>2026-10-28「自动化优先」P0 改造：映射从 {@code application-dev.yml} 落库到
 * {@code wms_material.kingdee_code / kingdee_product_type}，解析顺序：
 * <ol>
 *   <li>表列非空 → 用表列（ERP 同步物料为恒等映射，手工物料经页面维护）</li>
 *   <li>YAML {@code material-map} / {@code material-product-type} 兜底（存量配置零迁移）</li>
 *   <li>都没有 → 编码原样返回（历史行为）；产品类型返回 null（调用方按既有缺省逻辑处理）</li>
 * </ol>
 *
 * <p>本类只做内存/单表查询，无金蝶调用；查表失败按「未配置」处理并告警日志（不抛，
 * 回写链路交给后续金蝶查询给出可读报错）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
public class WmsMaterialCodeResolver {

    private final WmsKingdeeProperties properties;
    private final WmsMaterialMapper materialMapper;

    public WmsMaterialCodeResolver(WmsKingdeeProperties properties, WmsMaterialMapper materialMapper) {
        this.properties = properties;
        this.materialMapper = materialMapper;
    }

    /**
     * WMS 物料编码 → 金蝶物料编码。
     *
     * <p>三级优先：表列 {@code kingdee_code} → YAML {@code material-map} → 原样返回。
     */
    public String resolveKingdeeCode(String wmsMaterialCode) {
        if (oConvertUtils.isEmpty(wmsMaterialCode)) {
            return wmsMaterialCode;
        }
        String fromDb = lookUpDb(wmsMaterialCode).getKingdeeCode();
        if (oConvertUtils.isNotEmpty(fromDb)) {
            return fromDb;
        }
        return properties.getMaterialMap().getOrDefault(wmsMaterialCode, wmsMaterialCode);
    }

    /**
     * WMS 物料编码 → 产品类型（半成品/成品）。
     *
     * <p>两级优先：表列 {@code kingdee_product_type} → YAML {@code material-product-type}；
     * 都没有返回 null（调用方沿用各自缺省）。
     */
    public String resolveProductType(String wmsMaterialCode) {
        if (oConvertUtils.isEmpty(wmsMaterialCode)) {
            return null;
        }
        String fromDb = lookUpDb(wmsMaterialCode).getKingdeeProductType();
        if (oConvertUtils.isNotEmpty(fromDb)) {
            return fromDb;
        }
        return properties.getMaterialProductType().get(wmsMaterialCode);
    }

    /**
     * 金蝶物料编码 → WMS 物料编码（反向）。
     *
     * <p><b>YAML 显式映射优先于表列</b>（2026-10-29 修正）：物料同步会把金蝶物料原样建成 WMS 物料，
     * 于是同一条金蝶物料可能同时存在「自映射空壳记录」（material_code == kingdee_code，无库存）
     * 和「YAML 映射记录」（如 FG001，库存挂在这里）。若先查表列，自映射记录会抢先命中，
     * PPBOM 待办就解析到没有库存的那条，分配必然失败。
     *
     * <p>顺序：YAML {@code material-map} 反向 → 表列 {@code kingdee_code}（含恒等映射）→ null。
     * null 由调用方保留各自缺省（PpbomReader 视为未匹配，WorkOrderSync 回退原样）。
     */
    public String reverseKingdeeCode(String kingdeeCode) {
        if (oConvertUtils.isEmpty(kingdeeCode)) {
            return null;
        }
        // 1) 显式 YAML 映射优先：它是业务认可的口径，且库存通常挂在映射后的 WMS 物料上
        for (Map.Entry<String, String> entry : properties.getMaterialMap().entrySet()) {
            if (kingdeeCode.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        // 2) 回落表列（覆盖绝大多数与原样同码的物料）
        try {
            WmsMaterial material = materialMapper.selectOne(new LambdaQueryWrapper<WmsMaterial>()
                    .eq(WmsMaterial::getKingdeeCode, kingdeeCode)
                    .last("LIMIT 1"));
            if (material != null && oConvertUtils.isNotEmpty(material.getMaterialCode())) {
                return material.getMaterialCode();
            }
        } catch (Exception e) {
            log.warn("反查金蝶物料[{}]的 WMS 编码失败，返回 null：{}", kingdeeCode, e.getMessage());
        }
        return null;
    }

    /** 单表查询；查不到/查失败返回空实体（调用方走兜底）。 */
    private WmsMaterial lookUpDb(String wmsMaterialCode) {
        try {
            WmsMaterial material = materialMapper.selectOne(new LambdaQueryWrapper<WmsMaterial>()
                    .eq(WmsMaterial::getMaterialCode, wmsMaterialCode)
                    .last("LIMIT 1"));
            return material == null ? new WmsMaterial() : material;
        } catch (Exception e) {
            log.warn("查询物料[{}]金蝶映射失败，按 YAML/原样兜底：{}", wmsMaterialCode, e.getMessage());
            return new WmsMaterial();
        }
    }
}
