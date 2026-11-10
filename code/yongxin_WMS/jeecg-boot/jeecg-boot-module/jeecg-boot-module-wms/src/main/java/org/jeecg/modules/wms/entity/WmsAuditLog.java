package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jeecgframework.poi.excel.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description: WMS 审计留痕（XT-002）。
 *
 * <p><b>刻意不继承 {@code JeecgEntity}、不声明 {@code @TableLogic}：</b>
 * 审计记录一旦写入即不可变，表里没有 {@code create_by/update_by/update_time/deleted_at}。
 * 继承基类会让 MyBatis-Plus 在查询时拼出不存在的列；声明逻辑删除则等于给「删日志」开了口子。
 * 表上另有 {@code BEFORE UPDATE} 触发器兜底。
 *
 * <p>本实体只用于写入与读取，**不要**为其生成 update/delete 端点。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "WMS 审计留痕")
@TableName("wms_audit_log")
public class WmsAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "业务类型：工单/入库单/出库单/调拨单/盘点单/系统配置/主数据")
    @Excel(name = "业务类型", width = 12)
    private String bizType;

    @Schema(description = "业务单号（反查入口），按行同步用「单号#序列#工序号」")
    @Excel(name = "业务单号", width = 26)
    private String bizNo;

    @Schema(description = "关联单号（如入库单对应的工单号）")
    @Excel(name = "关联单号", width = 22)
    private String bizRef;

    @Schema(description = "动作：创建/修改/审核/作废/同步落地/回写ERP/绑定/解绑")
    @Excel(name = "动作", width = 12)
    private String action;

    @Schema(description = "通道：界面操作/ERP同步/回写ERP/设备回调/定时任务")
    @Excel(name = "通道", width = 12)
    private String channel;

    @Excel(name = "变更前", width = 40)
    @Schema(description = "变更前关键字段 JSON")
    private String beforeValue;

    @Excel(name = "变更后", width = 40)
    @Schema(description = "变更后关键字段 JSON")
    private String afterValue;

    @Schema(description = "发生变化的字段名列表")
    private String diffFields;

    @Schema(description = "操作人；系统/定时任务写 system")
    @Excel(name = "操作人", width = 14)
    private String operator;

    @Schema(description = "来源 IP")
    @Excel(name = "来源IP", width = 16)
    private String ip;

    @Schema(description = "同一次请求/同一批同步的串联 id")
    private String requestId;

    @Schema(description = "结果：成功/失败")
    @Excel(name = "结果", width = 8)
    private String result;

    @Schema(description = "失败原因（截断 500 字）")
    @Excel(name = "失败原因", width = 40)
    private String errorMsg;

    @Excel(name = "耗时(ms)", width = 10)
    @Schema(description = "耗时毫秒")
    private Integer costMs;

    @Excel(name = "发生时间", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "发生时间")
    private Date createTime;
}
