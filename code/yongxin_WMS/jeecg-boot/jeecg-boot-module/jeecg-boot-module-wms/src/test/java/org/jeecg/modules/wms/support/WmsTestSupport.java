package org.jeecg.modules.wms.support;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSecurityManager;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;

/**
 * @Description: WMS 测试支撑工具（反射注入 / Shiro 空上下文 / 导入模板 xlsx 构造）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public final class WmsTestSupport {

    private WmsTestSupport() {
    }

    /**
     * 沿继承链查找并注入字段。
     *
     * <p><b>为什么需要反射</b>：{@code JeecgController.service} 是声明在**另一个包**
     * （{@code org.jeecg.common.system.base.controller}）的 protected 字段——测试类即使与该
     * Controller 同包也无权赋值；{@code ServiceImpl.baseMapper} 同理。反射是唯一**不动生产代码**
     * 的注入方式（备选方案是给每个 Controller 加 {@code setXxxForTest} 专用 setter，但那要为
     * 4 个已上线控制器增加测试专用 API）。
     */
    public static void inject(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("注入字段失败：" + fieldName, e);
            }
        }
        throw new IllegalStateException(
                "未在继承链上找到字段：" + target.getClass().getName() + "#" + fieldName);
    }

    /**
     * 绑定一个空的 Shiro SecurityManager。
     *
     * <p>{@code WmsMaterialController.exportXls} 直接调用 {@code SecurityUtils.getSubject()} 且**未包 try/catch**，
     * 无绑定会抛 {@code UnavailableSecurityManagerException}；绑定后 {@code getPrincipal()} 返回 null，
     * 恰好覆盖控制器里 {@code user == null ? "" : user.getRealname()} 的分支。
     */
    public static void bindShiro() {
        SecurityUtils.setSecurityManager(new DefaultSecurityManager());
    }

    /** 解绑，避免污染同一 JVM 内的其它测试。 */
    public static void unbindShiro() {
        SecurityUtils.setSecurityManager(null);
    }

    /**
     * 构造与平台导入模板格式一致的 xlsx：第 1 行标题、第 2 行填写说明、第 3 行表头，数据自物理第 4 行起。
     *
     * <p>列顺序与 {@code WmsConstants.IMPORT_ROW_OFFSET}（=4）的约定一致：
     * 物料编码、物料名称、分类编码、规格。
     *
     * @param dataRows 数据行，每行按上述 4 列顺序；传 {@code null} 或空列表即「仅表头」的空文件
     */
    public static byte[] materialXlsx(List<String[]> dataRows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("物料");
            sheet.createRow(0).createCell(0).setCellValue("物料列表");
            sheet.createRow(1).createCell(0).setCellValue("填写说明：物料编码、物料名称、分类编码为必填");
            Row head = sheet.createRow(2);
            head.createCell(0).setCellValue("物料编码");
            head.createCell(1).setCellValue("物料名称");
            head.createCell(2).setCellValue("分类编码");
            head.createCell(3).setCellValue("规格");
            if (dataRows != null) {
                for (int i = 0; i < dataRows.size(); i++) {
                    Row row = sheet.createRow(3 + i);
                    String[] values = dataRows.get(i);
                    for (int column = 0; column < values.length; column++) {
                        row.createCell(column).setCellValue(values[column] == null ? "" : values[column]);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("构造测试 xlsx 失败", e);
        }
    }
}