package com.buukle.agent.capability.mcp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.domain.CapabilityMcpHub;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpTestResultVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.capability.mcp.repository.McpHubMapper;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.mcp.McpCallContext;
import com.buukle.agent.capability.mcp.service.mcp.McpProtocolConstants;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportClient;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.capability.mcp.spi.McpHubSpi;
import com.buukle.agent.common.constant.HttpHeaders;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Primary
public class CapabilityMcpServiceImpl extends ServiceImpl<McpMapper, CapabilityMcp> implements CapabilityMcpService {
    private static final String SQL_LIMIT_ONE = "LIMIT 1";
    private static final String DIRECT_HTTP_HINT = "No MCP found for serverUrl={}, using direct HTTP call";
    private static final String HEADER_CONTENT_TYPE = HttpHeaders.CONTENT_TYPE;
    private static final String APPLICATION_JSON = HttpHeaders.APPLICATION_JSON;
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    private final CapabilityMcpConverter capabilityMcpConverter;
    private final McpTransportFactory mcpTransportFactory;
    private final McpHubMapper mcpHubMapper;
    private final McpHubSpi mcpHubSpi;
    private final Duration directReadTimeout;
    private final Duration directConnectTimeout;

    public CapabilityMcpServiceImpl(CapabilityMcpConverter capabilityMcpConverter,
                                    McpTransportFactory mcpTransportFactory,
                                    McpHubMapper mcpHubMapper,
                                    McpHubSpi mcpHubSpi,
                                    AgentRuntimeProperties properties) {
        this.capabilityMcpConverter = capabilityMcpConverter;
        this.mcpTransportFactory = mcpTransportFactory;
        this.mcpHubMapper = mcpHubMapper;
        this.mcpHubSpi = mcpHubSpi;
        this.directReadTimeout = properties.getMcp().getDirectReadTimeout();
        this.directConnectTimeout = properties.getMcp().getDirectConnectTimeout();
    }

    @Override
    public McpVO createMcp(CreateMcpDTO dto) {
        validateServerUrl(dto.getServerUrl());
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
        List<McpVO> vos = list.stream().map(capabilityMcpConverter::toVO).toList();
        attachHubState(list, vos);
        return vos;
    }

