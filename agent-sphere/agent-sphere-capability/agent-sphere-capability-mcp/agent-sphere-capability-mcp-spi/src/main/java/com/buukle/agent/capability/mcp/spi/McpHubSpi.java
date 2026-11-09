package com.buukle.agent.capability.mcp.spi;

import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;

import java.util.List;

/** Read-only access to tenant MCP Hub entries for the runtime. */
public interface McpHubSpi {

    /** Hub entries by id, filtered to the runtime's visible tenant chain. */
    List<McpVO> listHubMcpsByIds(List<Long> hubIds);

    /** One hub entry, or null when it no longer exists or is not visible. */
    McpVO getHubMcp(Long hubId);

    /** Live tool list of a hub MCP server, or an empty list when unreachable. */
    List<com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO> listHubTools(Long hubId);

    /** Visible hub entry by server URL, used when executing a followed hub MCP. */
    McpVO findHubByServerUrl(String serverUrl);
}
