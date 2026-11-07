package com.buukle.agent.sso.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SsoErrorCode implements ErrorCode {
    PROVIDER_NOT_FOUND("S0001", "Identity provider not found", "Check the identity provider code"),
    PROVIDER_NOT_ENABLED("S0002", "Identity provider is disabled", "Ask an administrator to enable it"),
    AUTHORIZE_INVALID("S0003", "Invalid authorization request", "Start the login flow again"),
    STATE_MISMATCH("S0004", "Authorization state mismatch", "Start the login flow again"),
    TOKEN_EXCHANGE_FAILED("S0005", "Token exchange failed", "Retry later"),
    ID_TOKEN_INVALID("S0006", "ID token validation failed", "Log in again"),
    OTC_INVALID("S0007", "Login credential has expired", "Log in again"),
    PROVISION_FAILED("S0008", "User provisioning failed", "Retry later"),
    PROVIDER_CODE_EXISTS("S0009", "Identity provider code already exists", "Choose a different identity provider code"),
    CONNECTION_FAILED("S0010", "Connection test failed", "Check the identity provider configuration");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "sso";
    }
}