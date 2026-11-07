package com.buukle.agent.capability.mcp.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilityMcpErrorCode implements ErrorCode {
    MCP_NOT_FOUND("A0021", "MCP tool not found", "Check the MCP ID"),
    MCP_SERVER_UNREACHABLE("C0002", "MCP server unreachable", "The MCP server connection failed; retry later"),
    MCP_EXECUTE_FAILED("C0003", "MCP tool call failed", "The tool failed; retry later"),
    MCP_LIST_TOOLS_FAILED("C0004", "Failed to list MCP tools", "Could not fetch the tool list from the server"),
    MCP_CONNECT_TEST_FAILED("C0005", "MCP connection test failed", "The connection test did not pass"),
    MCP_AUTH_CONFIG_INVALID("C0006", "Invalid MCP auth configuration", "authConfig must be a JSON object, for example {\"Authorization\":\"Bearer <token>\"}");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "capability.mcp";
    }
}
