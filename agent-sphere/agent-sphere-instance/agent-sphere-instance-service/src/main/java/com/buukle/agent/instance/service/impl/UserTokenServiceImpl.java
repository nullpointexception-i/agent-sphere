package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.event.UserTokenRevokedEvent;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.instance.domain.AgentUserToken;
import com.buukle.agent.instance.dtvo.vo.IssuedTokenVO;
import com.buukle.agent.instance.dtvo.vo.UserSessionVO;
import com.buukle.agent.instance.repository.AgentUserTokenMapper;
import com.buukle.agent.instance.spi.UserTokenSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录会话实现。
 *
 * <p>核心约束：
 * <ul>
 *   <li>只存 token 的 SHA-256 摘要，明文仅在签发响应中出现；</li>
 *   <li>签发不覆盖任何已有会话 —— 这是「主站与 widget 互踢」的根因修复；</li>
 *   <li>吊销通过事件通知 infrastructure 清理 Redis 缓存，避免依赖反向。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTokenServiceImpl
        extends ServiceImpl<AgentUserTokenMapper, AgentUserToken>
        implements UserTokenSpi {

    /** 会话有效期（滑动续期）。 */
    private static final int TOKEN_TTL_DAYS = 30;
    /** last_used_at 的写库节流窗口。 */
    private static final int TOUCH_STALE_DAYS = 7;

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public IssuedTokenVO issue(Long userId, String source, String loginIp, String userAgent) {
        String raw = TokenHasher.generate();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(TOKEN_TTL_DAYS);

        AgentUserToken token = new AgentUserToken();
        token.setUserId(userId);
        token.setTokenHash(TokenHasher.sha256(raw));
        token.setTokenPrefix(TokenHasher.prefix(raw));
        token.setSource(source);
        token.setLoginIp(loginIp);
        token.setUserAgent(truncate(userAgent, 500));
        token.setExpiresAt(expiresAt);
        token.setStatus(AgentUserToken.STATUS_ACTIVE);
        save(token);

        IssuedTokenVO vo = new IssuedTokenVO();
        vo.setToken(raw);
        vo.setExpiresAt(expiresAt);
        return vo;
    }

    @Override
    public Long validate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String hash = TokenHasher.sha256(rawToken);
        AgentUserToken token = lambdaQuery()
                .eq(AgentUserToken::getTokenHash, hash)
                .eq(AgentUserToken::getStatus, AgentUserToken.STATUS_ACTIVE)
                .isNull(AgentUserToken::getRevokedAt)
                .gt(AgentUserToken::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 1")
                .one();
        if (token == null) {
            return null;
        }
        touch(hash);
        return token.getUserId();
    }

    @Override
    public void revoke(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return;
        }
        String hash = TokenHasher.sha256(rawToken);
        lambdaUpdate()
                .eq(AgentUserToken::getTokenHash, hash)
                .isNull(AgentUserToken::getRevokedAt)
                .set(AgentUserToken::getRevokedAt, LocalDateTime.now())
                .set(AgentUserToken::getStatus, AgentUserToken.STATUS_REVOKED)
                .update();
        publishRevoked(List.of(hash));
    }

    @Override
    public int revokeAll(Long userId) {
        if (userId == null) {
            return 0;
        }
        return revokeWhere(userId, null);
    }

    @Override
    public int revokeAllExcept(Long userId, String keepRawToken) {
        if (userId == null) {
            return 0;
        }
        String keepHash = StringUtils.hasText(keepRawToken) ? TokenHasher.sha256(keepRawToken) : null;
        return revokeWhere(userId, keepHash);
    }

    @Override
    public void revokeById(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        AgentUserToken token = lambdaQuery()
                .eq(AgentUserToken::getId, sessionId)
                .eq(AgentUserToken::getUserId, userId)
                .isNull(AgentUserToken::getRevokedAt)
                .one();
        if (token == null) {
            return;
        }
        lambdaUpdate()
                .eq(AgentUserToken::getId, sessionId)
                .eq(AgentUserToken::getUserId, userId)
                .isNull(AgentUserToken::getRevokedAt)
                .set(AgentUserToken::getRevokedAt, LocalDateTime.now())
                .set(AgentUserToken::getStatus, AgentUserToken.STATUS_REVOKED)
                .update();
        publishRevoked(List.of(token.getTokenHash()));
    }

    @Override
    public List<UserSessionVO> listActive(Long userId, String currentTokenHash) {
        if (userId == null) {
            return List.of();
        }
        List<AgentUserToken> tokens = lambdaQuery()
                .eq(AgentUserToken::getUserId, userId)
                .eq(AgentUserToken::getStatus, AgentUserToken.STATUS_ACTIVE)
                .isNull(AgentUserToken::getRevokedAt)
                .gt(AgentUserToken::getExpiresAt, LocalDateTime.now())
                .orderByDesc(AgentUserToken::getLastUsedAt)
                .orderByDesc(AgentUserToken::getId)
                .list();
        return tokens.stream().map(t -> toSessionVO(t, currentTokenHash)).toList();
    }

    private int revokeWhere(Long userId, String keepHash) {
        var query = lambdaQuery()
                .eq(AgentUserToken::getUserId, userId)
                .eq(AgentUserToken::getStatus, AgentUserToken.STATUS_ACTIVE)
                .isNull(AgentUserToken::getRevokedAt);
        if (keepHash != null) {
            query.ne(AgentUserToken::getTokenHash, keepHash);
        }
        List<AgentUserToken> tokens = query.list();
        if (tokens.isEmpty()) {
            return 0;
        }
        var update = lambdaUpdate()
                .eq(AgentUserToken::getUserId, userId)
                .eq(AgentUserToken::getStatus, AgentUserToken.STATUS_ACTIVE)
                .isNull(AgentUserToken::getRevokedAt);
        if (keepHash != null) {
            update.ne(AgentUserToken::getTokenHash, keepHash);
        }
        update.set(AgentUserToken::getRevokedAt, LocalDateTime.now())
                .set(AgentUserToken::getStatus, AgentUserToken.STATUS_REVOKED)
                .update();
        publishRevoked(tokens.stream().map(AgentUserToken::getTokenHash).toList());
        return tokens.size();
    }

    private void touch(String hash) {
        try {
            baseMapper.touch(hash, TOKEN_TTL_DAYS, TOUCH_STALE_DAYS);
        } catch (Exception e) {
            // 续期失败不能影响本次鉴权：token 本身仍然有效，只是有效期不延长
            log.warn("Token touch failed: {}", e.getMessage());
        }
    }

    private void publishRevoked(List<String> hashes) {
        if (hashes.isEmpty()) {
            return;
        }
        UserTokenRevokedEvent event = new UserTokenRevokedEvent();
        event.setTokenHashes(hashes);
        eventPublisher.publishEvent(event);
    }

    private static UserSessionVO toSessionVO(AgentUserToken token, String currentTokenHash) {
        UserSessionVO vo = new UserSessionVO();
        vo.setId(token.getId());
        vo.setSource(token.getSource());
        vo.setLoginIp(token.getLoginIp());
        vo.setUserAgent(token.getUserAgent());
        vo.setExpiresAt(token.getExpiresAt());
        vo.setLastUsedAt(token.getLastUsedAt());
        vo.setCreatedAt(token.getCreatedAt());
        vo.setCurrent(currentTokenHash != null && currentTokenHash.equals(token.getTokenHash()));
        return vo;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
