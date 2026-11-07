package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long threadId;
    private String finishStatus;
    private String type;
    private String userMessage;
    private String assistantReply;
    private String reasoning;
    private String intentClassification;
    private Long taskId;
    private String status;
    /** Snapshot of session run mode at creation: WRITE / READ_ONLY. */
    private String runMode;
    /** Snapshot of the instance completion contract for this run. */
    private String completionProtocol;
    /**
     * Snapshot taken at creation: autonomous tasks (Worker / task-type runs) may not ask the user
     * for clarification.
     *
     * <p>This must be persisted (V100). It used to live only on the in-memory RunVO, so any recovery
     * path (claimForResume / getRun / approval or wait recovery) that rebuilt the run from the DB
     * lost it, making ContextPreparer decide noClarification=false: finish was no longer registered
     * while ask_clarification was, leaving the STRICT_FINISH worker without a finalization tool and
     * spinning in place.
     */
    private Boolean noClarification;
    /** Chat image attachment reference JSON ([{"fileKey","contentType"}]); echoed by the Timeline USER row. */
    private String attachments;
    /** Loop cap hit and forced finalization applied (the task guard fails the task on this, so a "half-finished" result cannot pass as done). */
    private Boolean loopCapped;
    /** Consumed agent loop turns; incremented across suspend/resume so maxLoopCount is a true per-run budget. */
    private Integer loopCount;
    /**
     * Loop limit in effect for this run (pinned since V88).
     *
     * <p>Previously the limit was re-resolved on every loop entry, while a run has 5 re-entry points
     * and only the first one runs on the task request thread (holding TaskLoopLimitHolder); the other
     * entries run on scheduler threads, lose the TASK layer and fall back to a smaller value.
     * Only re-resolved when this is null.
     */
    private Integer loopLimit;
    /**
     * Failure reason (since V87). Previously a failed run kept no reason at all: the errorMessage in
     * the event was dropped and there was no matching column, so "why did it fail" could never be
     * answered afterwards.
     */
    private String errorMessage;
    /** Suspend type (V115): WAIT=wait tool; LLM_RETRY=retry after a transient upstream failure. */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String waitKind;
    /** Number of LLM transient failures so far (V115), used for graded backoff; reset to zero after a successful turn. */
    private Integer retryAttempt;
    /** Primary model route actually used by this run (V116); resume uses it to avoid re-resolving from the instance and switching models. */
    private Long modelRouteId;
    /** Reasoning effort pinned for this run (V118). */
    private String reasoningEffort;
    /** Reasoning budget pinned for this run (Qwen thinking_budget, V118). */
    private Integer reasoningBudgetTokens;
    @TableLogic
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
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
