package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;

import java.util.List;

/**
 * MCP transport client that handles the MCP protocol lifecycle:
 * initialize -> tools/list -> tools/call.
 * Supports Streamable HTTP (current spec) and legacy HTTP+SSE transport.
 */
public interface McpTransportClient extends AutoCloseable {

    /**
     * Initialize MCP session (handshake + capability negotiation).
     */
    void initialize();

    /**
     * Discover available tools from the MCP server.
     */
    List<McpToolInfoVO> listTools();

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName      the tool name as defined by the server
     * @param argumentsJson JSON arguments as a string
     * @return the tool result as a string
     */
    String callTool(String toolName, String argumentsJson);

    /**
     * Tool call with call context: extra request headers (such as the task-level credential) apply
     * to this single request.
     *
     * <p>The default implementation ignores the context so existing implementations need no change;
     * transports that must inject task credentials override this method.
     */
    default String callTool(String toolName, String argumentsJson, McpCallContext callContext) {
        return callTool(toolName, argumentsJson);
    }

    /**
     * Check if the client is still connected/initialized.
     */
    boolean isConnected();

    /**
     * MCP protocol version negotiated during the handshake; null when not initialized.
     */
    default String negotiatedProtocolVersion() {
        return null;
    }
}
