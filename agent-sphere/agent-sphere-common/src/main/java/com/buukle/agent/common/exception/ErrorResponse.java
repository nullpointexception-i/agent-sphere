package com.buukle.agent.common.exception;

import com.buukle.agent.common.error.CommonErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.LocalDateTime;

public record ErrorResponse(String errorCode, String errorMessage, String userTip, LocalDateTime timestamp) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ErrorResponse(String errorCode, String errorMessage, String userTip) {
        this(errorCode, errorMessage, userTip, LocalDateTime.now());
    }

    public static ErrorResponse of(CommonErrorCode ec) {
        return new ErrorResponse(ec.getCode(), ec.getMessage(), ec.getUserTip());
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
