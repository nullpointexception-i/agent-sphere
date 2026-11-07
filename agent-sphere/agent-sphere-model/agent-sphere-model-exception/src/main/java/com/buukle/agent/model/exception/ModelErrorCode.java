package com.buukle.agent.model.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ModelErrorCode implements ErrorCode {
    PROVIDER_NOT_FOUND("A0011", "Provider not found", "Check the provider ID"),
    API_KEY_NOT_FOUND("A0012", "API key not found", "Check the API key ID"),
    ROUTE_NOT_FOUND("A0013", "Route not found", "Check the route ID"),
    ROUTE_FALLBACK_NOT_FOUND("A0014", "Fallback route not found", "Check the fallback route ID"),
    ROUTE_FALLBACK_CYCLE("A0015", "Fallback chain contains a cycle", "Check the fallback route configuration"),
    LLM_CALL_FAILED("C0001", "LLM call failed", "The LLM service is temporarily unavailable; retry later");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "model";
    }
}
