package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private String bizType;

    @Schema(description = "业务单号（反查入口），按行同步用「单号#序列#工序号」")
    private String bizNo;

    @Schema(description = "关联单号（如入库单对应的工单号）")
    private String bizRef;

    @Schema(description = "动作：创建/修改/审核/作废/同步落地/回写ERP/绑定/解绑")
    private String action;

    @Schema(description = "通道：界面操作/ERP同步/回写ERP/设备回调/定时任务")
    private String channel;

    @Schema(description = "变更前关键字段 JSON")
    private String beforeValue;

    @Schema(description = "变更后关键字段 JSON")
    private String afterValue;

    @Schema(description = "发生变化的字段名列表")
    private String diffFields;

    @Schema(description = "操作人；系统/定时任务写 system")
    private String operator;

    @Schema(description = "来源 IP")
    private String ip;

    @Schema(description = "同一次请求/同一批同步的串联 id")
    private String requestId;

    @Schema(description = "结果：成功/失败")
    private String result;

    @Schema(description = "失败原因（截断 500 字）")
    private String errorMsg;

    @Schema(description = "耗时毫秒")
    private Integer costMs;

    @Schema(description = "发生时间")
    private Date createTime;
}
