package com.buukle.agent.runtime.orchestration.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrchestrationErrorCode implements ErrorCode {
    SESSION_NOT_FOUND("A0101", "Session not found", "Check the session ID"),
    INSTANCE_NOT_FOUND("A0102", "Agent instance not found", "Check the instance ID"),
    MODEL_ROUTE_NOT_FOUND("A0103", "Model route is not configured", "Configure a model route for this instance"),
    MODEL_ROUTE_NO_API_KEY("A0104", "The provider has no API key configured", "Set the active API key in API key management first");

    private final String code;
    private final String message;
    private final String userTip;
}
