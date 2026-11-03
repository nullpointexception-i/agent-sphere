package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LLM 瞬时失败的梯度退避策略（V115）。
 *
 * <p>为什么不能只 sleep：上游 503/超时经常持续几十秒到几分钟，同轮 sleep 会把 run 一直挂在
 * RUNNING 上（虽然虚拟线程不占 OS 线程，但 run 与轮次预算都被占着）。因此前几档就地重试，
 * 后续档位改为**挂起**（WAITING + kind=LLM_RETRY），交给 wait 调度器到点唤醒。
 *
 * <p>判据只有一条：本轮**没有任何输出分片、没有任何 tool call** 才允许重试 —— 已产生副作用
 * 的轮次重跑会重复执行工具。
 */
public final class LlmRetryPolicy {

    private LlmRetryPolicy() {
    }

    /** 错误分类 code（落 agent_pending_wait.reason，前端据此选文案）。 */
    public static final String HINT_PROVIDER_BUSY = "provider_busy";
    public static final String HINT_CONNECT_TIMEOUT = "connect_timeout";
    public static final String HINT_RATE_LIMITED = "rate_limited";
    public static final String HINT_UNKNOWN = "unknown_transient";

    /** 是否属于「瞬时、值得重试」的上游错误。 */
    public static boolean isTransient(Integer httpStatus, boolean retryable) {
        if (!retryable) {
            return false;
        }
        // 无 HTTP 交互（连接超时/被重置）、408/429、5xx
        return httpStatus == null || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }

    /** 错误分类 code。 */
    public static String hintOf(Integer httpStatus) {
        if (httpStatus == null) {
            return HINT_CONNECT_TIMEOUT;
        }
        if (httpStatus == 408) {
            return HINT_CONNECT_TIMEOUT;
        }
        if (httpStatus == 429) {
            return HINT_RATE_LIMITED;
        }
        if (httpStatus >= 500) {
            return HINT_PROVIDER_BUSY;
        }
        return HINT_UNKNOWN;
    }

    /** 梯度长度 = 最多尝试次数。 */
    public static int maxAttempts(AgentRuntimeProperties.RunnerConfig cfg) {
        return Math.max(1, cfg.getLlmRetryLadder().size());
    }

    /** 第 attempt 次失败（1 起）是否就地重试。 */
    public static boolean inline(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        return attempt <= Math.max(0, cfg.getLlmRetryInlineRungs());
    }

    /** 第 attempt 次失败（1 起）的退避时长；越界返回 0。 */
    public static long delayMs(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        List<Duration> ladder = cfg.getLlmRetryLadder();
        if (ladder == null || ladder.isEmpty() || attempt < 1) {
            return 0L;
        }
        int index = Math.min(attempt, ladder.size()) - 1;
        long base = ladder.get(index).toMillis();
        double jitter = cfg.getLlmRetryJitter();
        if (jitter <= 0) {
            return base;
        }
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * Math.min(jitter, 0.5);
        return Math.max(100L, (long) (base * factor));
    }

    /**
     * 累积到第 attempt 次失败（1 起）的理论总耗时，用于总窗口判断。
     *
     * <p>刻意用配置推导而不是记时间戳：挂起/恢复跨进程，多一个时间字段就多一处不一致。
     */
    public static long cumulativeMs(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        long total = 0L;
        for (int i = 1; i <= attempt; i++) {
            List<Duration> ladder = cfg.getLlmRetryLadder();
            if (ladder == null || ladder.isEmpty()) {
                break;
            }
            total += ladder.get(Math.min(i, ladder.size()) - 1).toMillis();
        }
        return total;
    }

    /** 是否已超过总重试窗口。 */
    public static boolean overTotalWindow(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        long max = cfg.getLlmRetryMaxTotal() == null ? 0L : cfg.getLlmRetryMaxTotal().toMillis();
        return max > 0 && cumulativeMs(cfg, attempt) > max;
    }
}
