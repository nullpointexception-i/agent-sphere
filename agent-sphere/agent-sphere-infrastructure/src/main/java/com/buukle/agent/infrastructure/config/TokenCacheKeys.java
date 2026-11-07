package com.buukle.agent.infrastructure.config;

/**
 * Login session cache conventions.
 *
 * <p>The key uses the SHA-256 digest of the token, so the plaintext token never appears in a Redis key.
 */
public final class TokenCacheKeys {

    public static final String USER_TOKEN_PREFIX = "token:user:";

    /** Cache TTL: revocation deletes explicitly; the TTL is only a fallback for exceptional cases. */
    public static final long USER_TOKEN_CACHE_MINUTES = 5;

    private TokenCacheKeys() {
    }
}
