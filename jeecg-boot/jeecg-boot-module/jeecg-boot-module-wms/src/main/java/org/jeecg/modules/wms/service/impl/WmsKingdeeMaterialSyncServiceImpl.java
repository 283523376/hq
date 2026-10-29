package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeMaterialSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @Description: 物料主数据同步（金蝶 BD_MATERIAL → wms_material，恒等映射）。
 *
 * <p>2026-10-28「自动化优先」P0：物料档案此前纯手工维护，现从金蝶同步——
 * {@code material_code = 金蝶 FNumber}（恒等映射，{@code kingdee_code} 同值），
 * 名称/规格/基本单位以金蝶为准更新（空值不覆盖本地）。存量手工物料（编码与金蝶不同）
 * 不会被命中，映射走页面维护或 YAML 兜底（{@link org.jeecg.modules.wms.sync.WmsMaterialCodeResolver}）。
 *
 * <p><b>不做</b>：分类匹配（WMS 分类与金蝶物料分组是两套体系，留人工）、
 * 删除（金蝶侧停用/删除的物料只跳过，不删本地）、产品类型（金蝶无半成品/成品概念）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsKingdeeMaterialSyncServiceImpl implements IWmsKingdeeMaterialSyncService {

    private static final String FORM_ID = "BD_MATERIAL";
    private static final String FIELD_KEYS =
            "FMATERIALID,FNumber,FName,FSpecification,FBaseUnitId.FNumber";
    /** 单页条数；返回行数小于该值即视为末页。 */
    private static final int PAGE_SIZE = 500;
    /** 翻页硬上限，防止金蝶异常时死循环。 */
    private static final int MAX_PAGES = 200;

    private static final String SYNC_OBJECT_TYPE = "MATERIAL";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsKingdeeAuthService kingdeeAuthService;
    private final WmsMaterialMapper materialMapper;
    private final IWmsSyncLogService syncLogService;

    public WmsKingdeeMaterialSyncServiceImpl(WmsKingdeeFormClient formClient,
                                             WmsKingdeeProperties properties,
                                             IWmsKingdeeAuthService kingdeeAuthService,
                                             WmsMaterialMapper materialMapper,
                                             IWmsSyncLogService syncLogService) {
        this.formClient = formClient;
        this.properties = properties;
        this.kingdeeAuthService = kingdeeAuthService;
        this.materialMapper = materialMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    public WmsKingdeeStockSyncResultVO syncMaterial() {
        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        if (!properties.isEnabled()) {
            return skip(vo, "金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
        }
        if (kingdeeAuthService != null && !kingdeeAuthService.isConfigured()) {
            return skip(vo, "金蝶未配置（baseUrl/appId 等），跳过");
        }

        long start = System.currentTimeMillis();
        List<List<Object>> rows = fetchAllMaterials();
        vo.setFetched(rows.size());

        for (List<Object> row : rows) {
            if (upsertMaterial(row)) {
                vo.setCreated(vo.getCreated() + 1);
            } else {
                vo.setRenamed(vo.getRenamed() + 1);
            }
        }
        vo.setMessage(String.format("扫描金蝶物料 %d 行：新建 %d，更新 %d",
                vo.getFetched(), vo.getCreated(), vo.getRenamed()));
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, vo.getFetched(),
                null, vo.getMessage(), null, System.currentTimeMillis() - start);
        return vo;
    }

    /** 跳过分支也留日志，便于回查定时任务是否真的跑过。 */
    private WmsKingdeeStockSyncResultVO skip(WmsKingdeeStockSyncResultVO vo, String message) {
        log.info("物料同步跳过：{}", message);
        vo.setMessage(message);
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, null, message, null, 0L);
        return vo;
    }

    /**
     * 双身份监控（2026-10-29）：金蝶编码已被 YAML {@code material-map} 映射到另一个 WMS 物料时，
     * 说明同步即将再造一条「自映射空壳」记录。此时只是告警、不阻断同步——阻断会让物料主数据同步整体失败，
     * 代价远大于脏数据本身。
     *
     * <p>发现这条 WARN 就说明需要在 YAML 映射与「物料编码统一为金蝶编码」之间做取舍（见物料双身份方案 B2）。
     */
    private void warnIfYamlMappedDuplicate(String kingdeeNumber) {
        try {
            for (Map.Entry<String, String> entry : properties.getMaterialMap().entrySet()) {
                if (kingdeeNumber.equals(entry.getValue())) {
                    log.warn("物料双身份：金蝶物料[{}]已由 YAML 映射到 WMS 物料[{}]，同步却仍在按原样码建记录，"
                                    + "解析时会优先走 YAML（见 WmsMaterialCodeResolver），但库里将出现两条记录",
                            kingdeeNumber, entry.getKey());
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("双身份监控检查失败（不影响同步）：{}", e.getMessage());
        }
    }

    /**
     * 单行 upsert：按 material_code（=金蝶 FNumber）查，查到更新非空字段，查到新建。
     *
     * @return true=新建，false=更新
     */
    private boolean upsertMaterial(List<Object> row) {
        // FMATERIALID,FNumber,FName,FSpecification,FBaseUnitId.FNumber
        String number = asText(row.size() > 1 ? row.get(1) : null);
        if (number.isEmpty()) {
            return false;
        }
        String name = asText(row.size() > 2 ? row.get(2) : null);
        String spec = asText(row.size() > 3 ? row.get(3) : null);
        String unit = asText(row.size() > 4 ? row.get(4) : null);

        WmsMaterial existing = materialMapper.selectOne(new LambdaQueryWrapper<WmsMaterial>()
                .eq(WmsMaterial::getMaterialCode, number)
                .last("LIMIT 1"));
        if (existing == null) {
            warnIfYamlMappedDuplicate(number);
            WmsMaterial material = new WmsMaterial();
            material.setMaterialCode(number);
            material.setMaterialName(name);
            material.setSpec(spec);
            material.setUnit(unit);
            // 恒等映射：ERP 同步物料金蝶码=自身编码
            material.setKingdeeCode(number);
            material.setStatus("1");
            material.setDeletedAt(0L);
            WmsUtils.fillCreate(material);
            materialMapper.insert(material);
            return true;
        }
        boolean changed = false;
        if (oConvertUtils.isEmpty(existing.getKingdeeCode())) {
            existing.setKingdeeCode(number);
            changed = true;
        }
        if (oConvertUtils.isNotEmpty(name) && !name.equals(existing.getMaterialName())) {
            existing.setMaterialName(name);
            changed = true;
        }
        if (oConvertUtils.isNotEmpty(spec) && !spec.equals(existing.getSpec())) {
            existing.setSpec(spec);
            changed = true;
        }
        if (oConvertUtils.isNotEmpty(unit) && !unit.equals(existing.getUnit())) {
            existing.setUnit(unit);
            changed = true;
        }
        if (changed) {
            materialMapper.updateById(existing);
        }
        return false;
    }

    private List<List<Object>> fetchAllMaterials() {
        String orgId = properties.getMasterData().getOrgId();
        String filter = (orgId == null || orgId.trim().isEmpty())
                ? "" : "FUseOrgId.FNumber='" + orgId.trim().replace("'", "''") + "'";
        List<List<Object>> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            WmsKingdeeResult result = formClient.executeBillQuery(
                    FORM_ID, FIELD_KEYS, filter, PAGE_SIZE, page * PAGE_SIZE);
            if (!result.isSuccess()) {
                throw new RuntimeException("金蝶物料查询失败：" + result.getMessage());
            }
            List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            all.addAll(rows);
            if (rows.size() < PAGE_SIZE) {
                return all;
            }
        }
        log.warn("物料同步达到翻页上限 {} 页，已截断", MAX_PAGES);
        return all;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
