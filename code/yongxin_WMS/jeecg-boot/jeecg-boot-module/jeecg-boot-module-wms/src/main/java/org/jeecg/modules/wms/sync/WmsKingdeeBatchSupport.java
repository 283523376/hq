package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶批号主档（BD_BatchMainFile）保障：批号缺失时自动创建为「可用」。
 *
 * <p>背景（2026-10-28 自动化优先 P1）：金蝶里批号主档不传 {@code FLotStatus} 新建时默认
 * {@code T}（不可用），领料/入库会被拒；而批号主档**没有** Submit/Audit/Delete 接口，
 * 过去只能人工到金蝶界面建。真机验证（101 测试账套）：{@code Save BD_BatchMainFile}
 * 直接带 {@code FLotStatus='2'} 即可创建为可用批号，无需审核。
 *
 * <p>两个开关（默认值按「不覆盖 ERP 既有数据」原则）：
 * <ul>
 *   <li>{@code batch.auto-create-enabled}（默认 true）：批号**不存在**时自动创建为可用；</li>
 *   <li>{@code batch.auto-repair-enabled}（默认 false）：批号**已存在但不可用**时是否自动改成可用。
 *       这属于改写金蝶主数据（可能是质量部门有意置为不可用），默认关闭，由顾问决定是否开启。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
public class WmsKingdeeBatchSupport {

    private static final String FORM_ID = "BD_BatchMainFile";
    /**
     * 批号「不可用」状态值 T。
     *
     * <p>2026-10-28 真机校准：BD_BatchMainFile 的 FLotStatus 实际存在 {@code T}（不可用/作废）、
     * {@code 1}、{@code 2} 三种取值；三种都能通过其他入库单保存，但**真机领料成功用的是 1**，
     * 历史失败（「批号已作废」）用的是 T。因此判据是「不等于 T 即可用」，
     * 而不是早先写的「必须等于 2」——后者会把状态 1 的正常批号全部误拒。
     */
    private static final String LOT_STATUS_UNUSABLE = "T";
    /** 新建批号时写入的状态值（历史验证可建；具体枚举含义待顾问确认）。 */
    private static final String LOT_STATUS_ON_CREATE = "2";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeBatchSupport(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    /**
     * 确保「物料 + 批号」在金蝶可用；缺失时按配置自动创建。
     *
     * @param materialCode 金蝶物料编码
     * @param lot          批号
     * @param orgNumber    使用组织编码
     * @return null 表示可用/已自动创建；否则返回给用户看的原因
     */
    public String ensureUsable(String materialCode, String lot, String orgNumber) {
        if (oConvertUtils.isEmpty(lot)) {
            return null;
        }
        WmsKingdeeResult query = formClient.executeBillQuery(FORM_ID,
                "FNumber,FMaterialId.FNumber,FLotStatus",
                "FNumber='" + lot.replace("'", "''") + "' AND FMaterialId.FNumber='"
                        + materialCode.replace("'", "''") + "'", 5);
        if (!query.isSuccess()) {
            // 查询失败不拦路：交给金蝶在保存/审核时判
            log.warn("批号[{}]校验查询失败（不拦路）：{}", lot, query.getMessage());
            return null;
        }
        WmsKingdeeProperties.Batch batch = properties.getBatch();
        if (query.getRows().isEmpty()) {
            if (!batch.isAutoCreateEnabled()) {
                return "批号[" + lot + "]在物料[" + materialCode + "]下不存在，请先在金蝶创建批号主档";
            }
            return create(materialCode, lot, orgNumber);
        }
        String status = query.getRows().get(0).size() > 2
                ? str(query.getRows().get(0).get(2)) : "";
        if (!LOT_STATUS_UNUSABLE.equalsIgnoreCase(status)) {
            return null;
        }
        if (!batch.isAutoRepairEnabled()) {
            return "批号[" + lot + "]状态为 " + status + "（非可用），金蝶拒绝领料；"
                    + "请在批号主档把状态改为可用（FLotStatus=2），"
                    + "或开启 wms.sync.erp.kingdee.batch.auto-repair-enabled 由系统自动修复";
        }
        return repair(materialCode, lot, orgNumber, status);
    }

    /** 创建可用批号：真机验证 Save 直接带 FLotStatus=2 即生效（该表单无 Submit/Audit）。 */
    private String create(String materialCode, String lot, String orgNumber) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FNumber", lot);
        model.put("FMaterialId", dict(materialCode));
        model.put("FUseOrgId", dict(orgNumber));
        model.put("FCreateOrgId", dict(orgNumber));
        model.put("FLotStatus", LOT_STATUS_ON_CREATE);
        WmsKingdeeResult saved = formClient.save(FORM_ID, model);
        if (!saved.isSuccess()) {
            return "批号[" + lot + "]在金蝶不存在，自动创建失败：" + saved.getMessage();
        }
        log.info("批号[{}]（物料 {}，组织 {}）在金蝶不存在，已自动创建为可用", lot, materialCode, orgNumber);
        return null;
    }

    /** 把已存在但不可用的批号改为可用（需显式开启 auto-repair-enabled）。 */
    private String repair(String materialCode, String lot, String orgNumber, String oldStatus) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FNumber", lot);
        model.put("FMaterialId", dict(materialCode));
        model.put("FUseOrgId", dict(orgNumber));
        model.put("FLotStatus", LOT_STATUS_ON_CREATE);
        WmsKingdeeResult saved = formClient.save(FORM_ID, model);
        if (!saved.isSuccess()) {
            return "批号[" + lot + "]状态为 " + oldStatus + "，自动改为可用失败：" + saved.getMessage();
        }
        log.warn("批号[{}]（物料 {}）状态 {} → 已按 auto-repair-enabled 自动改为可用",
                lot, materialCode, oldStatus);
        return null;
    }

    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
