package com.buukle.agent.infrastructure.config;

import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理进程中断留下的僵死 LLM 轮次记录。
 *
 * <p>主 Agent 轮次在调用前预建为 RUNNING；若 Pod 在调用结束前被杀，记录会一直停在 RUNNING。
 * 这里把超过阈值的记录置 FAILED，既能保证 timeline 的展示状态收敛，也能作为「有轮次没跑完」的告警信号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleInteractionCleanupTask {

    private static final String CLEANUP_LOCK_KEY = "scheduler:stale-interaction-cleanup";

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.llm.stale-interaction-minutes:30}")
    private int staleMinutes;

    @Scheduled(cron = "${buukle.agent.llm.stale-interaction-cron:0 */5 * * * ?}")
    public void cleanup() {
        RLock lock = redissonClient.getLock(CLEANUP_LOCK_KEY);
        if (!lock.tryLock()) {
            return;
        }
        try {
            int failed = interactionRecordSpi.failStaleRunning(staleMinutes);
            if (failed > 0) {
                log.warn("Stale LLM interactions marked FAILED: count={}, staleMinutes={}", failed, staleMinutes);
            }
        } finally {
            lock.unlock();
        }
    }
}
