package org.jeecg.modules.wms.service.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.jeecg.modules.wms.entity.WmsPrintDocument;
import org.jeecg.modules.wms.mapper.WmsPrintDocumentMapper;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 单据打印快照存储（DY-001）：金蝶来源单据的离线打印底账。
 *
 * <p>查询成功即 upsert 刷新；金蝶不可达时回退快照打印并在预览页标注数据时间。
 * uk_print_doc(bill_type, erp_bill_no) 保证一行一单；upsert 用「查-后-写」，
 * 两请求并发同单的窗口由唯一键兜底（重复插入撞 uk 即失败，调用方按失败处理）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Component
public class WmsPrintSnapshotStore {

    private final WmsPrintDocumentMapper mapper;

    public WmsPrintSnapshotStore(WmsPrintDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /** 查-后-写 upsert：查无 → insert（自建 id）；查有 → 沿用旧 id 刷新 payload 与 snapshotTime。 */
    public void upsert(String billType, String erpBillNo, String payloadJson) {
        String type = trimToEmpty(billType);
        String no = trimToEmpty(erpBillNo);
        List<WmsPrintDocument> existing = mapper.selectList(new QueryWrapper<WmsPrintDocument>()
                .eq("bill_type", type)
                .eq("erp_bill_no", no)
                .orderByDesc("snapshot_time")
                .last("LIMIT 1"));
        Date now = new Date();
        if (existing.isEmpty()) {
            WmsPrintDocument doc = new WmsPrintDocument()
                    .setBillType(type)
                    .setErpBillNo(no)
                    .setPayload(payloadJson)
                    .setSnapshotTime(now);
            doc.setId(IdWorker.getIdStr());
            doc.setCreateTime(now);
            mapper.insert(doc);
        } else {
            WmsPrintDocument patch = new WmsPrintDocument()
                    .setPayload(payloadJson)
                    .setSnapshotTime(now);
            patch.setId(existing.get(0).getId());
            patch.setUpdateTime(now);
            mapper.updateById(patch);
        }
    }

    /** 按类型+单号取快照（trim 匹配），查无返回 empty。 */
    public Optional<WmsPrintDocument> find(String billType, String erpBillNo) {
        List<WmsPrintDocument> existing = mapper.selectList(new QueryWrapper<WmsPrintDocument>()
                .eq("bill_type", trimToEmpty(billType))
                .eq("erp_bill_no", trimToEmpty(erpBillNo))
                .orderByDesc("snapshot_time")
                .last("LIMIT 1"));
        return existing.isEmpty() ? Optional.<WmsPrintDocument>empty() : Optional.of(existing.get(0));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
