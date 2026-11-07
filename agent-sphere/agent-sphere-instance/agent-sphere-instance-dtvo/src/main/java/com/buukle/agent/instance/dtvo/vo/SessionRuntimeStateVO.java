package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Session runtime state: the single authoritative source for the frontend send lock / pending checks.
 *
 * <p>All fields come directly from source tables ({@code agent_run} / clarification / approval /
 * queue); {@code agent_timeline} is never read.
 */
@Data
public class SessionRuntimeStateVO implements Serializable {
    private Long sessionId;
    private Long runId;
    /** PENDING / RUNNING / WAITING / AWAITING_USER / terminal; null when there is no active run. */
    private String runStatus;
    private Integer loopCount;
    private Integer loopLimit;
    private String runMode;
    private Integer pendingClarifications;
    private Integer pendingToolApprovals;
    private Integer pendingQueue;
    private Boolean canSend;
    /** Suspend type: WAIT / LLM_RETRY (present when runStatus=WAITING). */
    private String waitKind;
    /** LLM retry step (1..N) and the total; the frontend displays "k/N". */
    private Integer retryAttempt;
    private Integer retryMax;
    /** Time of the next retry (epoch ms), for the frontend countdown. */
    private Long nextRetryAt;
    /** Milliseconds until the next retry, computed on the server to avoid browser clock skew. */
    private Long retryRemainingMs;
    /** RUNNING / CLARIFICATION / TOOL_APPROVAL; empty means a message can be sent. */
    private List<String> blockReasons;
    private LocalDateTime serverTime;
}
