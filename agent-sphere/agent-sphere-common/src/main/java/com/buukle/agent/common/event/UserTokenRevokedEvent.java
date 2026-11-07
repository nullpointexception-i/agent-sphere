package com.buukle.agent.common.event;

import lombok.Data;

import java.util.List;

/**
 * Published after a login token is revoked, used to clear the token → user cache in Redis.
 *
 * <p>Why an event instead of deleting the cache directly: the cache implementation
 * ({@code CacheService}) lives in infrastructure, while revocation happens in instance-service.
 * Making the service depend back on infrastructure would create a cycle, so an event is the only
 * notification path that fits the existing dependency direction.
 *
 * <p>Carries hashes only, never plaintext tokens.
 */
@Data
public class UserTokenRevokedEvent {
    private List<String> tokenHashes;
}
