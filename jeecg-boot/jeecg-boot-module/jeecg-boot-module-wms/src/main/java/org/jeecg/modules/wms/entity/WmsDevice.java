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

    /** 删除状态：0正常 1已删除。 */
    @TableLogic
    @Schema(description = "删除状态：0正常 1已删除")
    private Integer delFlag;
}