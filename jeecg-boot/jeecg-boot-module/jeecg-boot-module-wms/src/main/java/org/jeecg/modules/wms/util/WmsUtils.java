package org.jeecg.modules.wms.util;

import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecg.common.system.vo.LoginUser;

import java.util.Date;

/**
 * WMS 模块通用工具。
 *
 * <p>本项目未启用 MyBatis-Plus 的自动填充（无 MetaObjectHandler），
 * 因此审计字段（创建/更新人与时间）由这里显式写入。
 *
 * @author 永鑫WMS
 */
public final class WmsUtils {

    private WmsUtils() {
    }

    /**
     * 取当前登录账号；无登录上下文时返回 null。
     */
    public static String currentUsername() {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof LoginUser) {
                return ((LoginUser) principal).getUsername();
            }
        } catch (Exception ignored) {
            // 非 Web/未登录上下文，忽略
        }
        return null;
    }

    /**
     * 取当前登录用户姓名；取不到姓名时退回账号，再无则返回 null。
     */
    public static String currentRealname() {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof LoginUser) {
                LoginUser user = (LoginUser) principal;
                if (user.getRealname() != null && !user.getRealname().trim().isEmpty()) {
                    return user.getRealname();
                }
                return user.getUsername();
            }
        } catch (Exception ignored) {
            // 非 Web/未登录上下文，忽略
        }
        return null;
    }

    /**
     * 新增时填充审计字段。
     */
    public static <T extends JeecgEntity> void fillCreate(T entity) {
        Date now = new Date();
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(now);
        }
        entity.setUpdateTime(now);
        String username = currentUsername();
        if (entity.getCreateBy() == null) {
            entity.setCreateBy(username);
        }
        entity.setUpdateBy(username);
    }

    /**
     * 更新时填充审计字段。
     */
    public static <T extends JeecgEntity> void fillUpdate(T entity) {
        entity.setUpdateTime(new Date());
        entity.setUpdateBy(currentUsername());
    }
}
