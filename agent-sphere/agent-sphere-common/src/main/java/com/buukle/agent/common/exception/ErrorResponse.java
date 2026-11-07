package com.buukle.agent.common.exception;

import com.buukle.agent.common.error.CommonErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.LocalDateTime;

/**
 * Standard error envelope.
 *
 * <p>{@code errorNamespace + errorCode} is the stable front-end i18n key
 * ({@code errors.<namespace>.<code>}); codes alone are not globally unique.
 */
public record ErrorResponse(String errorCode,
                            String errorNamespace,
                            String errorMessage,
                            String userTip,
                            LocalDateTime timestamp) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ErrorResponse(String errorCode, String errorNamespace, String errorMessage, String userTip) {
        this(errorCode, errorNamespace, errorMessage, userTip, LocalDateTime.now());
    }

    /** Backward-compatible constructor: unknown callers fall back to the common namespace. */
    public ErrorResponse(String errorCode, String errorMessage, String userTip) {
        this(errorCode, "common", errorMessage, userTip, LocalDateTime.now());
    }

    public static ErrorResponse of(ErrorCode ec) {
        return new ErrorResponse(ec.getCode(), ec.namespace(), ec.getMessage(), ec.getUserTip());
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
