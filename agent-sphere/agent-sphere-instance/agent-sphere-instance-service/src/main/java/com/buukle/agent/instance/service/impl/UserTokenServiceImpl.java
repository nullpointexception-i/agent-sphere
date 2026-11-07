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
 * Login session implementation.
 *
 * <p>Core constraints:
 * <ul>
 *   <li>only the SHA-256 digest of the token is stored; the plaintext appears only in the issuance response;</li>
 *   <li>issuance never overwrites an existing session — this is the root-cause fix for "console and widget kicking each other offline";</li>
 *   <li>revocation notifies infrastructure through an event to clear the Redis cache, avoiding a reverse dependency.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTokenServiceImpl
        extends ServiceImpl<AgentUserTokenMapper, AgentUserToken>
        implements UserTokenSpi {

    /** Session validity period (sliding renewal). */
    private static final int TOKEN_TTL_DAYS = 30;
    /** Write-throttling window for last_used_at. */
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

    @Override
    public String findSourceByHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return null;
        }
        AgentUserToken token = lambdaQuery()
                .eq(AgentUserToken::getTokenHash, tokenHash)
                .last("LIMIT 1")
                .one();
        return token != null ? token.getSource() : null;
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
            // A renewal failure must not affect this authentication: the token itself is still valid,
            // only its expiry is not extended
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
