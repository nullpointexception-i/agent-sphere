package com.buukle.agent.common.exception;

import com.buukle.agent.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorResponseTest {

    @Test
    void commonErrorCarriesNamespaceAndCode() {
        ErrorResponse response = ErrorResponse.of(CommonErrorCode.PARAM_INVALID);

        assertEquals("common", response.errorNamespace());
        assertEquals("A0100", response.errorCode());
        assertTrue(response.toJson().contains("\"errorNamespace\":\"common\""));
    }

    @Test
    void bizExceptionCarriesNamespace() {
        BizException ex = new BizException(CommonErrorCode.FORBIDDEN);

        assertEquals("common", ex.getErrorNamespace());
        assertEquals("A0300", ex.getErrorCode());
    }
}
