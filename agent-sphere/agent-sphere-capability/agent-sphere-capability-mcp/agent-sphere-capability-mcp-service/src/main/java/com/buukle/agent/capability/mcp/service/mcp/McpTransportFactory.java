package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for creating and caching MCP transport clients.
 * Chooses transport based on serverType: "http" -> Streamable HTTP, "sse" -> Legacy SSE.
 */
@Slf4j
@Component
public class McpTransportFactory {

    // Cache: key = mcpId, value = transport client
    private final ConcurrentMap<Long, McpTransportClient> clientCache = new ConcurrentHashMap<>();
    private final AgentRuntimeProperties.McpConfig mcpConfig;

    public McpTransportFactory(AgentRuntimeProperties properties) {
        this.mcpConfig = properties.getMcp();
    }

    /**
     * Get or create a transport client for the given MCP configuration.
     * Caches the client per mcpId. On initialization failure, tries fallback transport.
     */
    public McpTransportClient getOrCreateClient(Long mcpId, String serverUrl, String serverType, String authConfig) {
        return clientCache.computeIfAbsent(mcpId, id -> {
            McpTransportClient client = createClient(serverUrl, serverType, authConfig);
            try {
                client.initialize();
            } catch (Exception e) {
                log.warn("Failed to initialize MCP client for mcpId={}, client will retry on next call", mcpId, e);
                // Clear the cache entry so next call retries
                clientCache.remove(mcpId, client);
                throw e;
            }
            return client;
        });
    }

    /**
     * Create a new transport client based on serverType.
     * "http" -> StreamableHttpTransport, "sse" -> LegacySseTransport.
     */
    public McpTransportClient createClient(String serverUrl, String serverType, String authConfig) {
        if (McpProtocolConstants.SERVER_TYPE_SSE.equalsIgnoreCase(serverType)) {
            log.info("Creating Legacy SSE transport for: {}", serverUrl);
            return new LegacySseTransport(serverUrl, authConfig, mcpConfig);
        }
        log.info("Creating Streamable HTTP transport for: {}", serverUrl);
        return new StreamableHttpTransport(serverUrl, serverType, authConfig, mcpConfig);
    }

    /**
     * Evict a cached client (e.g., on configuration change or repeated failures).
     */
    public void evictClient(Long mcpId) {
        McpTransportClient client = clientCache.remove(mcpId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client for mcpId={}", mcpId, e);
            }
        }
    }
}
