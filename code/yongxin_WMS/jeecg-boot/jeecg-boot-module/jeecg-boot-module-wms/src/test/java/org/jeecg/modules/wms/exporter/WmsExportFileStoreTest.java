package org.jeecg.modules.wms.exporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出文件命名：必须全局唯一，否则同秒完成的两个任务会互相覆盖（评审 Important #2）。
 */
class WmsExportFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void sameSecondDifferentTasksGetDifferentNames() {
        WmsExportFileStore store = new WmsExportFileStore(tempDir);
        Date sameSecond = new Date(1793236000000L);

        // 雪花 ID 前 8 位相同（高 22 位是毫秒时间戳），只有后半段不同
        String nameA = store.buildFileName("2115621400000000001", sameSecond);
        String nameB = store.buildFileName("2115621400000000002", sameSecond);

        assertNotEquals(nameA, nameB, "同一秒内不同任务的文件名必须不同");
    }

    @Test
    void nameKeepsXlsxSuffixAndIsReadable() {
        WmsExportFileStore store = new WmsExportFileStore(tempDir);
        String name = store.buildFileName("2115621400000000001", new Date(1793236000000L));

        assertTrue(name.startsWith("库存台账_"), "文件名要对用户可读，实际: " + name);
        assertTrue(name.endsWith(".xlsx"), "实际: " + name);
    }
}
