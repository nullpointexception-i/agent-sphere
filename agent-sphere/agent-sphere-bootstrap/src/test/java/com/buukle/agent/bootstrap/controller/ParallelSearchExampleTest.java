package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.mcp.controller.CapabilityMcpController;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.impl.CapabilityMcpServiceImpl;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Loads the shipped example through the real controller, service and transport. */
class ParallelSearchExampleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLE = Path.of("../../examples/parallel-search/mcp.json");
    private static final String ENDPOINT = "https://search.parallel.ai/mcp";
    private static final String USER_AGENT = "agent-sphere/1.0.0";
    private static final String API = "/api/v1/capability/mcp";
    private static final String SEARCH = "web_search";
    private static final String FETCH = "web_fetch";
    private static final String PAGE = "https://modelcontextprotocol.io/specification/2025-11-25/basic/transports";
    private static final String SEARCH_OBJECTIVE = "Find the official MCP transport documentation";
    private static final String SEARCH_QUERY = "MCP Streamable HTTP transport documentation";
    private static final String FETCH_OBJECTIVE = "Explain Streamable HTTP transport";
    private static final long MCP_ID = 1L;

    @Test
    void exampleUsesAnonymousStreamableHttp() throws Exception {
        JsonNode config = JSON.readTree(Files.readString(EXAMPLE));
        assertEquals(ENDPOINT, config.path("serverUrl").asText());
        assertEquals("http", config.path("serverType").asText());
        JsonNode headers = JSON.readTree(config.path("authConfig").asText());
        assertEquals(1, headers.size());
        assertEquals(USER_AGENT, headers.path("User-Agent").asText());
        assertFalse(headers.has("Authorization"));
    }

    @Test
    void discoverySearchAndFetchCarryHeadersAndPreserveResults() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            if (!"/mcp".equals(exchange.getRequestURI().toString())) violations.add("wrong endpoint");
            if (!USER_AGENT.equals(exchange.getRequestHeaders().getFirst("User-Agent"))) violations.add("wrong User-Agent");
            if (exchange.getRequestHeaders().containsKey("Authorization")) violations.add("unexpected credentials");
            if (!"POST".equals(exchange.getRequestMethod())) violations.add("wrong method");
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            JsonNode result = switch (method) {
                case "initialize" -> JSON.readTree("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}");
                case "tools/list" -> JSON.readTree("{\"tools\":[{\"name\":\"web_search\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"web_fetch\",\"inputSchema\":{\"type\":\"object\"}}]}");
                case "tools/call" -> JSON.readTree("{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"https://modelcontextprotocol.io: Streamable HTTP uses POST and GET requests.\"}]}");
                default -> throw new IllegalStateException(method);
            };
            response.set("result", result);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        McpTransportFactory factory = new McpTransportFactory(new AgentRuntimeProperties());
        try {
            exercise("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", factory);
            assertTrue(violations.isEmpty(), violations.toString());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call", "tools/call"),
                    requests.stream().map(r -> r.path("method").asText()).toList());
            assertEquals(SEARCH, requests.get(3).at("/params/name").asText());
            assertEquals(FETCH, requests.get(4).at("/params/name").asText());
            assertEquals(SEARCH_QUERY, requests.get(3).at("/params/arguments/search_queries/0").asText());
            assertEquals(PAGE, requests.get(4).at("/params/arguments/urls/0").asText());
            assertEquals(requests.get(3).at("/params/arguments/session_id"), requests.get(4).at("/params/arguments/session_id"));
        } finally {
            factory.evictClient(MCP_ID);
            server.stop(0);
        }
    }

    /** Explicit opt-in: never sends network requests during normal mvn test. */
    @Test
    @EnabledIfSystemProperty(named = "parallel.live", matches = "true")
    void anonymousLiveSearchAndFetch() throws Exception {
        McpTransportFactory factory = new McpTransportFactory(new AgentRuntimeProperties());
        try {
            exercise(ENDPOINT, factory);
        } finally {
            factory.evictClient(MCP_ID);
        }
    }

    private void exercise(String endpoint, McpTransportFactory factory) throws Exception {
        // Only persistence is replaced. All DTO conversion, selection, HTTP and results are real.
        ConcurrentHashMap<Long, CapabilityMcp> saved = new ConcurrentHashMap<>();
        CapabilityMcpServiceImpl service = spy(new CapabilityMcpServiceImpl(
                new CapabilityMcpConverter(), factory, new AgentRuntimeProperties()));
        doAnswer(call -> {
            CapabilityMcp capability = call.getArgument(0);
            capability.setId(MCP_ID);
            saved.put(MCP_ID, capability);
            return true;
        }).when(service).save(any(CapabilityMcp.class));
        doAnswer(call -> saved.get(call.getArgument(0))).when(service).getById(MCP_ID);
        MockMvc api = MockMvcBuilders.standaloneSetup(new CapabilityMcpController(service)).build();
        ObjectNode config = (ObjectNode) JSON.readTree(Files.readString(EXAMPLE));
        config.put("serverUrl", endpoint);
        api.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(config.toString()))
                .andExpect(status().isCreated());
        String discovered = api.perform(get(API + "/" + MCP_ID + "/tools"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode tools = JSON.readTree(discovered);
        assertTrue(tools.findValuesAsText("name").containsAll(List.of(SEARCH, FETCH)), discovered);

        String session = UUID.randomUUID().toString();
        ObjectNode search = JSON.createObjectNode().put("objective", SEARCH_OBJECTIVE).put("session_id", session);
        search.putArray("search_queries").add(SEARCH_QUERY);
        JsonNode searchResult = call(api, SEARCH, search);
        ObjectNode fetch = JSON.createObjectNode().put("objective", FETCH_OBJECTIVE).put("session_id", session);
        fetch.putArray("urls").add(PAGE);
        JsonNode fetchResult = call(api, FETCH, fetch);
        assertTrue(searchResult.toString().contains("https://"), searchResult.toString());
        assertTrue(fetchResult.toString().contains("Streamable"), fetchResult.toString());
    }

    private JsonNode call(MockMvc api, String tool, JsonNode arguments) throws Exception {
        String body = api.perform(post(API + "/" + MCP_ID + "/tools/" + tool + "/call")
                        .contentType(MediaType.APPLICATION_JSON).content(arguments.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result = JSON.readTree(body);
        assertFalse(result.path("isError").asBoolean(), body);
        assertTrue(result.path("content").isArray(), body);
        assertTrue(result.path("content").size() > 0, body);
        assertTrue(result.path("content").findValuesAsText("text").stream().anyMatch(t -> !t.isBlank()), body);
        return result;
    }
}
