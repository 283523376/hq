package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsLocation;

import java.util.List;

/**
 * @Description: 库位
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface WmsLocationMapper extends BaseMapper<WmsLocation> {

    /**
     * 一批 INSERT IGNORE，返回**实际插入的行数**。
     *
     * <p>重复坐标（命中 {@code uk_wms_location_code} 或 {@code uk_wms_location_grid}）被跳过、
     * 不计入返回行数。服务层据此算 {@code skipped = 请求总数 - 实际插入数}，
     * 使「跳过」是**数据库告诉我们的真值**，而不是服务端猜的。
     *
     * @param list 同一库区、已算好编码的库位列表，长度不得为 0
     * @return 实际插入行数
     */
    int insertIgnoreBatch(@Param("list") List<WmsLocation> list);
}