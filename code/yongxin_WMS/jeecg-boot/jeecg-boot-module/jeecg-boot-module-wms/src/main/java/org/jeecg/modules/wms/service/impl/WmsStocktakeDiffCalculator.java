package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: 盘点差异汇总与复核阈值判定（纯函数，便于穷举边界）。
 *
 * <p>判定式（spec §5.3，整单口径）：
 * {@code (Σ盘盈 + Σ盘亏) / Σ账面数量 * 100 >= 阈值} → 需复核。
 * 两条特殊边界：
 * <ul>
 *   <li>账面合计为 0（整库位盘盈）→ 任一差异即复核；</li>
 *   <li>阈值为空或 ≤ 0 → 任一差异即复核（空阈值视为「全部复核」而不是「不用复核」）。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Component
public class WmsStocktakeDiffCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * @return {@code [账面合计, 盘盈合计, 盘亏合计]}，三者均非负且盘亏为正数表示亏的量
     */
    public BigDecimal[] summarize(List<WmsStocktakeDetail> details) {
        BigDecimal book = BigDecimal.ZERO;
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        if (details != null) {
            for (WmsStocktakeDetail detail : details) {
                BigDecimal bookQty = nz(detail.getBookQty());
                book = book.add(bookQty);
                if (detail.getActualQty() == null) {
                    continue;
                }
                BigDecimal diff = detail.getActualQty().subtract(bookQty);
                if (diff.signum() > 0) {
                    gain = gain.add(diff);
                } else if (diff.signum() < 0) {
                    loss = loss.add(diff.negate());
                }
            }
        }
        return new BigDecimal[]{book, gain, loss};
    }

    /** 是否需要对整单做复核。 */
    public boolean needsReview(BigDecimal bookTotal, BigDecimal gainTotal, BigDecimal lossTotal,
                               BigDecimal thresholdPct) {
        BigDecimal diff = nz(gainTotal).add(nz(lossTotal));
        if (diff.signum() == 0) {
            return false;
        }
        if (nz(bookTotal).signum() == 0) {
            return true;
        }
        if (thresholdPct == null || thresholdPct.signum() <= 0) {
            return true;
        }
        BigDecimal ratio = diff.multiply(HUNDRED).divide(bookTotal, 6, java.math.RoundingMode.HALF_UP);
        return ratio.compareTo(thresholdPct) >= 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
