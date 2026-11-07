package com.buukle.agent.capability.mcp.service.mcp;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extra context for a single MCP call (currently only the task-level credential).
 *
 * <p>Modeled as a separate object instead of adding mutable fields to the transport: transport
 * clients are cached and reused per mcpId (see {@code McpTransportFactory}), so hanging the
 * credential on the instance would let concurrent tasks overwrite each other's - mixing up whose
 * records a call operates on, a far worse failure than a duplicate.
 */
@Data
public class McpCallContext {

    private static final McpCallContext NONE = new McpCallContext(Collections.emptyMap());

    private final Map<String, String> extraHeaders;

    private McpCallContext(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    public static McpCallContext none() {
        return NONE;
    }

    /** Carries the task-level credential; returns none when the credential is blank, to avoid sending an empty header. */
    public static McpCallContext ofTaskCredential(String taskCredential) {
        if (taskCredential == null || taskCredential.isBlank()) {
            return NONE;
        }
        Map<String, String> headers = new LinkedHashMap<>(1);
        headers.put(McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL, taskCredential.trim());
        return new McpCallContext(Collections.unmodifiableMap(headers));
    }

    public boolean isEmpty() {
        return extraHeaders.isEmpty();
    }

    public String header(String name) {
        return extraHeaders.get(name);
    }
}
