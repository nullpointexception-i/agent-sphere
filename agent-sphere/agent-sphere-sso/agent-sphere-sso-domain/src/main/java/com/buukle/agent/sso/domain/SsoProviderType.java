package com.buukle.agent.sso.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SsoProviderType {
    OIDC("OIDC");

    private final String value;
}
