package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Graded backoff policy for transient LLM failures (V115).
 *
 * <p>Why not just sleep: upstream 503/timeouts often last tens of seconds to minutes, and sleeping within the same turn keeps the run pinned
 * in RUNNING (virtual threads do not occupy OS threads, but the run and its loop budget stay occupied). So the first few steps retry in place,
 * and later steps switch to **suspension** (WAITING + kind=LLM_RETRY), leaving the wait scheduler to wake the run when due.
 *
 * <p>There is only one criterion: a retry is allowed only if this turn produced **no output chunks and no tool calls** — re-running a turn that
 * already caused side effects would execute its tools twice.
 */
public final class LlmRetryPolicy {

    private LlmRetryPolicy() {
    }

    /** Error classification code (persisted to agent_pending_wait.reason; the frontend picks the message from it). */
    public static final String HINT_PROVIDER_BUSY = "provider_busy";
    public static final String HINT_CONNECT_TIMEOUT = "connect_timeout";
    public static final String HINT_RATE_LIMITED = "rate_limited";
    public static final String HINT_UNKNOWN = "unknown_transient";

    /** Whether this is a transient upstream error worth retrying. */
    public static boolean isTransient(Integer httpStatus, boolean retryable) {
        if (!retryable) {
            return false;
        }
        // No HTTP interaction (connection timeout / reset), 408/429, 5xx
        return httpStatus == null || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }

    /** Error classification code. */
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

    /** Ladder length = maximum number of attempts. */
    public static int maxAttempts(AgentRuntimeProperties.RunnerConfig cfg) {
        return Math.max(1, cfg.getLlmRetryLadder().size());
    }

    /** Whether to retry in place after failure number attempt (1-based). */
    public static boolean inline(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        return attempt <= Math.max(0, cfg.getLlmRetryInlineRungs());
    }

    /** Backoff duration after failure number attempt (1-based); returns 0 when out of range. */
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
     * Theoretical total elapsed time after accumulating up to failure number attempt (1-based), used for the overall window check.
     *
     * <p>Deliberately derived from config instead of recording timestamps: suspension/resume crosses processes, and every extra time field is another place to get out of sync.
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

    /** Whether the overall retry window has been exceeded. */
    public static boolean overTotalWindow(AgentRuntimeProperties.RunnerConfig cfg, int attempt) {
        long max = cfg.getLlmRetryMaxTotal() == null ? 0L : cfg.getLlmRetryMaxTotal().toMillis();
        return max > 0 && cumulativeMs(cfg, attempt) > max;
    }
}
