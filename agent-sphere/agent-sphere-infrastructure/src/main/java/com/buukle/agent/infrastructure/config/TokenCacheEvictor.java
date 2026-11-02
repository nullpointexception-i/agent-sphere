package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.event.UserTokenRevokedEvent;
import com.buukle.agent.infrastructure.persistence.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * token 被吊销后立即清理 Redis 缓存。
 *
 * <p>为什么需要它：{@code AuthInterceptor} 把「token → 用户」缓存在 Redis（跨副本共享）。
 * 只改 DB 不删缓存的话，已登出的 token 在 TTL 内仍然可用，安全语义不成立。
 *
 * <p>为什么用事件：吊销发生在 instance-service，而 {@code CacheService} 在 infrastructure。
 * 直接调用会形成反向依赖，事件是符合现有分层的最小改动。
 */
@Component
@RequiredArgsConstructor
public class TokenCacheEvictor {

    private final CacheService cacheService;

    @EventListener
    public void onRevoked(UserTokenRevokedEvent event) {
        if (event.getTokenHashes() == null) {
            return;
        }
        for (String hash : event.getTokenHashes()) {
            cacheService.delete(TokenCacheKeys.USER_TOKEN_PREFIX + hash);
        }
    }
}
