package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.impl.CapabilityMcpServiceImpl;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.domain.AgentInstanceCapability;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.repository.InstanceCapabilityMapper;
import com.buukle.agent.instance.service.impl.InstanceCapabilityServiceImpl;
import com.buukle.agent.instance.spi.*;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.*;
import com.buukle.agent.runtime.kernel.config.*;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real binding services, native loader, runner, executor and transport; storage and model are fixtures. */
class ParallelSearchAgentLoopTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLE = Path.of("../../examples/parallel-search/mcp.json");
    private static final String ENDPOINT = "https://search.parallel.ai/mcp";
    private static final String USER_AGENT = "agent-sphere/1.0.0";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String SEARCH = "web_search";
    private static final String FETCH = "web_fetch";
    private static final String SEARCH_CALL = "search-call";
    private static final String FETCH_CALL = "fetch-call";
    private static final String OBJECTIVE = "Find the official MCP Streamable HTTP transport documentation";
    private static final String QUERY = "site:modelcontextprotocol.io specification Streamable HTTP transports";
    private static final String FIXTURE_PAGE = "https://modelcontextprotocol.io/specification/2025-11-25/basic/transports";
    private static final String FIXTURE_TEXT = "Streamable HTTP fixture content " + UUID.randomUUID();
    private static final Pattern SOURCE_URL = Pattern.compile("https://modelcontextprotocol\\.io/[^\\s\"<>\\\\)]+");
    private static final long MCP_ID = 91L;
    private static final long INSTANCE_ID = 92L;
    private static final long SESSION_ID = 93L;
    private static final long RUN_ID = 94L;

    @Test
    void boundSearchResultDrivesFetchAndNextModelAnswer() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> violations = new CopyOnWriteArrayList<>();
        HttpServer server = fixture(requests, violations);
        server.start();
        try {
            exercise("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", true);
            assertTrue(violations.isEmpty(), violations.toString());
            List<JsonNode> calls = requests.stream().filter(r -> "tools/call".equals(r.path("method").asText())).toList();
            assertEquals(2, calls.size());
            assertEquals(SEARCH, calls.get(0).at("/params/name").asText());
            assertEquals(FETCH, calls.get(1).at("/params/name").asText());
            assertEquals(QUERY, calls.get(0).at("/params/arguments/search_queries/0").asText());
            assertEquals(FIXTURE_PAGE, calls.get(1).at("/params/arguments/urls/0").asText());
            assertEquals(calls.get(0).at("/params/arguments/session_id"), calls.get(1).at("/params/arguments/session_id"));
            assertTrue(requests.stream().anyMatch(r -> "tools/list".equals(r.path("method").asText())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledBindingDoesNotExposeOrExecuteParallelTools() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fixture(requests, new CopyOnWriteArrayList<>());
        server.start();
        try {
            exercise("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", false);
            assertTrue(requests.isEmpty(), "Disabled binding must not discover or execute MCP tools");
        } finally {
            server.stop(0);
        }
    }

    /** Explicit opt-in; shipped JSON unchanged, no Parallel key or saved credentials. */
    @Test
    @EnabledIfSystemProperty(named = "parallel.live", matches = "true")
    void anonymousLiveSearchAndFetchThroughSessionRunner() throws Exception {
        exercise(ENDPOINT, true);
    }

    @SuppressWarnings("unchecked")
    private void exercise(String endpoint, boolean enabled) throws Exception {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getRunner().setLlmTurnMaxRetries(0);
        McpTransportFactory factory = new McpTransportFactory(properties);
        try {
            // Mock only mapper persistence, keeping native create, query, conversion and binding.
            initTable(CapabilityMcp.class);
            initTable(AgentInstanceCapability.class);
            McpMapper mcpMapper = mock(McpMapper.class);
            when(mcpMapper.selectOne(any(Wrapper.class))).thenCallRealMethod();
            when(mcpMapper.selectOne(any(Wrapper.class), anyBoolean())).thenCallRealMethod();
            AtomicReference<CapabilityMcp> saved = new AtomicReference<>();
            when(mcpMapper.insert(any(CapabilityMcp.class))).thenAnswer(inv -> {
                CapabilityMcp value = inv.getArgument(0);
                value.setId(MCP_ID);
                saved.set(value);
                return 1;
            });
            when(mcpMapper.selectById(MCP_ID)).thenAnswer(inv -> saved.get());
            when(mcpMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
                Wrapper<CapabilityMcp> query = inv.getArgument(0);
                assertTrue(query.getSqlSegment().contains("server_url"));
                assertTrue(query.getSqlSegment().contains("LIMIT 1"));
                assertTrue(((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs().containsValue(endpoint));
                return List.of(saved.get());
            });
            CapabilityMcpServiceImpl mcp = new CapabilityMcpServiceImpl(new CapabilityMcpConverter(), factory, properties);
            ReflectionTestUtils.setField(mcp, "baseMapper", mcpMapper);
            ReflectionTestUtils.setField(mcp, "entityClass", CapabilityMcp.class);
            ReflectionTestUtils.setField(mcp, "mapperClass", McpMapper.class);
            CreateMcpDTO config = JSON.readValue(Files.readString(EXAMPLE), CreateMcpDTO.class);
            config.setServerUrl(endpoint);
            mcp.createMcp(config);

            RedissonClient redis = mock(RedissonClient.class);
            InstanceCapabilityMapper bindingMapper = mock(InstanceCapabilityMapper.class);
            AtomicReference<AgentInstanceCapability> bound = new AtomicReference<>();
            when(bindingMapper.insert(any(AgentInstanceCapability.class))).thenAnswer(inv -> {
                AgentInstanceCapability value = inv.getArgument(0);
                value.setId(INSTANCE_ID);
                bound.set(value);
                return 1;
            });
            when(bindingMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
                Wrapper<AgentInstanceCapability> query = inv.getArgument(0);
                assertTrue(query.getSqlSegment().contains("instance_id"));
                assertTrue(((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs().containsValue(INSTANCE_ID));
                return List.of(bound.get());
            });
            InstanceCapabilityServiceImpl bindings = new InstanceCapabilityServiceImpl(redis, properties);
            ReflectionTestUtils.setField(bindings, "baseMapper", bindingMapper);
            ReflectionTestUtils.setField(bindings, "entityClass", AgentInstanceCapability.class);
            ReflectionTestUtils.setField(bindings, "mapperClass", InstanceCapabilityMapper.class);
            CreateInstanceCapabilityDTO binding = new CreateInstanceCapabilityDTO();
            binding.setInstanceId(INSTANCE_ID);
            binding.setCapabilityType(CAPABILITY_TYPE_MCP);
            binding.setCapabilityId(MCP_ID);
            binding.setStatus(enabled ? STATUS_ENABLED : STATUS_DISABLED);
            bindings.createCapability(binding);

            CapabilityBuiltinSpi builtin = mock(CapabilityBuiltinSpi.class);
            InstanceVO instance = new InstanceVO();
            instance.setId(INSTANCE_ID);
            instance.setSystemPrompt(OBJECTIVE);
            ModelRouteFullVO route = new ModelRouteFullVO();
            route.setId(INSTANCE_ID);
            route.setCompany("controlled");
            route.setBaseUrl("http://controlled.invalid");
            route.setModelName("result-dependent-fixture");
            RunVO run = new RunVO();
            run.setId(RUN_ID);
            run.setSessionId(SESSION_ID);
            ContextPreparer preparer = new ContextPreparer(bindings, mcp, mock(CapabilitySkillSpi.class),
                    mock(CapabilityCliSpi.class), builtin, properties);
            KernelContext ctx = preparer.prepare(run, ValidationResult.builder()
                    .agentInstance(instance).modelRoute(route).build(), OBJECTIVE);
            assertSame(route, ctx.getModelRoute(), "Keep the selected model route");
            assertNull(route.getApiKeyId());
            ToolExecutor executor = new ToolExecutor(List.of(mcp), builtin, mock(CliExecutorService.class),
                    mock(SessionTodoSpi.class), mock(ApplicationEventPublisher.class), mock(ClarificationSpi.class),
                    mock(DelegateService.class), new TaskMcpCredentialStore(redis));
            if (!enabled) {
                assertTrue(ctx.getTools().stream().noneMatch(t -> CAPABILITY_TYPE_MCP.equals(t.getCapabilityType())));
                assertTrue(executor.execute(new TurnToolCall(SEARCH_CALL, "mcp_91_0", "{}"),
                        SESSION_ID, RUN_ID, ctx.getTools()).contains("Unknown tool"));
                verify(mcpMapper, never()).selectList(any(Wrapper.class));
                return;
            }
            RuntimeTool search = nativeTool(ctx, SEARCH);
            RuntimeTool fetch = nativeTool(ctx, FETCH);
            assertEquals(endpoint, search.getExecBinding().get(ExecBindingKeys.MCP_SERVER_URL));
            assertEquals(config.getAuthConfig(), fetch.getExecBinding().get(ExecBindingKeys.MCP_AUTH_CONFIG));

            RMapCache<Long, KernelContext> cache = mock(RMapCache.class);
            when(redis.<Long, KernelContext>getMapCache(DistributedRuntimeConstants.KEY_CTX)).thenReturn(cache);
            when(cache.get(SESSION_ID)).thenReturn(ctx);
            RSet<Object> cancel = mock(RSet.class);
            when(redis.<Object>getSet(anyString())).thenReturn(cancel);
            RBucket<Long> pending = mock(RBucket.class);
            when(redis.<Long>getBucket(DistributedRuntimeConstants.sessionPendingRunKey(SESSION_ID))).thenReturn(pending);
            when(pending.getAndDelete()).thenReturn(RUN_ID);
            RBucket<String> credential = mock(RBucket.class);
            when(redis.<String>getBucket("runtime:mcp:task-cred:" + SESSION_ID)).thenReturn(credential);
            RunSpi runs = mock(RunSpi.class);
            when(runs.getRun(RUN_ID)).thenReturn(run);
            SessionInputManager input = mock(SessionInputManager.class);
            when(input.promoteInput(SESSION_ID)).thenReturn(
                    new SessionInputManager.InputMessage(OBJECTIVE, null, 0L, false, List.of()), null);
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<String> selectedUrl = new AtomicReference<>();
            AtomicReference<String> finalAnswer = new AtomicReference<>();
            AtomicReference<Throwable> modelFailure = new AtomicReference<>();
            AtomicInteger searchChars = new AtomicInteger();
            AtomicInteger fetchChars = new AtomicInteger();
            String parallelSession = UUID.randomUUID().toString();
            ModelProviderSpi model = mock(ModelProviderSpi.class);
            doAnswer(inv -> {
                ChatCompletionRequestDTO request = inv.getArgument(4);
                Consumer<LLMEvent> events = inv.getArgument(5);
                Runnable done = inv.getArgument(6);
                try {
                    assertEquals("", inv.getArgument(2), "Controlled model needs no credential");
                    int turn = turns.getAndIncrement();
                    if (turn == 0) {
                        assertTrue(request.getTools().stream().anyMatch(t -> search.getLlmToolName().equals(t.getFunction().getName())));
                        ObjectNode args = JSON.createObjectNode().put("objective", OBJECTIVE).put("session_id", parallelSession);
                        args.putArray("search_queries").add(QUERY);
                        events.accept(new LLMEvent.ToolCall(SEARCH_CALL, search.getLlmToolName(), args.toString()));
                    } else if (turn == 1) {
                        String text = toolResult(request, SEARCH_CALL, search.getLlmToolName());
                        var match = SOURCE_URL.matcher(text);
                        searchChars.set(text.length());
                        while (match.find()) {
                            if (match.group().contains("/transports")) {
                                selectedUrl.set(match.group());
                                break;
                            }
                        }
                        assertNotNull(selectedUrl.get(), "Search must return an official transport source URL: " + text);
                        ObjectNode args = JSON.createObjectNode().put("objective", OBJECTIVE).put("session_id", parallelSession);
                        args.putArray("urls").add(selectedUrl.get());
                        events.accept(new LLMEvent.ToolCall(FETCH_CALL, fetch.getLlmToolName(), args.toString()));
                    } else if (turn == 2) {
                        String text = pageText(toolResult(request, FETCH_CALL, fetch.getLlmToolName()));
                        fetchChars.set(text.length());
                        assertTrue(text.contains("Streamable"), text);
                        if (!ENDPOINT.equals(endpoint)) assertTrue(text.contains(FIXTURE_TEXT));
                        String answer = selectedUrl.get() + "\n" + text.substring(0, Math.min(240, text.length()));
                        finalAnswer.set(answer);
                        events.accept(new LLMEvent.TextDelta(answer));
                    } else {
                        fail("Unexpected model turn: " + turn);
                    }
                } catch (Throwable failure) {
                    // Propagate assertion failures across the real KernelLlmService virtual thread.
                    modelFailure.set(failure);
                    events.accept(new LLMEvent.Error(failure.toString()));
                } finally {
                    done.run();
                }
                return null;
            }).when(model).stream(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            SessionRunner runner = new SessionRunner(properties, runs, mock(SessionSpi.class),
                    new KernelLlmService(model, publisher, properties), mock(ApiKeySpi.class), publisher,
                    new RouteListBuilder(mock(RouteSpi.class), mock(InstanceSpi.class), model),
                    new FallbackRouteExecutor(), mock(HistoryLoader.class), mock(CompactionService.class), input,
                    new RunPromptBuilder(), executor, mock(TitleService.class), mock(AgentToolCallRecordSpi.class),
                    redis, mock(ChatAttachmentResolver.class), new LlmRequestConfigurer(mock(SystemConfigSpi.class)));
            runner.run(SESSION_ID);
            if (modelFailure.get() != null) throw new AssertionError("Controlled model validation failed", modelFailure.get());
            assertEquals("COMPLETED", run.getStatus(), "Runner must finish all result-dependent turns");
            assertEquals(3, turns.get());
            assertNotNull(finalAnswer.get());
            assertEquals(finalAnswer.get(), run.getAssistantReply());
            verify(mcpMapper, times(2)).selectList(any(Wrapper.class));
            verify(credential, times(2)).get();
            verify(input).clear(SESSION_ID);
            verify(cache).remove(SESSION_ID);
            System.out.printf("Parallel MCP agent loop completed: source=%s; search characters=%d; fetched body characters=%d; model turns=%d%n",
                    selectedUrl.get(), searchChars.get(), fetchChars.get(), turns.get());
        } finally {
            factory.evictClient(MCP_ID);
        }
    }

    private static RuntimeTool nativeTool(KernelContext ctx, String name) {
        return ctx.getTools().stream().filter(t -> name.equals(t.getExecBinding().get(ExecBindingKeys.MCP_NATIVE_TOOL_NAME)))
                .findFirst().orElseThrow(() -> new AssertionError("Native discovery did not load " + name));
    }

    private static String toolResult(ChatCompletionRequestDTO request, String callId, String toolName) throws Exception {
        List<ChatMessageDTO> messages = request.getMessages();
        ChatMessageDTO tool = messages.getLast();
        assertEquals("tool", tool.getRole());
        assertEquals(callId, tool.getToolCallId());
        ChatMessageDTO assistant = messages.get(messages.size() - 2);
        assertEquals("assistant", assistant.getRole());
        assertEquals(callId, assistant.getToolCalls().getFirst().getId());
        assertEquals(toolName, assistant.getToolCalls().getFirst().getFunction().getName());
        JsonNode result = JSON.readTree((String) tool.getContent());
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.path("content").isArray(), result.toString());
        String text = String.join("\n", result.path("content").findValuesAsText("text"));
        assertFalse(text.isBlank());
        return text;
    }

    private static String pageText(String text) throws Exception {
        if (!text.stripLeading().startsWith("{")) return text; // Local fixture's opaque MCP text.
        JsonNode extracted = JSON.readTree(text);
        assertTrue(extracted.path("errors").isEmpty(), text);
        StringBuilder body = new StringBuilder();
        for (JsonNode page : extracted.path("results")) {
            for (JsonNode excerpt : page.path("excerpts")) body.append(excerpt.asText()).append("\n");
            if (page.path("full_content").isTextual()) body.append(page.path("full_content").asText());
        }
        assertFalse(body.isEmpty(), "Fetch must contain extracted page text");
        return body.toString();
    }

    private static void initTable(Class<?> entity) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"), entity);
    }

    private static HttpServer fixture(List<JsonNode> requests, List<String> violations) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            if (!"/mcp".equals(exchange.getRequestURI().toString())) violations.add("Wrong endpoint");
            if (!USER_AGENT.equals(exchange.getRequestHeaders().getFirst("User-Agent"))) violations.add("Wrong User-Agent");
            if (exchange.getRequestHeaders().containsKey("Authorization")) violations.add("Unexpected Authorization");
            if (exchange.getRequestHeaders().containsKey("X-Task-Mcp-Credential")) violations.add("Unexpected task credential");
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-11-25");
                    result.putObject("capabilities");
                    result.putObject("serverInfo").put("name", "fixture").put("version", "1");
                }
                case "tools/list" -> {
                    for (String name : List.of(SEARCH, FETCH)) {
                        ObjectNode tool = result.withArray("tools").addObject().put("name", name);
                        tool.putObject("inputSchema").put("type", "object");
                    }
                }
                case "tools/call" -> {
                    String name = request.at("/params/name").asText();
                    if (!List.of(SEARCH, FETCH).contains(name)) violations.add("Wrong native tool name");
                    result.put("isError", false);
                    result.putArray("content").addObject().put("type", "text")
                            .put("text", SEARCH.equals(name) ? FIXTURE_PAGE : FIXTURE_TEXT);
                }
                default -> throw new IllegalStateException(method);
            }
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
