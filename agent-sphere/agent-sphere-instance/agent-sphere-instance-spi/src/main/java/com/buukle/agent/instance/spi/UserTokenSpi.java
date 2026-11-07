package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.IssuedTokenVO;
import com.buukle.agent.instance.dtvo.vo.UserSessionVO;

import java.util.List;

/**
 * Login session contract: issue, validate, revoke.
 *
 * <p>A user may hold several valid sessions; logging in from any surface never overwrites the others.
 */
public interface UserTokenSpi {

    /** Issue a new session and return the plaintext token with its expiry time. */
    IssuedTokenVO issue(Long userId, String source, String loginIp, String userAgent);

    /** Validate a token and return userId when valid; also performs sliding renewal throttled to 7 days. */
    Long validate(String rawToken);

    /** Revoke a single token (logout). */
    void revoke(String rawToken);

    /** Revoke all valid tokens of a user (sign out of all devices). */
    int revokeAll(Long userId);

    /** Revoke all sessions of a user except the given token (keeps the current device after a password change). */
    int revokeAllExcept(Long userId, String keepRawToken);

    /** Revoke one session of a user (by id; it must belong to that user). */
    void revokeById(Long userId, Long sessionId);

    /** List a user's valid sessions; currentTokenHash marks the current session and may be null. */
    List<UserSessionVO> listActive(Long userId, String currentTokenHash);

    /** Look up the surface (CONSOLE/WIDGET) by token hash; used for browser connection registration, returns null when not found. */
    String findSourceByHash(String tokenHash);
}
