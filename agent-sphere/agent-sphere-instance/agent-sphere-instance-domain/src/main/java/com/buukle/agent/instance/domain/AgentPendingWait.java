package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** wait 内置工具的挂起记录（V83）。一条记录 = 一次等待。 */
@Data
@TableName("agent_pending_wait")
public class AgentPendingWait {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    /** 模型侧 tool call id：唤醒时用它定位并改写对应的 tool call 记录。 */
    private String callId;
    private Integer waitMs;
    private LocalDateTime wakeAt;
    private String status;
    private String reason;
    private LocalDateTime resumedAt;
    /** 恢复重试次数（V86）：每次 requeueForRetry +1，超上限置 FAILED。 */
    private Integer attempts;
    /** 最近一次恢复失败原因（V86）。 */
    private String lastError;
    /** 挂起类型（V115）：WAIT=wait 工具；LLM_RETRY=上游瞬时失败后的重试。 */
    private String kind;
    /** 触发 LLM_RETRY 的上游 HTTP 状态码（无 HTTP 交互时为空，如连接超时）。 */
    private Integer errorStatus;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
}