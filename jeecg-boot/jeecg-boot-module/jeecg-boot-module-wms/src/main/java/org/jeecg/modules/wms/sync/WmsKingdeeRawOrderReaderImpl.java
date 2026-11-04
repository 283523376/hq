package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.springframework.stereotype.Service;

/**
 * @Description: 金蝶采购订单 / 受托加工订单读取（V2.4 §3.2 到货登记校验）。
 *
 * <p><b>formId/字段需控制台确认</b>：采购管理与受托加工的单据标识、实体字段名随客户账套
 * 差异较大，本类只读<b>最小集</b>（单号、物料编码、数量），字段名以 BOS 在线控制台
 * 实测为准。formId 来自系统配置 {@link WmsConfigKey#KD_FORM_PURCHASE_ORDER} /
 * {@link WmsConfigKey#KD_FORM_CONSIGNED_ORDER}，<b>未配置一律抛错</b>：
 * 返回空结果会让到货登记误判成「ERP 没有这张单」，比直接失败更难排查。
 *
 * <p>本类不判断 {@code wms.sync.erp.kingdee.enabled}：开关语义是「是否调用 ERP」，
 * 由调用方（到货登记服务）在发起前自行判断，Reader 不在这里伪造空结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsKingdeeRawOrderReaderImpl implements IWmsKingdeeRawOrderReader {

    /**
     * 最小字段集：单号、物料编码、数量。
     *
     * <p>「formId/字段需控制台确认」：采购订单与受托加工订单的实体字段名（尤其物料
     * 分录上的 {@code FMaterialId.FNumber}）必须用控制台 ExecuteBillQuery 实测后再定，
     * 这里先按金蝶采购管理的通用口径写。
     */
    static final String FIELDS = "FBillNo,FMaterialId.FNumber,FQty";

    /** 一张订单可能有多行物料，单次查询上限取足够大（采购订单行数远小于此值）。 */
    private static final int LIMIT = 500;

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;

    public WmsKingdeeRawOrderReaderImpl(WmsKingdeeFormClient formClient,
                                        IWmsSystemConfigService configService) {
        this.formClient = formClient;
        this.configService = configService;
    }

    @Override
    public WmsKingdeeResult queryPurchaseOrder(String billNo) {
        return query(WmsConfigKey.KD_FORM_PURCHASE_ORDER, "采购订单", billNo);
    }

    @Override
    public WmsKingdeeResult queryConsignedOrder(String billNo) {
        return query(WmsConfigKey.KD_FORM_CONSIGNED_ORDER, "受托加工订单", billNo);
    }

    private WmsKingdeeResult query(WmsConfigKey formKey, String billName, String billNo) {
        if (oConvertUtils.isEmpty(billNo)) {
            throw new IllegalArgumentException("查询金蝶" + billName + "必须提供单据编号");
        }
        String formId = requireFormId(formKey, billName);
        String filter = "FBillNo='" + billNo.trim().replace("'", "''") + "'";
        log.info("查询金蝶{}({})，单号 {}", billName, formId, billNo);
        return formClient.executeBillQuery(formId, FIELDS, filter, LIMIT);
    }

    /**
     * 从系统配置读 formId；空串 = 未配置，显式失败。
     *
     * <p>不能返回空结果或伪装成功：到货登记依赖「订单存在且数量一致」才能放行，
     * 静默空结果会把配置缺失伪装成业务事实。
     */
    private String requireFormId(WmsConfigKey formKey, String billName) {
        String formId = configService.getString(formKey.getKey(), "");
        if (oConvertUtils.isEmpty(formId) || formId.trim().isEmpty()) {
            throw new IllegalStateException("金蝶单据[" + billName + "]未配置 formId（配置项 "
                    + formKey.getKey() + "）");
        }
        return formId.trim();
    }
}
