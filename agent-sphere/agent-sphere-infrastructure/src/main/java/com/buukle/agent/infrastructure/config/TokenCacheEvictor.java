package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.event.UserTokenRevokedEvent;
import com.buukle.agent.infrastructure.persistence.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Evicts the Redis cache immediately after a token is revoked.
 *
 * <p>Why this is needed: {@code AuthInterceptor} caches "token → user" in Redis (shared across replicas).
 * If only the DB is updated without deleting the cache, a logged-out token remains usable until TTL expiry,
 * and the security semantics do not hold.
 *
 * <p>Why an event: revocation happens in instance-service, while {@code CacheService} lives in
 * infrastructure. A direct call would create a reverse dependency; an event is the smallest change that fits
 * the existing layering.
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
