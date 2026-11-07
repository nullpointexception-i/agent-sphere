package com.buukle.agent.sso.spi;

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
}
