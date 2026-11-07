package com.buukle.agent.instance.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** A wait record consumed by a waker (in-memory timer / cron compensation). */
@Data
@AllArgsConstructor
public class PendingWaitVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long runId;
    private String callId;
    private Integer waitMs;
    private LocalDateTime wakeAt;
    private String status;
    /** Number of resume retries already performed (V86). */
    private Integer attempts;
    /** Suspend type (V115): WAIT / LLM_RETRY. */
    private String kind;
    /** Upstream HTTP status code for LLM_RETRY. */
    private Integer errorStatus;
    /** Error category code (present for LLM_RETRY): provider_busy / connect_timeout / rate_limited … */
    private String reason;
    /** Raw text of the most recent error (for display). */
    private String lastError;

    public PendingWaitVO() {
    }

    /** 8-argument constructor kept for existing callers (the wait tool chain); newer fields such as kind go through setters. */
    public PendingWaitVO(Long id, Long sessionId, Long runId, String callId, Integer waitMs,
                         LocalDateTime wakeAt, String status, Integer attempts) {
        this.id = id;
        this.sessionId = sessionId;
        this.runId = runId;
        this.callId = callId;
        this.waitMs = waitMs;
        this.wakeAt = wakeAt;
        this.status = status;
        this.attempts = attempts;
    }
}