    @Override
    public IPage<McpVO> pageMcps(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<CapabilityMcp> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilityMcp::getName, keyword)
                .ge(startTime != null, CapabilityMcp::getCreatedAt, startTime)
                .le(endTime != null, CapabilityMcp::getCreatedAt, endTime)
                .orderByDesc(CapabilityMcp::getCreatedAt)
                .page(new Page<>(page, size));
        List<McpVO> vos = p.getRecords().stream().map(capabilityMcpConverter::toVO).toList();
        attachHubState(p.getRecords(), vos);
        Page<McpVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(vos);
        return result;
    }

    /** One extra query for the page: publish state shown by the "my MCP" badges. */
    private void attachHubState(List<CapabilityMcp> mcps, List<McpVO> vos) {
        if (mcps == null || mcps.isEmpty() || vos == null || vos.isEmpty()) {
            return;
        }
        List<Long> ids = mcps.stream().map(CapabilityMcp::getId).toList();
        Map<Long, CapabilityMcpHub> hubs = new HashMap<>();
        if (mcpHubMapper != null) {
            mcpHubMapper.selectList(Wrappers.<CapabilityMcpHub>lambdaQuery()
                            .in(CapabilityMcpHub::getSourceMcpId, ids))
                    .forEach(h -> hubs.putIfAbsent(h.getSourceMcpId(), h));
        }
        for (int i = 0; i < mcps.size(); i++) {
            McpVO vo = vos.get(i);
            CapabilityMcpHub hub = hubs.get(mcps.get(i).getId());
            if (hub == null) {
                vo.setHasUnpublished(false);
                continue;
            }
            vo.setHubMcpId(hub.getId());
            vo.setHubVersion(hub.getVersion());
            vo.setHasUnpublished(hasUnpublished(mcps.get(i), hub));
        }
    }

    private static boolean hasUnpublished(CapabilityMcp mcp, CapabilityMcpHub hub) {
        return mcp.getUpdatedAt() != null && hub.getUpdatedAt() != null
                && mcp.getUpdatedAt().isAfter(hub.getUpdatedAt());
    }

    @Override
    public List<McpVO> listMcpsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return lambdaQuery().in(CapabilityMcp::getId, ids).list().stream().map(capabilityMcpConverter::toVO).toList();
    }

    @Override
    public McpVO updateMcp(Long id, CreateMcpDTO dto) {
        validateServerUrl(dto.getServerUrl());
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
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list MCP tools for mcpId={}: {}", mcpId, e.getMessage());
            throw new BizException(CapabilityMcpErrorCode.MCP_LIST_TOOLS_FAILED, e.getMessage());
        }
    }

    @Override
    public McpTestResultVO testConnection(Long mcpId) {
        McpVO mcp = getMcp(mcpId);
        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    mcpId, mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig());
            List<McpToolInfoVO> tools = client.listTools();
            return McpTestResultVO.builder()
                    .ok(true)
                    .serverType(mcp.getServerType())
                    .protocolVersion(client.negotiatedProtocolVersion())
                    .toolCount(tools.size())
                    .message("Connection succeeded")
                    .build();
        } catch (BizException e) {
            log.error("MCP connect test failed for mcpId={}: {}", mcpId, e.getMessage());
            throw new BizException(CapabilityMcpErrorCode.MCP_CONNECT_TEST_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("MCP connect test failed for mcpId={}", mcpId, e);
            throw new BizException(CapabilityMcpErrorCode.MCP_CONNECT_TEST_FAILED, e.getMessage());
        }
    }

    @Override
    public String callToolByMcpId(Long mcpId, String toolName, String argumentsJson) {
        McpVO mcp = getMcp(mcpId);
        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    mcpId, mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig());
            return client.callTool(toolName, argumentsJson);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP tool call failed for mcpId={}, tool={}: {}", mcpId, toolName, e.getMessage());
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, e.getMessage());
        }
    }

    @Override
    public String executeTool(String serverUrl, String toolName, String argumentsJson) {
        return executeTool(serverUrl, toolName, argumentsJson, null);
    }

    @Override
    public String executeTool(String serverUrl, String toolName, String argumentsJson, String taskMcpCredential) {
        CapabilityMcp mcp = lambdaQuery()
                .eq(CapabilityMcp::getServerUrl, serverUrl)
                .last(SQL_LIMIT_ONE)
                .one();

        // The credential applies only to this request and is never attached to the cached transport
        // instance: transports are reused per mcpId, so attaching it to the instance would let
        // concurrent tasks overwrite each other's credentials.
        McpCallContext callContext = McpCallContext.ofTaskCredential(taskMcpCredential);

        if (mcp == null) {
            McpVO hub = mcpHubSpi == null ? null : mcpHubSpi.findHubByServerUrl(serverUrl);
            if (hub != null) {
                return executeHubMcp(hub, toolName, argumentsJson, callContext);
            }
            log.warn(DIRECT_HTTP_HINT, serverUrl);
            return executeDirectHttp(serverUrl, toolName, argumentsJson, callContext);
        }

        return executeLocalMcp(mcp, toolName, argumentsJson, callContext);
    }

    @Override
    public String executeToolById(Long mcpId, String toolName, String argumentsJson, String taskMcpCredential) {
        CapabilityMcp mcp = mcpId == null ? null : getById(mcpId);
        if (mcp == null) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        return executeLocalMcp(mcp, toolName, argumentsJson,
                McpCallContext.ofTaskCredential(taskMcpCredential));
    }

    @Override
    public String executeHubTool(Long hubId, String toolName, String argumentsJson, String taskMcpCredential) {
        McpVO hub = mcpHubSpi == null ? null : mcpHubSpi.getHubMcp(hubId);
        if (hub == null) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        return executeHubMcp(hub, toolName, argumentsJson,
                McpCallContext.ofTaskCredential(taskMcpCredential));
    }

    /** Executes one tool of an already-resolved local MCP definition. */
    private String executeLocalMcp(CapabilityMcp mcp, String toolName, String argumentsJson,
                                   McpCallContext callContext) {
        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    mcp.getId(), mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig());
            return client.callTool(toolName, argumentsJson, callContext);
        } catch (BizException e) {
            // Configuration errors (such as an invalid authConfig) must be rethrown as-is: the
            // direct-HTTP fallback targets a different URL and carries no auth, so falling back
            // would disguise "the config is wrong" as "network/protocol unreachable" and bury the root cause.
            if (CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode().equals(e.getErrorCode())) {
                throw e;
            }
            log.error("MCP tool call failed via client, falling back to direct HTTP: {}", e.getMessage());
            return executeDirectHttp(mcp.getServerUrl(), toolName, argumentsJson, callContext);
        } catch (Exception e) {
            log.error("MCP tool call failed via client, falling back to direct HTTP: {}", e.getMessage());
            return executeDirectHttp(mcp.getServerUrl(), toolName, argumentsJson, callContext);
        }
    }

    /**
     * Executes a followed hub MCP with the hub's shared auth config. The negative cache key keeps
     * hub transports separate from local MCP transports.
     */
    private String executeHubMcp(McpVO hub, String toolName, String argumentsJson,
                                 McpCallContext callContext) {
        try {
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    -hub.getId(), hub.getServerUrl(), hub.getServerType(), hub.getAuthConfig());
            return client.callTool(toolName, argumentsJson, callContext);
        } catch (BizException e) {
            if (CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode().equals(e.getErrorCode())) {
                throw e;
            }
            log.error("MCP hub tool call failed via client, falling back to direct HTTP: {}", e.getMessage());
            return executeDirectHttp(hub.getServerUrl(), toolName, argumentsJson, callContext);
        } catch (Exception e) {
            log.error("MCP hub tool call failed via client, falling back to direct HTTP: {}", e.getMessage());
            return executeDirectHttp(hub.getServerUrl(), toolName, argumentsJson, callContext);
        }
    }

    /**
     * Fallback direct HTTP call (old behavior).
     */
    private String executeDirectHttp(String serverUrl, String toolName, String argumentsJson,
            McpCallContext callContext) {
        try {
            String url = serverUrl.endsWith("/") ? serverUrl + toolName : serverUrl + "/" + toolName;
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .timeout(directReadTimeout)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(argumentsJson));
            // The fallback path must carry the credential too: otherwise, as soon as the MCP client
            // breaks, task credentials silently stop working and surface as "deduplication
            // intermittently skipped" - extremely hard to diagnose.
            if (callContext != null) {
                String credential = callContext.header(McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL);
                if (credential != null && !credential.isBlank()) {
                    builder.header(McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL, credential);
                }
            }
            java.net.http.HttpRequest request = builder.build();
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
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, e.getMessage());
        }
    }

    /**
     * serverUrl must start with http/https; otherwise the MCP transport's URI.create throws a raw
     * IllegalArgumentException for the missing scheme. Validate at create/update time to surface a
     * readable error early.
     */
    private void validateServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "serverUrl must not be empty");
        }
        String url = serverUrl.trim();
        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX)) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                    "serverUrl must start with http:// or https://; current: " + serverUrl);
        }
    }
}
