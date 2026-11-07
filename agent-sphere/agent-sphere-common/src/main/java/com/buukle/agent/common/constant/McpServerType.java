package com.buukle.agent.common.constant;

/**
 * MCP transport/server types shared by every module.
 *
 * <p>The value is persisted in {@code capability_mcp.server_type}, so it is part of the
 * database contract and must never change.
 */
public final class McpServerType {

    public static final String STDIO = "stdio";
    public static final String SSE = "sse";
    public static final String HTTP = "http";

    private McpServerType() {
    }
}
