package org.jeecg.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * @Description: PmsUtil
 * @author: jeecg-boot
 */
@Slf4j
@Component
@Lazy(false)
public class PmsUtil {


    private static String uploadPath;

    @Value("${jeecg.path.upload:}")
    public void setUploadPath(String uploadPath) {
        PmsUtil.uploadPath = uploadPath;
    }

    public static String saveErrorTxtByList(List<String> msg, String name) {
        Date d = new Date();
        String saveDir = "logs" + File.separator + DateUtils.yyyyMMdd.get().format(d) + File.separator;
        String saveFullDir = uploadPath + File.separator + saveDir;

        File saveFile = new File(saveFullDir);
        if (!saveFile.exists()) {
            saveFile.mkdirs();
        }
        name += DateUtils.yyyymmddhhmmss.get().format(d) + Math.round(Math.random() * 10000);
        String saveFilePath = saveFullDir + name + ".txt";

        try {
            //封装目的地
            //update-begin---author:claude ---date:20261013  for：【永鑫WMS】错误清单字符集固定为UTF-8-----------
            // 原实现用 FileWriter，按平台默认字符集写出（中文 Windows 上多为 GBK），
            // 浏览器下载后按 UTF-8 解析会显示乱码。这里显式指定 UTF-8。
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saveFilePath), StandardCharsets.UTF_8));
            //update-end---author:claude ---date:20261013  for：【永鑫WMS】错误清单字符集固定为UTF-8-----------
            //遍历集合
            for (String s : msg) {
                //update-begin---author:claude ---date:20261013  for：【永鑫WMS】错误清单破坏含下划线消息-----------
                // 原实现：消息中出现半角下划线时，整行按 "_" 拆开重拼为 "第<arr[0]>行:<arr[1]>"。
                // 缺陷：把业务值中的下划线（如分类编码 NO_SUCH_CAT）误当分隔符，且丢弃第 3 段之后的内容，
                // 会产生 "第第 5 行：分类编码 NO行:SUCH" 这类被破坏的错误行。
                // 现调用方传入的已是「第 N 行：原因」完整句子，原样写出即可。
                bw.write(s == null ? "" : s);
                //update-end---author:claude ---date:20261013  for：【永鑫WMS】错误清单破坏含下划线消息-----------
                //bw.newLine();
                bw.write("\r\n");
            }
            //释放资源
            bw.flush();
            bw.close();
        } catch (Exception e) {
            log.info("excel导入生成错误日志文件异常:" + e.getMessage());
        }
        return saveDir + name + ".txt";
    }

}
