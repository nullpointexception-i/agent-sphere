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
 * 清理过期 / 已吊销的登录会话。
 *
 * <p>会话表只增不减会持续膨胀；token 是临时凭证，超过保留期后没有任何业务价值，
 * 因此这里与审计日志清理一样做物理删除。
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
        // 多副本：同一时刻仅一个副本执行清扫
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
