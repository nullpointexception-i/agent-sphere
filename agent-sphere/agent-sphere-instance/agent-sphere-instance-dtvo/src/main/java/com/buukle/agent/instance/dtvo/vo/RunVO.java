package com.buukle.agent.instance.dtvo.vo;

import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RunVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long threadId;
    private String finishStatus;
    private String type;
    private String userMessage;
    private String assistantReply;
    /** Accumulated model reasoning/thinking text (persisted for terminal runs, used when rendering history) */
    private String reasoning;
    private String intentClassification;
    /** Chat image attachment references ([{"fileKey","contentType"}]): echoed by the Timeline USER row */
    private List<RunAttachment> attachments;
    private String status;
    /** Snapshot of session run mode at creation: WRITE / READ_ONLY. */
    private String runMode;
    /** Snapshot of the instance completion contract for this run. */
    private String completionProtocol;
    /** Loop cap hit and forced finalization applied (the task guard fails the task on this) */
    private Boolean loopCapped;
    /**
     * Agent loop turns already consumed by this run (persisted since V82).
     *
     * <p>Previously loopCount was a local variable in SessionRunner#runInternal, reset to zero on
     * every suspend/resume (clarification AWAITING_USER / wait WAITING), so maxLoopCount only
     * constrained a "single call" and the model could suspend repeatedly to keep going forever.
     * Now it is read back from the database on resume, making the loop budget truly per-run.
     */
    private Integer loopCount;
    /**
     * Loop limit in effect for this run (pinned since V88); null means it has not been recorded yet
     * and will be resolved and written on first entry.
     */
    private Integer loopLimit;
    /**
     * Failure reason (since V87). Previously a failed run left no reason at all and it could only be
     * inferred from indirect signals.
     */
    private String errorMessage;
    /** Suspend type (V115): WAIT / LLM_RETRY. */
    private String waitKind;
    /** Number of LLM transient failures so far (V115). */
    private Integer retryAttempt;
    /** Graded retry cap (derived from configuration; the frontend shows "k/N" when WAITING and present). */
    private Integer retryMax;
    /** Next retry time (epoch ms; the frontend counts down). */
    private Long nextRetryAt;
    /** Error category code; the frontend picks the copy based on it. */
    private String retryHint;
    /** Upstream HTTP status code that triggered the retry. */
    private Integer retryHttpStatus;
    /** Raw error text (shown in a tooltip). */
    private String retryDetail;
    /** Primary model route actually used by this run (V116). */
    private Long modelRouteId;
    /** Reasoning effort pinned for this run (V118). */
    private String reasoningEffort;
    /** Reasoning budget pinned for this run (Qwen, V118). */
    private Integer reasoningBudgetTokens;
    private String delivery;
    private String createdBy;
    private String createdAt;
    /** Whether this run is a clarification answer (userMessage carries the clarification resume prefix); the view layer uses this to skip a separate user bubble */
    private boolean clarificationResponse;
    private List<ClarificationVO> clarifications;
    /** Transient flag: when true, this run's kernel context does not register the ask_clarification tool (not persisted, not serialized externally) */
    @JsonIgnore
    private Boolean noClarification;
    /** LLM usage aggregation for this run (present on terminal runs; backfilled in lists, included in single lookups). */
    private RunUsageVO usageSummary;
}
