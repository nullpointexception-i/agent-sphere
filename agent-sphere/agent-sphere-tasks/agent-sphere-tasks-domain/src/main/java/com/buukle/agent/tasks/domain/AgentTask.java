package com.buukle.agent.tasks.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task")
public class AgentTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String goal;
    private String contextJson;
    private String expectedOutputJson;
    private String config;
    private Long instanceId;
    private Long sessionId;
    private Long runId;
    private String status;
    private String resultJson;
    /** Current polling phase (execute → extract → refine); used by DB-backed polling to prevent duplicate advancement. */
    private String pollPhase;
    /** Last poll time (claim condition for DB-backed polling). */
    private LocalDateTime polledAt;
    /** Task start time (reference point for the timeout fallback). */
    private LocalDateTime startedAt;
    /**
     * Task timeout in seconds; when null, the AS-side config provides the fallback. If the caller
     * (Bole) provides it, the caller's value wins.
     */
    private Integer taskTimeoutSeconds;
    private String remark;
    private String callbackUrl;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
