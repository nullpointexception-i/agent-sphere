package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 梯度重试的判定与退避。这里锁死两件事：
 * 1) 哪些错误值得重试（4xx 终态不重试，避免把一次失败放大成多次）；
 * 2) 前几档就地重试、后续档挂起，且梯度/总窗口按配置推导。
 */
class LlmRetryPolicyTest {

    private static AgentRuntimeProperties.RunnerConfig config() {
        AgentRuntimeProperties.RunnerConfig cfg = new AgentRuntimeProperties.RunnerConfig();
        cfg.setLlmRetryLadder(List.of(
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20)));
        cfg.setLlmRetryInlineRungs(2);
        cfg.setLlmRetryJitter(0d);
        cfg.setLlmRetryMaxTotal(Duration.ofMinutes(5));
        return cfg;
    }

    @Test
    void transientClassification() {
        assertTrue(LlmRetryPolicy.isTransient(null, true), "连接超时（无 HTTP 状态）应重试");
        assertTrue(LlmRetryPolicy.isTransient(408, true));
        assertTrue(LlmRetryPolicy.isTransient(429, true));
        assertTrue(LlmRetryPolicy.isTransient(500, true));
        assertTrue(LlmRetryPolicy.isTransient(503, true));

        assertFalse(LlmRetryPolicy.isTransient(400, true), "4xx 是终态，重试只会放大失败");
        assertFalse(LlmRetryPolicy.isTransient(401, true));
        assertFalse(LlmRetryPolicy.isTransient(503, false), "上游明确标记不可重试时不再重试");
    }

    @Test
    void hintsMapToUserFacingCategories() {
        assertEquals(LlmRetryPolicy.HINT_CONNECT_TIMEOUT, LlmRetryPolicy.hintOf(null));
        assertEquals(LlmRetryPolicy.HINT_CONNECT_TIMEOUT, LlmRetryPolicy.hintOf(408));
        assertEquals(LlmRetryPolicy.HINT_RATE_LIMITED, LlmRetryPolicy.hintOf(429));
        assertEquals(LlmRetryPolicy.HINT_PROVIDER_BUSY, LlmRetryPolicy.hintOf(503));
    }

    @Test
    void firstRungsAreInlineThenSuspend() {
        AgentRuntimeProperties.RunnerConfig cfg = config();

        assertTrue(LlmRetryPolicy.inline(cfg, 1));
        assertTrue(LlmRetryPolicy.inline(cfg, 2));
        assertFalse(LlmRetryPolicy.inline(cfg, 3), "第 3 次起改为挂起，不占执行资源");
    }

    @Test
    void ladderDelaysFollowConfig() {
        AgentRuntimeProperties.RunnerConfig cfg = config();

        assertEquals(1_000L, LlmRetryPolicy.delayMs(cfg, 1));
        assertEquals(10_000L, LlmRetryPolicy.delayMs(cfg, 3));
        assertEquals(20_000L, LlmRetryPolicy.delayMs(cfg, 5));
        // 锯齿：第 6 档回到 1s
        assertEquals(1_000L, LlmRetryPolicy.delayMs(cfg, 6));
        assertEquals(20_000L, LlmRetryPolicy.delayMs(cfg, 25));
        // 超过梯度长度时沿用最后一档
        assertEquals(20_000L, LlmRetryPolicy.delayMs(cfg, 40));
    }

    @Test
    void totalWindowExhaustsLadder() {
        AgentRuntimeProperties.RunnerConfig cfg = config();
        assertEquals(25, LlmRetryPolicy.maxAttempts(cfg));

        // 1+3+10+15+20 + 5*(1+3+15+20) = 244s < 5min
        assertEquals(244_000L, LlmRetryPolicy.cumulativeMs(cfg, 25));
        assertFalse(LlmRetryPolicy.overTotalWindow(cfg, 25));

        cfg.setLlmRetryMaxTotal(Duration.ofMinutes(3));
        assertTrue(LlmRetryPolicy.overTotalWindow(cfg, 25), "累计退避超过总窗口后不再挂起");
    }
}
