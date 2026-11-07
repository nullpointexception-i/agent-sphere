package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * authConfig parsing: valid input yields auth headers, invalid input **fails loudly** (instead of
 * silently sending no auth).
 *
 * <p>These assertions map directly to a real incident: a parse failure only logged a warn and
 * continued, so the request went out without Authorization and the downstream reported
 * "missing Authorization / 401" while the root cause was AS's registration config - sending
 * troubleshooting far in the wrong direction. That warn also wrote the raw authConfig (token
 * included) to the logs.
 */
class McpAuthHeadersTest {

    @Test
    void blankAuthConfigYieldsNoHeaders() {
        // Blank is a legal "no auth needed" value and must not error, but it also means the request carries no auth header at all.
        assertEquals(Map.of(), McpAuthHeaders.parse(null));
        assertEquals(Map.of(), McpAuthHeaders.parse(""));
        assertEquals(Map.of(), McpAuthHeaders.parse("   "));
    }

    @Test
    void parsesHeaderMap() {
        Map<String, String> headers = McpAuthHeaders.parse(
                "{\"Authorization\":\"Bearer svc-token\",\"X-Tenant\":\"bole\"}");

        assertEquals("Bearer svc-token", headers.get("Authorization"));
        assertEquals("bole", headers.get("X-Tenant"));
        assertEquals(2, headers.size());
    }

    @Test
    void invalidJsonFailsLoudly() {
        BizException e = assertThrows(BizException.class, () -> McpAuthHeaders.parse("not-json"));
        assertEquals(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode(), e.getErrorCode());
        // Must include the shape hint, otherwise the user has no idea what to enter
        assertTrue(e.getMessage().contains(McpAuthHeaders.EXPECTED_SHAPE), e.getMessage());
    }

    @Test
    void bareTokenIsRejectedWithHint() {
        // The most common misconfiguration: only a bare token was pasted. Previously it was silently ignored (not a JSON object).
        BizException e = assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("human-resource-intermediary-mcp-service-token-2024"));
        assertEquals(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode(), e.getErrorCode());
        assertTrue(e.getMessage().contains("Bearer"), e.getMessage());
    }

    @Test
    void nonScalarValueIsRejected() {
        assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("{\"Authorization\":{\"token\":\"x\"}}"));
    }

    @Test
    void errorMessageMustNotLeakTheRawValue() {
        // Redaction requirement: the exception message must not echo the raw authConfig (it may contain a token).
        String secret = "super-secret-token-value-12345";
        BizException e = assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("{\"Authorization\":\"" + secret + "\", \"bad\": {} }"));

        assertFalse(e.getMessage().contains(secret), e.getMessage());
    }

    @Test
    void blankHeaderNameIsRejected() {
        assertThrows(BizException.class, () -> McpAuthHeaders.parse("{\"  \":\"v\"}"));
    }
}
