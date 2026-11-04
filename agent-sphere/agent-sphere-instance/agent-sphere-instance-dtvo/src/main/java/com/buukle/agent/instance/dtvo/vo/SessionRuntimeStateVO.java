package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话运行态：前端发送锁 / pending 判定的唯一权威来源。
 *
 * <p>所有字段都直接来自来源表（{@code agent_run} / 澄清 / 审批 / 队列），
 * 不读 {@code agent_timeline}。
 */
@Data
public class SessionRuntimeStateVO implements Serializable {
    private Long sessionId;
    private Long runId;
    /** PENDING / RUNNING / WAITING / AWAITING_USER / 终态；无活动 run 时为 null。 */
    private String runStatus;
    private Integer loopCount;
    private Integer loopLimit;
    private String runMode;
    private Integer pendingClarifications;
    private Integer pendingToolApprovals;
    private Integer pendingQueue;
    private Boolean canSend;
    /** 挂起类型：WAIT / LLM_RETRY（runStatus=WAITING 时有值）。 */
    private String waitKind;
    /** LLM 重试档位（1..N）与总档位，前端展示“k/N”。 */
    private Integer retryAttempt;
    private Integer retryMax;
    /** 下次重试的时间点（epoch ms），用于前端读秒。 */
    private Long nextRetryAt;
    /** 距下次重试的毫秒数，由服务端计算，避免浏览器时钟偏差。 */
    private Long retryRemainingMs;
    /** RUNNING / CLARIFICATION / TOOL_APPROVAL；为空表示可发送。 */
    private List<String> blockReasons;
    private LocalDateTime serverTime;
}
