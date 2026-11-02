package com.buukle.agent.common.event;

import lombok.Data;

import java.util.List;

/**
 * 登录 token 被吊销后发布，用于清理 Redis 中的 token → 用户缓存。
 *
 * <p>为什么用事件而不是直接删缓存：缓存实现（{@code CacheService}）在 infrastructure，
 * 而吊销发生在 instance-service。让 service 反向依赖 infrastructure 会成环，事件是唯一
 * 符合现有依赖方向的通知方式。
 *
 * <p>只携带 hash，不携带明文 token。
 */
@Data
public class UserTokenRevokedEvent {
    private List<String> tokenHashes;
}
