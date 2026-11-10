package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.mapper.WmsCustomerMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 客户档案（受托方，V2.4）
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsCustomerServiceImpl extends ServiceImpl<WmsCustomerMapper, WmsCustomer>
        implements IWmsCustomerService {

    @Autowired
    private WmsInboundOrderMapper wmsInboundOrderMapper;

    @Autowired
    private WmsInventoryMapper wmsInventoryMapper;

    @Override
    public void addCustomer(WmsCustomer customer) {
        trimFields(customer);
        if (oConvertUtils.isEmpty(customer.getCustomerCode())) {
            throw new RuntimeException("客户编码不能为空");
        }
        if (oConvertUtils.isEmpty(customer.getCustomerName())) {
            throw new RuntimeException("客户名称不能为空");
        }
        if (checkCodeExists(customer.getCustomerCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(customer.getStatus())) {
            customer.setStatus(WmsConstants.STATUS_ENABLE);
        }
        customer.setId(null);
        // 显式标记「未删除」：删除时间戳方案下 0 = 未删除（见 WmsCustomer.deletedAt）
        customer.setDeletedAt(0L);
        WmsUtils.fillCreate(customer);
        this.save(customer);
    }

    @Override
    public void editCustomer(WmsCustomer customer) {
        trimFields(customer);
        if (oConvertUtils.isEmpty(customer.getCustomerCode())) {
            throw new RuntimeException("客户编码不能为空");
        }
        if (oConvertUtils.isEmpty(customer.getCustomerName())) {
            throw new RuntimeException("客户名称不能为空");
        }
        if (checkCodeExists(customer.getCustomerCode(), customer.getId())) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(customer);
        this.updateById(customer);
    }

    @Override
    public void disableCustomer(String id) {
        WmsCustomer customer = new WmsCustomer();
        customer.setId(id);
        customer.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(customer);
        this.updateById(customer);
    }

    @Override
    public WmsDeleteCheckVO checkDelete(String id) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (oConvertUtils.isEmpty(id)) {
            return vo;
        }
        // 委托库存行：受托货权属客户，删掉档案后库存行与退货链路都失去归属
        long inventoryCount = wmsInventoryMapper.selectCount(
                new QueryWrapper<WmsInventory>().eq("consignor_id", id));
        if (inventoryCount > 0) {
            vo.addBlocker("存在受托库存", inventoryCount, null);
        }
        // 受托入库单：@TableLogic 自动追加 deleted_at = 0，不要手写删除条件
        long orderCount = wmsInboundOrderMapper.selectCount(
                new QueryWrapper<WmsInboundOrder>().eq("consignor_id", id));
        if (orderCount > 0) {
            List<WmsInboundOrder> recent = wmsInboundOrderMapper.selectList(new QueryWrapper<WmsInboundOrder>()
                    .eq("consignor_id", id).orderByDesc("create_time").last("limit 5"));
            vo.addBlocker("存在受托入库单", orderCount,
                    recent.stream().map(WmsInboundOrder::getOrderNo).collect(Collectors.toList()));
        }
        return vo;
    }

    @Override
    public WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids) {
        WmsBatchDeleteCheckVO vo = new WmsBatchDeleteCheckVO();
        if (ids == null || ids.isEmpty()) {
            return vo;
        }
        for (String id : ids) {
            WmsDeleteCheckVO check = checkDelete(id);
            if (!check.isDeletable()) {
                WmsCustomer customer = this.getById(id);
                vo.addBlocked(id,
                        customer == null ? id : customer.getCustomerCode(),
                        check.getBlockers().stream()
                                .map(WmsDeleteCheckVO.Blocker::getMessage)
                                .collect(Collectors.joining("；")));
            }
        }
        return vo;
    }

    @Override
    public void removeCustomer(String id) {
        WmsDeleteCheckVO check = checkDelete(id);
        if (!check.isDeletable()) {
            // 文案逐字透出：controller 显式 catch → Result.error，不经全局异常处理器加前缀
            throw new RuntimeException("该客户存在受托库存或受托入库单，不允许删除");
        }
        this.removeById(id);
    }

    /**
     * 批量删除必须整体在一个事务里：循环复用单条守卫，第 N 条被拒时前 N-1 条一并回滚
     * （与 WmsSupplierServiceImpl.removeBatch 同一教训）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                removeCustomer(id);
            }
        }
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsCustomer> queryWrapper = new QueryWrapper<>();
        // 按 trim 后的编码比对，与 addCustomer/editCustomer 的入库值保持一致
        queryWrapper.eq("customer_code", code.trim());
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public List<WmsCustomer> listAllEnabled() {
        QueryWrapper<WmsCustomer> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", WmsConstants.STATUS_ENABLE);
        queryWrapper.orderByAsc("customer_code");
        return this.list(queryWrapper);
    }

    /** 去掉文本字段首尾空格，避免「看起来一样、实际不同」的脏数据。 */
    private void trimFields(WmsCustomer customer) {
        customer.setCustomerCode(trim(customer.getCustomerCode()));
        customer.setCustomerName(trim(customer.getCustomerName()));
        customer.setContact(trim(customer.getContact()));
        customer.setPhone(trim(customer.getPhone()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
