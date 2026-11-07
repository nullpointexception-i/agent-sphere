package com.buukle.agent.infrastructure.config;

import com.buukle.agent.instance.repository.AgentUserTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up expired / revoked login sessions.
 *
 * <p>A session table that only grows keeps bloating; tokens are temporary credentials with no business
 * value past the retention period, so this performs a physical delete just like the audit log cleanup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTokenCleanupTask {

    private static final String CLEANUP_LOCK_KEY = "scheduler:user-token-cleanup";

    private final AgentUserTokenMapper tokenMapper;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.auth.token-retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${buukle.agent.auth.token-cleanup-cron:0 20 3 * * ?}")
    public void cleanup() {
        // Multi-replica: only one replica performs the sweep at any moment
        RLock lock = redissonClient.getLock(CLEANUP_LOCK_KEY);
        if (!lock.tryLock()) {
            return;
        }
        try {
            int deleted = tokenMapper.deleteStale(retentionDays);
            if (deleted > 0) {
                log.info("User token cleanup: removed {} sessions (retention {}d)", deleted, retentionDays);
            }
        } finally {
            lock.unlock();
        }
    }
}
