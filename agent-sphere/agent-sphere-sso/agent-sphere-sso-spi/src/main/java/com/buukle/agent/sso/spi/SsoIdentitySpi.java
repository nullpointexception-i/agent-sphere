package com.buukle.agent.sso.spi;

import java.util.Collection;
import java.util.Map;

/**
 * Resolve the caller from an SSO identity (provider code + subject).
 * Used by the external capability API (completions / tasks) for authentication at the business layer.
 */
public interface SsoIdentitySpi {

    /**
     * Look up an agent-sphere user by third-party identity code + subject.
     *
     * @return the resolved identity; null when not found
     */
    ResolvedIdentityVO resolveByCodeSubject(String providerCode, String subject);

    /**
     * Batch display labels for user ids. Each user's latest identity wins; the label subject is the
     * IdP display username (preferred_username), falling back to the raw subject.
     */
    Map<Long, SsoUserLabelVO> labelsByUserIds(Collection<Long> userIds);
}
