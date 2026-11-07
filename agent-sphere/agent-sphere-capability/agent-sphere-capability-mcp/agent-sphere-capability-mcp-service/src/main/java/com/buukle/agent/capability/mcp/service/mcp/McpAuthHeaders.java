package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsing of authConfig (the outbound auth headers configured at registration time).
 *
 * <p>Centralized here because both transports (Streamable / Legacy SSE) previously carried a copy
 * of the same **defective** implementation:
 * <ol>
 *   <li>a parse failure only logged a warn and continued - the request then went out with **no auth
 *       header at all**, and the downstream reported "missing Authorization / 401" while the root
 *       cause was AS's registration config, completely derailing troubleshooting;</li>
 *   <li>that warn wrote the **raw** authConfig into the logs - which holds the token;</li>
 *   <li>a value that was not a JSON object (for example a bare pasted token) was likewise silently ignored.</li>
 * </ol>
 *
 * <p>Current semantics:
 * <ul>
 *   <li>blank -> a legal "no auth needed", returns an empty map, but logs a WARN (this kind of
 *       problem was hard to diagnose precisely because it was "too quiet");</li>
 *   <li>invalid (parse failure / not an object / non-scalar value) -> **throws immediately**, with a
 *       shape hint in the message and **no echo of the raw value**.</li>
 * </ul>
 */
@Slf4j
public final class McpAuthHeaders {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Example used in the shape hint, helping users write the correct format. */
    static final String EXPECTED_SHAPE = "{\"Authorization\":\"Bearer <token>\"}";

    private McpAuthHeaders() {
    }

    /**
     * Parses into headers to be injected. Blank returns an empty map; invalid input throws
     * {@link CapabilityMcpErrorCode#MCP_AUTH_CONFIG_INVALID}. The result is immutable and safe to
     * hold long-term (transports are cached and reused per mcpId).
     */
    public static Map<String, String> parse(String authConfigJson) {
        if (authConfigJson == null || authConfigJson.isBlank()) {
            // No error: MCP servers that need no auth really do exist. But leave a trace - if the
            // server actually requires auth, this WARN is the only clue linking "downstream 401"
            // to "missing config on our side".
            log.warn("MCP authConfig is not configured: outbound requests carry no auth headers; if the target MCP Server requires auth it will return an auth-related error");
            return Collections.emptyMap();
        }
        JsonNode auth;
        try {
            auth = JSON.readTree(authConfigJson);
        } catch (Exception e) {
            // Do not echo the raw value (may contain a token); only give the shape hint.
            throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                    "authConfig is not valid JSON; expected shape " + EXPECTED_SHAPE);
        }
        if (auth == null || !auth.isObject()) {
            throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                    "authConfig must be a JSON object (key = header name, value = header value); expected shape " + EXPECTED_SHAPE
                            + "; if you only pasted a token, wrap it as {\"Authorization\":\"Bearer <token>\"}");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Iterator<String> fields = auth.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            JsonNode value = auth.get(key);
            if (key == null || key.isBlank()) {
                throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                        "authConfig contains an empty header name; expected shape " + EXPECTED_SHAPE);
            }
            if (value == null || !value.isValueNode()) {
                throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                        "authConfig values must be string scalars (" + key + " is not); expected shape " + EXPECTED_SHAPE);
            }
            headers.put(key, value.asText());
        }
        return Collections.unmodifiableMap(headers);
    }
}
