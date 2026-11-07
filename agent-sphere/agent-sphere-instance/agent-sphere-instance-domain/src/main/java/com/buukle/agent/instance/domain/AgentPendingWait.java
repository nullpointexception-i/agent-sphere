package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Suspension records of the {@code wait} builtin tool (V83). One record = one wait. */
@Data
@TableName("agent_pending_wait")
public class AgentPendingWait {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    /** Model-side tool call id: used on wake to locate and rewrite the matching tool call record. */
    private String callId;
    private Integer waitMs;
    private LocalDateTime wakeAt;
    private String status;
    private String reason;
    private LocalDateTime resumedAt;
    /** Resume retry count (V86): incremented by each requeueForRetry; over the limit the record is set to FAILED. */
    private Integer attempts;
    /** Reason for the most recent resume failure (V86). */
    private String lastError;
    /** Suspend type (V115): WAIT=wait tool; LLM_RETRY=retry after a transient upstream failure. */
    private String kind;
    /** Upstream HTTP status code that triggered LLM_RETRY (empty when there was no HTTP interaction, e.g. connect timeout). */
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