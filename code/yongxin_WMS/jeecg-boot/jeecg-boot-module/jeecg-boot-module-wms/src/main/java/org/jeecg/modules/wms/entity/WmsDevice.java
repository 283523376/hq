package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecg.common.system.base.entity.JeecgEntity;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.util.Date;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "设备")
@TableName("wms_device")
public class WmsDevice extends JeecgEntity {

    private static final long serialVersionUID = 1L;

    /** 设备编码。 */
    @Excel(name = "设备编码", width = 20)
    @Schema(description = "设备编码")
    private String deviceCode;

    /** 设备名称。 */
    @Excel(name = "设备名称", width = 20)
    @Schema(description = "设备名称")
    private String deviceName;

    /** 设备类型：堆垛机 / SCR / 分拣单元 / 充电桩。 */
    @Excel(name = "设备类型", width = 15)
    @Schema(description = "设备类型：堆垛机/SCR/分拣单元/充电桩")
    private String deviceType;

    /** 归属库区ID；NULL = 未绑定。 */
    @Schema(description = "归属库区ID，NULL=未绑定")
    private String warehouseId;

    /** 状态：在线 / 离线 / 故障。 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：在线/离线/故障")
    private String status;

    /**
     * 最近一次心跳（或显式置在线）时刻。
     *
     * <p><b>判定离线的唯一依据</b>。为 NULL 表示该设备从未上线过，
     * 离线扫描会跳过它 —— 否则新建设备会在 10 分钟后被误报离线。
     */
    @Schema(description = "最近一次心跳时刻")
    private Date lastHeartbeat;

    /** 最近一次离线告警时刻，用于告警节流。恢复在线时清空。 */
    @Schema(description = "最近一次离线告警时刻")
    private Date lastAlertTime;

    /** 归属库区名称（非持久化，列表展示用，交给 QueryGenerator 前必须先置空）。 */
    @TableField(exist = false)
    @Schema(description = "归属库区名称")
    private String warehouseName;

    /**
     * 删除时间戳（epoch 毫秒）：**0 = 未删除**，否则为删除时刻。
     *
     * <p>原因同 {@code WmsWarehouse}：{@code uk_wms_device_code} 必须把删除标记一起纳入，
     * 否则已删除的设备编码永久不可复用、且查重与保存的结论互相矛盾。
     * 用时间戳而非固定值，是为了让「删除 → 重建 → 再删除」可无限次进行。
     *
     * <p>本表原先是「物理删除」，于 V3.9.5_9 并入统一方案。
     * 索引为 {@code (device_code, deleted_at)}。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")
    @Schema(description = "删除时间戳(epoch ms)：0=未删除")
    private Long deletedAt;
}