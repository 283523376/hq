package org.jeecg.modules.wms.exporter;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @Description: 导出文件的落盘与定位（KC-004）。
 *
 * <p>写文件、改名、定位、删除都收在这里，别的类不再自己拼路径。
 *
 * <p>目录取 {@code wms.export.dir}，留空时用 {@code jeecg.path.upload} + {@code /wms-export}。
 * 启动时就把目录建出来并在建不出来时报 ERROR——总比用户点了导出才发现要强。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
public class WmsExportFileStore {

    /** 文件名字里的时间格式。 */
    private static final String FILE_NAME_TIME_PATTERN = "yyyyMMddHHmmss";
    /** 下载文件名前缀。 */
    private static final String LEDGER_FILE_PREFIX = "库存台账_";
    /** 生成中的临时后缀：改名前不会被下载到。 */
    private static final String PART_SUFFIX = ".part";
    /** taskId 取前几位做文件名区分（任务 ID 是完整雪花串，太长）。 */
    private static final int TASK_ID_SNIP_LENGTH = 8;

    private final Path root;

    @Autowired
    public WmsExportFileStore(WmsExportProperties properties,
                              @Value("${jeecg.path.upload:}") String uploadRoot) {
        this(Paths.get(resolveRootDir(properties, uploadRoot)), true);
    }

    /** 直接指定根目录，不创建（单测用，也可给嵌入式场景用）。 */
    public WmsExportFileStore(Path root) {
        this(root, false);
    }

    private WmsExportFileStore(Path root, boolean create) {
        this.root = root.toAbsolutePath().normalize();
        if (create) {
            try {
                Files.createDirectories(this.root);
                log.info("导出文件目录：{}", this.root);
            } catch (IOException e) {
                log.error("导出文件目录创建失败，导出任务会全部失败：{}", this.root, e);
            }
        }
    }

    private static String resolveRootDir(WmsExportProperties properties, String uploadRoot) {
        if (properties != null && properties.getDir() != null && !properties.getDir().trim().isEmpty()) {
            return properties.getDir().trim();
        }
        String base = uploadRoot == null || uploadRoot.trim().isEmpty() ? "." : uploadRoot.trim();
        return base + "/wms-export";
    }

    public Path getRoot() {
        return root;
    }

    /** 生成中的临时文件。 */
    public File partFile(String taskId) {
        return root.resolve(taskId + PART_SUFFIX).toFile();
    }

    /** 用户可见的文件名（同时作为相对路径存库）。 */
    public String buildFileName(String taskId, Date time) {
        String stamp = new SimpleDateFormat(FILE_NAME_TIME_PATTERN).format(time);
        String snip = taskId.length() <= TASK_ID_SNIP_LENGTH
                ? taskId : taskId.substring(0, TASK_ID_SNIP_LENGTH);
        return LEDGER_FILE_PREFIX + stamp + "_" + snip + ".xlsx";
    }

    /**
     * 把临时文件改名成最终文件。
     *
     * <p>先写 `.part` 再改名，下载端就不可能拿到写了一半的 xlsx。
     */
    public File commit(File part, String fileName) throws IOException {
        Path target = root.resolve(fileName);
        Files.move(part.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return target.toFile();
    }

    /** 相对路径 → 绝对文件（不校验存在，调用方自己判）。 */
    public File resolve(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        return root.resolve(relativePath).toFile();
    }

    /** 删文件，任何异常都只记 warn（清理这类动作不该因为一个坏文件停下来）。 */
    public void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("删除导出文件失败：{}（{}）", file.getAbsolutePath(), e.getMessage());
        }
    }
}
