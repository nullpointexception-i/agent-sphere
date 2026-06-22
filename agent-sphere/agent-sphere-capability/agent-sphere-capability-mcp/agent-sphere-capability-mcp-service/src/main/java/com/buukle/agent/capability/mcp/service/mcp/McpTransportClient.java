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
     * Check if the client is still connected/initialized.
     */
    boolean isConnected();
}
