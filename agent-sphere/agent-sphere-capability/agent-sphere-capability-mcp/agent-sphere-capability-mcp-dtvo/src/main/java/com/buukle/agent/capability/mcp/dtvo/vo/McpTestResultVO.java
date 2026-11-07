package com.buukle.agent.capability.mcp.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** MCP connection test result. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpTestResultVO implements Serializable {
    private boolean ok;
    private String serverType;
    private String protocolVersion;
    private int toolCount;
    private String message;
}