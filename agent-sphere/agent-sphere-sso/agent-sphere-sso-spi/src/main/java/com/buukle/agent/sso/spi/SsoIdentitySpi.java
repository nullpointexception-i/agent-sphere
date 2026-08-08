package com.buukle.agent.sso.spi;

/**
 * 通过 SSO 身份（provider code + subject）解析调用方。
 * 供外部能力开放接口（completions / tasks）在业务层做认证使用。
 */
public interface SsoIdentitySpi {

    /**
     * 按第三方身份 code + subject 反查 agent-sphere 用户。
     *
     * @return 解析到的身份；未找到返回 null
     */
    ResolvedIdentityVO resolveByCodeSubject(String providerCode, String subject);
}
