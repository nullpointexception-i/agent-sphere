package com.buukle.agent.instance.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 唤醒方（内存定时器 / cron 补偿）消费的一条等待记录。 */
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
    /** 已重试恢复次数（V86）。 */
    private Integer attempts;
    /** 挂起类型（V115）：WAIT / LLM_RETRY。 */
    private String kind;
    /** LLM_RETRY 的上游 HTTP 状态码。 */
    private Integer errorStatus;
    /** 错误分类 code（LLM_RETRY 时有值）：provider_busy / connect_timeout / rate_limited … */
    private String reason;
    /** 最近一次错误原文（展示用）。 */
    private String lastError;

    public PendingWaitVO() {
    }

    /** 兼容既有调用方（wait 工具链路）的 8 参构造；kind 等新字段走 setter。 */
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