package com.buukle.agent.capability.mcp.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
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
     * Discover tools from an MCP server at runtime.
     * Implemented in Phase 5 with full MCP protocol client.
     * Returns empty list if not yet implemented or if discovery fails.
     */
    default List<McpToolInfoVO> listMcpTools(Long mcpId) {
        return List.of();
    }
}
