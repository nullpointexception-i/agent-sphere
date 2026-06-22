package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.buukle.agent.capability.mcp.service.mcp.McpProtocolConstants.*;

@Slf4j
public class LegacySseTransport implements McpTransportClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String sseUrl;
    private final String authConfigJson;
    private final Duration connectTimeout;
    private final Duration sseInitTimeout;
    private final Duration sseReadTimeout;
    private final Duration rpcTimeout;
    private final AtomicLong requestId = new AtomicLong(1);
    private volatile boolean initialized = false;
    private String postEndpointUrl;

    public LegacySseTransport(String serverUrl, String authConfig, AgentRuntimeProperties.McpConfig config) {
        this.connectTimeout = config.getConnectTimeout();
        this.sseInitTimeout = config.getSseInitTimeout();
        this.sseReadTimeout = config.getSseReadTimeout();
        this.rpcTimeout = config.getRpcTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        this.authConfigJson = authConfig;
        this.sseUrl = serverUrl.endsWith("/") ? serverUrl + "sse" : serverUrl + DEFAULT_SSE_PATH;
    }

    @Override
    public void initialize() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(sseUrl))
                    .header(HEADER_ACCEPT, CONTENT_TYPE_SSE)
                    .timeout(sseInitTimeout)
                    .GET();
            applyAuth(builder);

            HttpResponse<java.io.InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED,
                        "Legacy SSE: HTTP " + response.statusCode() + " from " + sseUrl);
            }

            boolean foundEndpoint = false;
            long sseReadStart = System.currentTimeMillis();
            long sseReadTimeoutMs = sseReadTimeout.toMillis();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (System.currentTimeMillis() - sseReadStart > sseReadTimeoutMs) {
                        throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED,
                                "Legacy SSE: timed out waiting for endpoint event from " + sseUrl);
                    }
                    if (line.startsWith(SSE_EVENT_PREFIX)) {
                        foundEndpoint = SSE_EVENT_ENDPOINT.equals(line.substring(SSE_EVENT_PREFIX.length()).trim());
                    } else if (foundEndpoint && line.startsWith(SSE_DATA_PREFIX)) {
                        postEndpointUrl = line.substring(SSE_DATA_PREFIX.length()).trim();
                        log.info("Legacy SSE: received endpoint POST URL: {}", postEndpointUrl);
                        break;
                    }
                }
            }

            if (postEndpointUrl == null || postEndpointUrl.isBlank()) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED,
                        "Legacy SSE: no endpoint event received from " + sseUrl);
            }
            initialized = true;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Legacy SSE initialization failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED,
                    "Legacy SSE init failed: " + e.getMessage());
        }
    }

    @Override
    public List<McpToolInfoVO> listTools() {
        ensureInitialized();
        return doRpcListTools();
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        ensureInitialized();
        return doRpcCallTool(toolName, argumentsJson);
    }

    @Override
    public boolean isConnected() {
        return initialized;
    }

    @Override
    public void close() {
        initialized = false;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "Legacy SSE client not initialized");
        }
    }

    private List<McpToolInfoVO> doRpcListTools() {
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put(JSONRPC, JSONRPC_VERSION);
            request.put(JSONRPC_ID, requestId.getAndIncrement());
            request.put(JSONRPC_METHOD, METHOD_TOOLS_LIST);
            request.set(JSONRPC_PARAMS, JSON.createObjectNode());

            JsonNode root = JSON.readTree(doPost(request.toString()));
            JsonNode result = root.get(JSONRPC_RESULT);
            if (result == null) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "Legacy SSE tools/list failed");
            }

            JsonNode toolsNode = result.get(TOOLS);
            if (toolsNode == null || !toolsNode.isArray()) return List.of();

            List<McpToolInfoVO> tools = new ArrayList<>();
            for (JsonNode toolNode : toolsNode) {
                String name = toolNode.has(TOOL_NAME) ? toolNode.get(TOOL_NAME).asText() : "";
                String description = toolNode.has(TOOL_DESCRIPTION) ? toolNode.get(TOOL_DESCRIPTION).asText() : "";
                JsonNode inputSchema = toolNode.get(TOOL_INPUT_SCHEMA);
                String inputSchemaStr = DEFAULT_EMPTY_SCHEMA;
                if (inputSchema != null) {
                    inputSchemaStr = JSON.writeValueAsString(inputSchema);
                }
                tools.add(McpToolInfoVO.builder().name(name).description(description).inputSchema(inputSchemaStr).build());
            }
            return tools;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Legacy SSE tools/list failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "Legacy SSE list tools: " + e.getMessage());
        }
    }

    private String doRpcCallTool(String toolName, String argumentsJson) {
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put(JSONRPC, JSONRPC_VERSION);
            request.put(JSONRPC_ID, requestId.getAndIncrement());
            request.put(JSONRPC_METHOD, METHOD_TOOLS_CALL);
            ObjectNode params = request.putObject(JSONRPC_PARAMS);
            params.put(CALL_NAME, toolName);
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                try {
                    params.set(CALL_ARGUMENTS, JSON.readTree(argumentsJson));
                } catch (Exception e) {
                    params.put(CALL_ARGUMENTS, argumentsJson);
                }
            } else {
                params.set(CALL_ARGUMENTS, JSON.createObjectNode());
            }

            JsonNode root = JSON.readTree(doPost(request.toString()));
            if (root.has(JSONRPC_ERROR)) {
                JsonNode err = root.get(JSONRPC_ERROR);
                String errMsg = err.has("message") ? err.get("message").asText() : "unknown legacy SSE error";
                return "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"MCP legacy SSE error: "
                        + errMsg.replace("\"", "\\\"") + "\"}]}";
            }
            JsonNode result = root.get(JSONRPC_RESULT);
            return result != null ? JSON.writeValueAsString(result)
                    : "{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"\"}]}";
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Legacy SSE tool call failed: {}", toolName, e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "Legacy SSE call failed: " + e.getMessage());
        }
    }

    private String doPost(String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(postEndpointUrl))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .timeout(rpcTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            applyAuth(builder);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED,
                        "Legacy SSE POST returned " + response.statusCode());
            }
            return response.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Legacy SSE POST request failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "Legacy SSE POST failed: " + e.getMessage());
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        if (authConfigJson == null || authConfigJson.isBlank()) return;
        try {
            JsonNode auth = JSON.readTree(authConfigJson);
            if (auth != null && auth.isObject()) {
                Iterator<String> fields = auth.fieldNames();
                while (fields.hasNext()) {
                    String key = fields.next();
                    builder.header(key, auth.get(key).asText());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse authConfig JSON, ignoring: {}", authConfigJson);
        }
    }
}
