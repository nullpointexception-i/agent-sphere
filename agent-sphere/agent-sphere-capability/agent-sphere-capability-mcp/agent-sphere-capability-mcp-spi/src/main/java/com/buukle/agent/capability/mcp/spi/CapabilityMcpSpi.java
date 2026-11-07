package com.buukle.agent.capability.mcp.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpTestResultVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;

import java.time.LocalDateTime;
import java.util.List;

public interface CapabilityMcpSpi {
    McpVO createMcp(CreateMcpDTO dto);

    McpVO getMcp(Long id);

    List<McpVO> listMcps(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<McpVO> pageMcps(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    McpVO updateMcp(Long id, CreateMcpDTO dto);

    void deleteMcp(Long id);

    void batchDeleteMcp(java.util.List<Long> ids);

    List<McpVO> listMcpsByIds(List<Long> ids);

    String executeTool(String serverUrl, String toolName, String argumentsJson);

    /**
     * Tool call with a task-level credential.
     *
     * <p>The credential is injected as the {@code X-Task-Mcp-Credential} header so the MCP server can
     * identify the owner of this call (Bole uses it to tell which user's research task it belongs to).
     * When null it is fully equivalent to the three-argument version.
     *
     * <p>Added as an overload instead of changing the three-argument signature: existing
     * implementations and callers (paths outside ToolExecutor) need no change.
     */
    default String executeTool(String serverUrl, String toolName, String argumentsJson, String taskMcpCredential) {
        return executeTool(serverUrl, toolName, argumentsJson);
    }

    /**
     * Discover tools from an MCP server at runtime.
     * Implemented in Phase 5 with full MCP protocol client.
     * Returns empty list if not yet implemented or if discovery fails.
     */
    default List<McpToolInfoVO> listMcpTools(Long mcpId) {
        return List.of();
    }

    /**
     * Connection test: performs an initialize handshake against the registered server and returns
     * reachability, protocol version and tool count.
     */
    McpTestResultVO testConnection(Long mcpId);

    /**
     * Calls one tool of the MCP identified by mcpId (used by the frontend "try it" action).
     * See {@link #executeTool(String, String, String)} for the serverUrl variant.
     */
    String callToolByMcpId(Long mcpId, String toolName, String argumentsJson);
}
