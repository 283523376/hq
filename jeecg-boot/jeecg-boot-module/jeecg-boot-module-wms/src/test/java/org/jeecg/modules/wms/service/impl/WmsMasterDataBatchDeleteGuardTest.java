package org.jeecg.modules.wms.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: D4——主数据批量删除必须整体原子。
 *
 * <p>引用守卫设计（spec 2026-10-16 §2）要求 {@code removeBatch} 在一个事务里
 * 循环复用单条守卫：任何一行被引用 → 整批回滚，绝不允许「删一半」。
 * 此前该约束只有订单域一条反射断言（WmsOrderServiceImplTest），其余主数据服务
 * 没有注解级保护——有人顺手删掉 {@code @Transactional}，行为级测试未必红
 * （mock 环境感知不到事务边界）。此测试把六个主数据服务一起钉死。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-11
 */
class WmsMasterDataBatchDeleteGuardTest {

    private static final Map<String, String> SERVICES = Map.of(
            "WmsSupplierServiceImpl", "供应商",
            "WmsCustomerServiceImpl", "客户",
            "WmsMaterialServiceImpl", "物料",
            "WmsWarehouseServiceImpl", "仓库",
            "WmsLocationServiceImpl", "库位",
            "WmsDeviceServiceImpl", "设备");

    @Test
    void removeBatch_mustBeTransactionalInAllMasterDataServices() throws Exception {
        for (Map.Entry<String, String> entry : SERVICES.entrySet()) {
            Class<?> clazz = Class.forName("org.jeecg.modules.wms.service.impl." + entry.getKey());
            Method method = findRemoveBatch(clazz);
            assertNotNull(method, entry.getValue() + "服务缺少 removeBatch(List<String>)");
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx,
                    entry.getValue() + "服务的 removeBatch 必须带 @Transactional（整批原子，"
                            + "任何一行被引用即整批回滚，不允许删一半）");
            assertTrue(tx.rollbackFor().length == 1 && tx.rollbackFor()[0] == Exception.class,
                    entry.getValue() + "服务的 removeBatch 必须 rollbackFor = Exception.class");
        }
    }

    private Method findRemoveBatch(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if ("removeBatch".equals(method.getName())
                    && method.getParameterCount() == 1
                    && List.class.isAssignableFrom(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }
}
