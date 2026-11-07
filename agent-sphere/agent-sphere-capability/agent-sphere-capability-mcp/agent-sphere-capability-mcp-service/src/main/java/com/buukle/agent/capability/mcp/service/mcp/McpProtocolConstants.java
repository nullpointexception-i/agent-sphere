package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.common.constant.HttpHeaders;
import com.buukle.agent.common.constant.McpServerType;

public final class McpProtocolConstants {
    // ---- Protocol ----
    public static final String PROTOCOL_VERSION = "2025-11-25";
    // ---- JSON-RPC ----
    public static final String JSONRPC = "jsonrpc";
    public static final String JSONRPC_VERSION = "2.0";
    public static final String JSONRPC_ID = "id";
    public static final String JSONRPC_METHOD = "method";
    public static final String JSONRPC_PARAMS = "params";
    public static final String JSONRPC_RESULT = "result";
    public static final String JSONRPC_ERROR = "error";
    // ---- MCP Methods ----
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    // ---- Initialize params ----
    public static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    public static final String FIELD_CLIENT_INFO = "clientInfo";
    public static final String FIELD_CLIENT_NAME = "name";
    public static final String FIELD_CLIENT_VERSION = "version";
    public static final String FIELD_CAPABILITIES = "capabilities";
    public static final String CLIENT_NAME_VALUE = "agent-sphere";
    public static final String CLIENT_VERSION_VALUE = "1.0.0";
    // ---- HTTP headers ----
    public static final String HEADER_CONTENT_TYPE = HttpHeaders.CONTENT_TYPE;
    public static final String HEADER_ACCEPT = HttpHeaders.ACCEPT;
    public static final String HEADER_MCP_SESSION_ID = "MCP-Session-Id";
    public static final String HEADER_MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
    /**
     * Task-level MCP credential header. The name is fixed by agent-sphere and not caller-defined:
     * if an external party could choose the header name, task submission would let them override
     * authentication headers such as Authorization on MCPs that already registered them.
     */
    public static final String HEADER_TASK_MCP_CREDENTIAL = "X-Task-Mcp-Credential";
    public static final String CONTENT_TYPE_JSON = HttpHeaders.APPLICATION_JSON;
    public static final String CONTENT_TYPE_SSE = HttpHeaders.TEXT_EVENT_STREAM;
    public static final String ACCEPT_JSON_SSE = "application/json,text/event-stream";
    // ---- Endpoint defaults ----
    public static final String DEFAULT_SSE_PATH = "/sse";
    public static final String DEFAULT_MCP_PATH = "/mcp";
    // ---- SSE parsing ----
    public static final String SSE_EVENT_PREFIX = "event: ";
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String SSE_EVENT_ENDPOINT = "endpoint";
    public static final String SSE_EVENT_MESSAGE = "message";
    // ---- Tool info fields ----
    public static final String TOOL_NAME = "name";
    public static final String TOOL_DESCRIPTION = "description";
    public static final String TOOL_INPUT_SCHEMA = "inputSchema";
    public static final String TOOLS = "tools";
    // ---- Call tool fields ----
    public static final String CALL_NAME = "name";
    public static final String CALL_ARGUMENTS = "arguments";
    // ---- Call result fields ----
    public static final String RESULT_IS_ERROR = "isError";
    public static final String RESULT_CONTENT = "content";
    public static final String RESULT_CONTENT_TYPE = "type";
    public static final String RESULT_CONTENT_TEXT = "text";
    // ---- HTTP status codes ----
    public static final int HTTP_OK = 200;
    public static final int HTTP_ACCEPTED = 202;
    // ---- Server type values ----
    public static final String SERVER_TYPE_HTTP = "http";
    public static final String SERVER_TYPE_SSE = McpServerType.SSE;
    // ---- Default empty schema ----
    public static final String DEFAULT_EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private McpProtocolConstants() {
    }
}
