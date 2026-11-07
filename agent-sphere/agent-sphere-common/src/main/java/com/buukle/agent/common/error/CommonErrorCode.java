package com.buukle.agent.common.error;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    PARAM_INVALID("A0100", "Parameter validation failed", "Check the input parameters"),
    UNAUTHORIZED("A0200", "Unauthorized", "Log in first"),
    FORBIDDEN("A0300", "Forbidden", "You do not have permission to perform this action"),
    /**
     * The current surface (console / widget) may not start or continue a session on this instance —
     * blocked by the instance-level switch.
     *
     * <p>Distinct from {@link #FORBIDDEN}: that one means "this user lacks permission", this one
     * means "this entry point is not enabled".
     */
    SESSION_SURFACE_DISABLED("A0301", "This entry point cannot start a session", "Use another entry point or ask an administrator to enable it"),
    RATE_LIMITED("A0400", "Rate limit exceeded", "Retry later"),
    RESOURCE_NOT_FOUND("A0404", "Resource not found", "The requested resource does not exist"),
    INTERNAL_ERROR("B0500", "Internal server error", "Retry later"),
    SERVICE_UNAVAILABLE("B0503", "Service unavailable", "The service is temporarily unavailable; retry later");

    private final String code;
    private final String message;
    private final String userTip;
}
