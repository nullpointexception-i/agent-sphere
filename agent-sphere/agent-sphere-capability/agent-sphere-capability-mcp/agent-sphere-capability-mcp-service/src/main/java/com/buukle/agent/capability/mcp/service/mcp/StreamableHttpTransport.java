package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
public class StreamableHttpTransport implements McpTransportClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpointUrl;
    private final String authConfigJson;
    private final Duration connectTimeout;
    private final Duration rpcTimeout;

    private String sessionId;
    private volatile boolean initialized = false;
    private final AtomicLong requestId = new AtomicLong(1);
    private String negotiatedProtocolVersion;

    public StreamableHttpTransport(String serverUrl, String serverType, String authConfig, AgentRuntimeProperties.McpConfig config) {
        this.connectTimeout = config.getConnectTimeout();
        this.rpcTimeout = config.getRpcTimeout();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        this.authConfigJson = authConfig;
        this.endpointUrl = resolveEndpoint(serverUrl, serverType);
    }

    static String resolveEndpoint(String serverUrl, String serverType) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl must not be empty");
        }
        try {
            URI uri = URI.create(serverUrl);
            String path = uri.getPath();
            String query = uri.getQuery();
            boolean hasCustomPath = path != null && !path.isEmpty() && !path.equals("/");
            boolean hasQuery = query != null && !query.isEmpty();
            if (hasCustomPath || hasQuery) {
                return serverUrl;
            }
        } catch (Exception e) {
            log.warn("Failed to parse serverUrl {}, falling back to default path", serverUrl);
        }
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        if (SERVER_TYPE_SSE.equalsIgnoreCase(serverType)) {
            return base + DEFAULT_SSE_PATH;
        }
        return base + DEFAULT_MCP_PATH;
    }

    @Override
    public void initialize() {
        try {
            ObjectNode initRequest = JSON.createObjectNode();
            initRequest.put(JSONRPC, JSONRPC_VERSION);
            initRequest.put(JSONRPC_ID, requestId.getAndIncrement());
            initRequest.put(JSONRPC_METHOD, METHOD_INITIALIZE);
            ObjectNode params = initRequest.putObject(JSONRPC_PARAMS);
            params.put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION);
            ObjectNode clientInfo = params.putObject(FIELD_CLIENT_INFO);
            clientInfo.put(FIELD_CLIENT_NAME, CLIENT_NAME_VALUE);
            clientInfo.put(FIELD_CLIENT_VERSION, CLIENT_VERSION_VALUE);
            params.set(FIELD_CAPABILITIES, JSON.createObjectNode());

            JsonNode response = doPost(JSON.writeValueAsString(initRequest));
            if (response == null) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP initialization failed: null response");
            }

            JsonNode result = response.get(JSONRPC_RESULT);
            if (result == null) {
                String errMsg = response.has(JSONRPC_ERROR) ? response.get(JSONRPC_ERROR).toString() : "unknown error";
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP init error: " + errMsg);
            }

            negotiatedProtocolVersion = result.has(FIELD_PROTOCOL_VERSION)
                ? result.get(FIELD_PROTOCOL_VERSION).asText()
                : PROTOCOL_VERSION;

            log.info("MCP initialized: protocol={}, serverInfo={}", negotiatedProtocolVersion, result.get("serverInfo"));

            ObjectNode notification = JSON.createObjectNode();
            notification.put(JSONRPC, JSONRPC_VERSION);
            notification.put(JSONRPC_METHOD, METHOD_NOTIFICATIONS_INITIALIZED);
            doPost(JSON.writeValueAsString(notification));

            initialized = true;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP initialization failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP init failed: " + e.getMessage());
        }
    }

    @Override
    public List<McpToolInfoVO> listTools() {
        ensureInitialized();
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put(JSONRPC, JSONRPC_VERSION);
            request.put(JSONRPC_ID, requestId.getAndIncrement());
            request.put(JSONRPC_METHOD, METHOD_TOOLS_LIST);
            request.set(JSONRPC_PARAMS, JSON.createObjectNode());

            String responseJson = doPostRaw(JSON.writeValueAsString(request));
            JsonNode root = JSON.readTree(responseJson);
            JsonNode result = root.get(JSONRPC_RESULT);
            if (result == null) {
                throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP tools/list failed");
            }

            JsonNode toolsNode = result.get(TOOLS);
            if (toolsNode == null || !toolsNode.isArray()) {
                return List.of();
            }

            List<McpToolInfoVO> tools = new ArrayList<>();
            for (JsonNode toolNode : toolsNode) {
                String name = toolNode.has(TOOL_NAME) ? toolNode.get(TOOL_NAME).asText() : "";
                String description = toolNode.has(TOOL_DESCRIPTION) ? toolNode.get(TOOL_DESCRIPTION).asText() : "";
                JsonNode inputSchema = toolNode.get(TOOL_INPUT_SCHEMA);
                String inputSchemaStr = DEFAULT_EMPTY_SCHEMA;
                if (inputSchema != null) {
                    inputSchemaStr = JSON.writeValueAsString(inputSchema);
                }
                tools.add(McpToolInfoVO.builder()
                    .name(name).description(description).inputSchema(inputSchemaStr).build());
            }
            log.debug("MCP tools/list returned {} tools", tools.size());
            return tools;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP tools/list failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP list tools failed: " + e.getMessage());
        }
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        ensureInitialized();
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put(JSONRPC, JSONRPC_VERSION);
            request.put(JSONRPC_ID, requestId.getAndIncrement());
            request.put(JSONRPC_METHOD, METHOD_TOOLS_CALL);
            ObjectNode params = request.putObject(JSONRPC_PARAMS);
            params.put(CALL_NAME, toolName);
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                try {
                    JsonNode args = JSON.readTree(argumentsJson);
                    params.set(CALL_ARGUMENTS, args);
                } catch (Exception e) {
                    params.put(CALL_ARGUMENTS, argumentsJson);
                }
            } else {
                params.set(CALL_ARGUMENTS, JSON.createObjectNode());
            }

            String responseJson = doPostRaw(JSON.writeValueAsString(request));
            JsonNode root = JSON.readTree(responseJson);
            if (root.has(JSONRPC_ERROR)) {
                JsonNode err = root.get(JSONRPC_ERROR);
                String errMsg = err.has("message") ? err.get("message").asText() : "unknown MCP error";
                log.error("MCP tool call error: {} - {}", toolName, errMsg);
                return "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"MCP error: " +
                    errMsg.replace("\"", "\\\"") + "\"}]}";
            }
            JsonNode result = root.get(JSONRPC_RESULT);
            if (result == null) {
                return "{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"\"}]}";
            }
            return JSON.writeValueAsString(result);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP tool call failed: {}", toolName, e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP call failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isConnected() { return initialized; }

    @Override
    public void close() {
        initialized = false;
        sessionId = null;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP client not initialized");
        }
    }

    private JsonNode doPost(String body) {
        try {
            String json = doPostRaw(body);
            if (json == null || json.isBlank()) return null;
            return JSON.readTree(json);
        } catch (Exception e) {
            log.warn("doPost parse error", e);
            return null;
        }
    }

    private String doPostRaw(String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .header(HEADER_ACCEPT, ACCEPT_JSON_SSE)
                .timeout(rpcTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            applyAuth(builder);
            if (sessionId != null) {
                builder.header(HEADER_MCP_SESSION_ID, sessionId);
            }
            if (negotiatedProtocolVersion != null) {
                builder.header(HEADER_MCP_PROTOCOL_VERSION, negotiatedProtocolVersion);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            String newSessionId = response.headers().firstValue(HEADER_MCP_SESSION_ID).orElse(null);
            if (newSessionId != null) {
                sessionId = newSessionId;
            }

            int status = response.statusCode();
            if (status >= 400) {
                log.debug("MCP POST returned status {} - may not support Streamable HTTP", status);
                return "{\"error\":{\"code\":-32000,\"message\":\"HTTP " + status + "\"}}";
            }

            String respBody = response.body();
            if (respBody == null || respBody.isBlank()) {
                return "{\"result\":{}}";
            }

            String contentType = response.headers().firstValue(HEADER_CONTENT_TYPE).orElse("");
            if (contentType.contains(CONTENT_TYPE_SSE)) {
                return parseSseResponse(respBody);
            }
            return respBody;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("MCP request timed out for endpoint: {}", endpointUrl);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP request timed out");
        } catch (Exception e) {
            log.error("MCP POST request failed", e);
            throw new BizException(CapabilityMcpErrorCode.MCP_EXECUTE_FAILED, "MCP request failed: " + e.getMessage());
        }
    }

    private String parseSseResponse(String sseBody) {
        StringBuilder jsonRpcPayload = new StringBuilder();
        boolean inEvent = false;
        for (String line : sseBody.split("\n")) {
            if (line.startsWith(SSE_EVENT_PREFIX)) {
                String event = line.substring(SSE_EVENT_PREFIX.length()).trim();
                inEvent = SSE_EVENT_MESSAGE.equals(event);
            } else if (line.startsWith(SSE_DATA_PREFIX) && inEvent) {
                String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (!data.isEmpty()) {
                    jsonRpcPayload.append(data);
                }
            }
        }
        String result = jsonRpcPayload.toString();
        if (result.isEmpty()) {
            log.warn("No JSON-RPC message found in SSE response");
            return "{\"result\":{}}";
        }
        return result;
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
