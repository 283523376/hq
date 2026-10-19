package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @Description: 生产工单服务：扫码查询、MES 推送/回源、手工维护与删除守卫。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsWorkOrderServiceImpl extends ServiceImpl<WmsWorkOrderMapper, WmsWorkOrder>
        implements IWmsWorkOrderService {

    private static final int MES_TIMEOUT_MS = 3000;

    @Autowired
    private WmsMaterialMapper materialMapper;

    @Autowired
    private WmsProductionInboundMapper inboundMapper;

    @Value("${wms.sync.mes.work-order-url:}")
    private String mesWorkOrderUrl;

    @Value("${wms.sync.mes.username:}")
    private String mesUsername;

    @Value("${wms.sync.mes.password:}")
    private String mesPassword;

    private final RestTemplate restTemplate;

    public WmsWorkOrderServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(MES_TIMEOUT_MS);
        factory.setReadTimeout(MES_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsWorkOrder getByWorkOrderNo(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            return null;
        }
        return baseMapper.selectOne(Wrappers.<WmsWorkOrder>query()
                .eq("work_order_no", workOrderNo.trim()).last("LIMIT 1"));
    }

    @Override
    public WmsWorkOrderInfoVO scan(String workOrderNo) {
        WmsWorkOrder order = getByWorkOrderNo(workOrderNo);
        if (order == null) {
            order = fetchFromMes(workOrderNo);
        }
        if (order == null) {
            throw new RuntimeException("工单号无效");
        }
        BigDecimal inbounded = inboundMapper.sumInboundedQty(order.getWorkOrderNo());
        if (inbounded == null) {
            inbounded = BigDecimal.ZERO;
        }
        BigDecimal plan = order.getPlanQty() == null ? BigDecimal.ZERO : order.getPlanQty();
        WmsWorkOrderInfoVO vo = new WmsWorkOrderInfoVO();
        vo.setWorkOrderNo(order.getWorkOrderNo());
        vo.setMaterialId(order.getMaterialId());
        vo.setMaterialCode(order.getMaterialCode());
        vo.setMaterialName(order.getMaterialName());
        vo.setProductType(order.getProductType());
        vo.setPlanQty(plan);
        vo.setBatchNo(order.getBatchNo());
        vo.setWorkOrderStatus(order.getWorkOrderStatus());
        vo.setInboundedQty(inbounded);
        vo.setRemainingQty(plan.subtract(inbounded));
        vo.setIdentType(WmsConstants.PRODUCT_TYPE_FINISHED.equals(order.getProductType())
                ? WmsConstants.IDENT_TYPE_QRCODE : WmsConstants.IDENT_TYPE_RFID);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsWorkOrder push(WmsWorkOrderPushDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        if (oConvertUtils.isEmpty(dto.getMaterialCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", dto.getMaterialCode().trim()).last("LIMIT 1"));
        if (material == null) {
            throw new RuntimeException("物料编码不存在");
        }
        WmsWorkOrder order = getByWorkOrderNo(dto.getWorkOrderNo());
        boolean create = order == null;
        if (create) {
            order = new WmsWorkOrder().setSource(WmsConstants.WORK_ORDER_SOURCE_MES);
        }
        order.setWorkOrderNo(dto.getWorkOrderNo().trim())
                .setMaterialId(material.getId())
                .setMaterialCode(material.getMaterialCode())
                .setMaterialName(material.getMaterialName())
                .setProductType(dto.getProductType())
                .setPlanQty(dto.getPlanQty())
                .setBatchNo(dto.getBatchNo())
                .setWorkOrderStatus(oConvertUtils.isEmpty(dto.getWorkOrderStatus())
                        ? WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS : dto.getWorkOrderStatus())
                .setMesOrderNo(dto.getMesOrderNo())
                .setRemark(dto.getRemark());
        validate(order);
        if (create) {
            order.setId(null);
            order.setDeletedAt(0L);
            WmsUtils.fillCreate(order);
            save(order);
        } else {
            WmsUtils.fillUpdate(order);
            updateById(order);
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addWorkOrder(WmsWorkOrder order) {
        if (order == null) {
            throw new RuntimeException("工单信息不能为空");
        }
        fillMaterial(order);
        order.setWorkOrderNo(trim(order.getWorkOrderNo()));
        if (getByWorkOrderNo(order.getWorkOrderNo()) != null) {
            throw new RuntimeException("工单号已存在");
        }
        order.setSource(WmsConstants.WORK_ORDER_SOURCE_MANUAL);
        if (oConvertUtils.isEmpty(order.getWorkOrderStatus())) {
            order.setWorkOrderStatus(WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS);
        }
        validate(order);
        order.setId(null);
        order.setDeletedAt(0L);
        WmsUtils.fillCreate(order);
        save(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editWorkOrder(WmsWorkOrder order) {
        if (order == null || oConvertUtils.isEmpty(order.getId())) {
            throw new RuntimeException("工单ID不能为空");
        }
        WmsWorkOrder existing = getById(order.getId());
        if (existing == null) {
            throw new RuntimeException("工单不存在");
        }
        if (oConvertUtils.isNotEmpty(order.getWorkOrderNo())
                && !order.getWorkOrderNo().trim().equals(existing.getWorkOrderNo())) {
            throw new RuntimeException("工单号不可修改");
        }
        fillMaterial(order);
        order.setWorkOrderNo(existing.getWorkOrderNo());
        Long refs = inboundMapper.selectCount(Wrappers.<WmsProductionInbound>query()
                .eq("work_order_no", existing.getWorkOrderNo()));
        if (refs != null && refs > 0) {
            if (!java.util.Objects.equals(existing.getMaterialId(), order.getMaterialId())
                    || !java.util.Objects.equals(existing.getProductType(), order.getProductType())) {
                throw new RuntimeException("工单已被生产入库引用，不可修改物料或产品类型");
            }
        }
        validate(order);
        WmsUtils.fillUpdate(order);
        updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeWorkOrder(String id) {
        WmsWorkOrder existing = getById(id);
        if (existing == null) {
            throw new RuntimeException("工单不存在");
        }
        Long refs = inboundMapper.selectCount(Wrappers.<WmsProductionInbound>query()
                .eq("work_order_no", existing.getWorkOrderNo()));
        if (refs != null && refs > 0) {
            throw new RuntimeException("该工单已被生产入库引用，不可删除");
        }
        removeById(id);
    }

    @Override
    public IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo,
                                        String productType, String workOrderStatus) {
        QueryWrapper<WmsWorkOrder> qw = new QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(workOrderNo)) {
            qw.like("work_order_no", workOrderNo.trim());
        }
        if (oConvertUtils.isNotEmpty(productType)) {
            qw.eq("product_type", productType);
        }
        if (oConvertUtils.isNotEmpty(workOrderStatus)) {
            qw.eq("work_order_status", workOrderStatus);
        }
        qw.orderByDesc("create_time");
        return page(new Page<>(pageNo, pageSize), qw);
    }

    /** 从 MES 回源工单；未配置地址或调用失败返回 null。 */
    @SuppressWarnings("unchecked")
    private WmsWorkOrder fetchFromMes(String workOrderNo) {
        if (oConvertUtils.isEmpty(mesWorkOrderUrl) || oConvertUtils.isEmpty(workOrderNo)) {
            return null;
        }
        try {
            String url = mesWorkOrderUrl.trim()
                    + (mesWorkOrderUrl.contains("?") ? "&" : "?")
                    + "workOrderNo=" + URLEncoder.encode(workOrderNo.trim(), StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            if (oConvertUtils.isNotEmpty(mesUsername)) {
                headers.setBasicAuth(mesUsername, mesPassword == null ? "" : mesPassword);
            }
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null || !"0".equals(String.valueOf(body.get("code")))) {
                log.warn("MES 工单回源失败：工单号={}，响应={}", workOrderNo, body);
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            if (data == null) {
                return null;
            }
            WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
            dto.setWorkOrderNo(str(data.get("workOrderNo")));
            dto.setMaterialCode(str(data.get("materialCode")));
            dto.setProductType(str(data.get("productType")));
            dto.setPlanQty(data.get("planQty") == null ? null : new BigDecimal(str(data.get("planQty"))));
            dto.setBatchNo(str(data.get("batchNo")));
            dto.setWorkOrderStatus(str(data.get("workOrderStatus")));
            return push(dto);
        } catch (Exception e) {
            log.warn("MES 工单回源异常：工单号={}，原因={}", workOrderNo, e.getMessage());
            return null;
        }
    }

    /** 按物料编码带出物料主数据；物料不存在时抛错。 */
    private void fillMaterial(WmsWorkOrder order) {
        if (oConvertUtils.isEmpty(order.getMaterialCode())) {
            if (oConvertUtils.isEmpty(order.getMaterialId())) {
                throw new RuntimeException("物料编码不能为空");
            }
            WmsMaterial material = materialMapper.selectById(order.getMaterialId());
            if (material == null) {
                throw new RuntimeException("物料编码不存在");
            }
            order.setMaterialCode(material.getMaterialCode());
            order.setMaterialName(material.getMaterialName());
            return;
        }
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", order.getMaterialCode().trim()).last("LIMIT 1"));
        if (material == null) {
            throw new RuntimeException("物料编码不存在");
        }
        order.setMaterialId(material.getId());
        order.setMaterialCode(material.getMaterialCode());
        order.setMaterialName(material.getMaterialName());
    }

    private void validate(WmsWorkOrder order) {
        if (oConvertUtils.isEmpty(order.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        if (oConvertUtils.isEmpty(order.getMaterialId())) {
            throw new RuntimeException("物料编码不能为空");
        }
        if (!WmsConstants.PRODUCT_TYPE_SEMI.equals(order.getProductType())
                && !WmsConstants.PRODUCT_TYPE_FINISHED.equals(order.getProductType())) {
            throw new RuntimeException("产品类型必须是半成品或成品");
        }
        if (order.getPlanQty() == null || order.getPlanQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("计划数量必须大于 0");
        }
        if (oConvertUtils.isEmpty(order.getBatchNo())) {
            throw new RuntimeException("批次号不能为空");
        }
    }

    private String trim(String text) {
        return text == null ? null : text.trim();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
