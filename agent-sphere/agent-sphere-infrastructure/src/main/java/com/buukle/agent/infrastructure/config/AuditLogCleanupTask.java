package com.buukle.agent.infrastructure.config;

import com.buukle.agent.instance.repository.AgentAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogCleanupTask {

    private static final String CLEANUP_LOCK_KEY = "scheduler:audit-cleanup";

    private final AgentAuditLogMapper auditLogMapper;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.audit.frontend-retention-days:7}")
    private int frontendRetentionDays;

    @Value("${buukle.agent.audit.backend-retention-days:90}")
    private int backendRetentionDays;

    @Scheduled(cron = "${buukle.agent.audit.cleanup-cron:0 0 3 * * ?}")
    public void cleanup() {
        // Multi-replica: only one replica performs the sweep at any moment, the rest skip
        RLock lock = redissonClient.getLock(CLEANUP_LOCK_KEY);
        if (!lock.tryLock()) {
            return;
        }
        try {
            int deletedFrontend = auditLogMapper.deleteFrontendEventsOlderThan(frontendRetentionDays);
            int deletedBackend = auditLogMapper.deleteBackendEventsOlderThan(backendRetentionDays);
            if (deletedFrontend > 0 || deletedBackend > 0) {
                log.info("Audit cleanup: removed {} frontend events (>{}d), {} backend events (>{}d)",
                        deletedFrontend, frontendRetentionDays, deletedBackend, backendRetentionDays);
            }
        } finally {
            lock.unlock();
        }
    }
}
