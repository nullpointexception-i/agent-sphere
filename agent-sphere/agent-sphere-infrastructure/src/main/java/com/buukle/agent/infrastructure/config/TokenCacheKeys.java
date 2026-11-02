package com.buukle.agent.infrastructure.config;

/**
 * 登录会话缓存约定。
 *
 * <p>key 使用 token 的 SHA-256 摘要，避免明文 token 出现在 Redis key 里。
 */
public final class TokenCacheKeys {

    public static final String USER_TOKEN_PREFIX = "token:user:";

    /** 缓存 TTL：吊销时显式删除，TTL 只作为异常情况下的兜底。 */
    public static final long USER_TOKEN_CACHE_MINUTES = 5;

    private TokenCacheKeys() {
    }
}
