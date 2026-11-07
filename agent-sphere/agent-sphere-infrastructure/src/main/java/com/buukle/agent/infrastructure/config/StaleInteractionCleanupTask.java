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
 * Cleans up LLM turn records left stale by a process interruption.
 *
 * <p>Main Agent turns are pre-created as RUNNING before the call; if the Pod is killed before the call
 * completes, the record stays RUNNING forever. This marks records past the threshold as FAILED, which both
 * keeps the timeline's display state converged and serves as an alert signal that a turn did not finish.
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
