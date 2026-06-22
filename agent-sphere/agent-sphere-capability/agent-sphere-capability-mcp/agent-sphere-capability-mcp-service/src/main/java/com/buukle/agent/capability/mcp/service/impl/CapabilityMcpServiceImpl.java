package com.buukle.agent.capability.mcp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportClient;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@Primary
public class CapabilityMcpServiceImpl extends ServiceImpl<McpMapper, CapabilityMcp> implements CapabilityMcpService {
    private static final String SQL_LIMIT_ONE = "LIMIT 1";
    private static final String DIRECT_HTTP_HINT = "No MCP found for serverUrl={}, using direct HTTP call";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final CapabilityMcpConverter capabilityMcpConverter;
    private final McpTransportFactory mcpTransportFactory;
    private final Duration directReadTimeout;
    private final Duration directConnectTimeout;

    public CapabilityMcpServiceImpl(CapabilityMcpConverter capabilityMcpConverter,
                                    McpTransportFactory mcpTransportFactory,
                                    AgentRuntimeProperties properties) {
        this.capabilityMcpConverter = capabilityMcpConverter;
        this.mcpTransportFactory = mcpTransportFactory;
        this.directReadTimeout = properties.getMcp().getDirectReadTimeout();
        this.directConnectTimeout = properties.getMcp().getDirectConnectTimeout();
    }

    @Override
    public McpVO createMcp(CreateMcpDTO dto) {
        CapabilityMcp mcp = capabilityMcpConverter.toDO(dto);
        save(mcp);
        return capabilityMcpConverter.toVO(mcp);
    }

    @Override
    public McpVO getMcp(Long id) {
        CapabilityMcp mcp = getById(id);
        if (mcp == null) throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        return capabilityMcpConverter.toVO(mcp);
    }

    @Override
    public List<McpVO> listMcps(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.warn("listMcps called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<CapabilityMcp> list = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilityMcp::getName, keyword)
                .ge(startTime != null, CapabilityMcp::getCreatedAt, startTime)
                .le(endTime != null, CapabilityMcp::getCreatedAt, endTime)
                .orderByDesc(CapabilityMcp::getCreatedAt)
                .list();
        log.warn("listMcps result: {} rows", list.size());
        return list.stream().map(capabilityMcpConverter::toVO).toList();
    }

    @Override
    public IPage<McpVO> pageMcps(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<CapabilityMcp> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilityMcp::getName, keyword)
                .ge(startTime != null, CapabilityMcp::getCreatedAt, startTime)
                .le(endTime != null, CapabilityMcp::getCreatedAt, endTime)
                .orderByDesc(CapabilityMcp::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(capabilityMcpConverter::toVO);
    }

    @Override
    public List<McpVO> listMcpsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return lambdaQuery().in(CapabilityMcp::getId, ids).list().stream().map(capabilityMcpConverter::toVO).toList();
    }

    @Override
    public McpVO updateMcp(Long id, CreateMcpDTO dto) {
        CapabilityMcp mcp = capabilityMcpConverter.toDO(dto);
        mcp.setId(id);
        updateById(mcp);
        // Evict cached client on update
        mcpTransportFactory.evictClient(id);
        return capabilityMcpConverter.toVO(mcp);
    }

    @Override
    public void deleteMcp(Long id) {
        mcpTransportFactory.evictClient(id);
        removeById(id);
    }

    @Override
    public void batchDeleteMcp(java.util.List<Long> ids) {
        for (Long id : ids) {
            mcpTransportFactory.evictClient(id);
        }
        removeByIds(ids);
    }

    @Override
    public List<McpToolInfoVO> listMcpTools(Long mcpId) {
        McpVO mcp = getMcp(mcpId);
        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    mcpId, mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig());
            return client.listTools();
        } catch (Exception e) {
            log.error("Failed to list MCP tools for mcpId={}: {}", mcpId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String executeTool(String serverUrl, String toolName, String argumentsJson) {
        CapabilityMcp mcp = lambdaQuery()
                .eq(CapabilityMcp::getServerUrl, serverUrl)
                .last(SQL_LIMIT_ONE)
                .one();

        if (mcp == null) {
            log.warn(DIRECT_HTTP_HINT, serverUrl);
            return executeDirectHttp(serverUrl, toolName, argumentsJson);
        }

        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    mcp.getId(), mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig());
            return client.callTool(toolName, argumentsJson);
        } catch (Exception e) {
            log.error("MCP tool call failed via client, falling back to direct HTTP: {}", e.getMessage());
            return executeDirectHttp(serverUrl, toolName, argumentsJson);
        }
    }

    /**
     * Fallback direct HTTP call (old behavior).
     */
    private String executeDirectHttp(String serverUrl, String toolName, String argumentsJson) {
        try {
            String url = serverUrl.endsWith("/") ? serverUrl + toolName : serverUrl + "/" + toolName;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .timeout(directReadTimeout)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(argumentsJson))
                    .build();
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(directConnectTimeout)
                    .build();
            java.net.http.HttpResponse<String> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("MCP direct HTTP error: status={}, body={}", response.statusCode(), response.body());
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED);
            }
            return response.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP direct HTTP call failed: serverUrl={}, toolName={}", serverUrl, toolName, e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED);
        }
    }
}
