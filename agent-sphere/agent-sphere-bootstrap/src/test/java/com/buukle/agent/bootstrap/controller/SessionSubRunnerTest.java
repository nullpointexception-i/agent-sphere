package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.sub.SessionSubRunner;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentPolicy;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionSubRunnerTest {

    private static final String TOOL_REF = ToolRefs.agent(SubAgentConstants.DELEGATE_TOOL);

    @Mock
    ModelProviderSpi modelProviderSpi;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    ToolExecutor toolExecutor;
    @Mock
    RouteSpi routeSpi;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    AgentSubAgentRunSpi subAgentRunSpi;

    SessionSubRunner executor;

    @BeforeEach
    void setUp() {
        RouteListBuilder routeListBuilder = new RouteListBuilder(routeSpi, instanceSpi, modelProviderSpi);
        KernelLlmService llmService = new KernelLlmService(modelProviderSpi, eventPublisher, new AgentRuntimeProperties());
        FallbackRouteExecutor fallbackRouteExecutor = new FallbackRouteExecutor();
        RSet<Object> set = mock(RSet.class);
        lenient().when(set.contains(Boolean.TRUE)).thenReturn(false);
        lenient().when(redissonClient.getSet(anyString())).thenReturn(set);
        ObjectProvider<com.buukle.agent.instance.spi.AgentTimelineSpi> timelineProvider = mock(ObjectProvider.class);
        executor = new SessionSubRunner(llmService, fallbackRouteExecutor, routeListBuilder, apiKeySpi,
                toolExecutor, new RunPromptBuilder(), eventPublisher, redissonClient,
                new AgentRuntimeProperties(),
                new SubAgentPolicy(new AgentRuntimeProperties()),
                subAgentRunSpi,
                timelineProvider,
                mock(ChatAttachmentResolver.class),
                new LlmRequestConfigurer(mock(com.buukle.agent.common.config.SystemConfigSpi.class)));
    }

    private RuntimeTool delegateTool(String system) {
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.DELEGATE_INSTRUCTION, "请分析 {{q}}");
        if (system != null) {
            binding.put(ExecBindingKeys.DELEGATE_SYSTEM, system);
        }
        return RuntimeTool.builder()
                .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                .toolRef(TOOL_REF)
                .displayName("测试子Agent")
                .description("测试")
                .parametersSchemaJson("{\"type\":\"object\",\"properties\":{}}")
                .execBinding(binding)
                .build();
    }

    private SubRunExecutionContext rootCtx() {
        return SubRunExecutionContext.root(1L, 2L, kernelCtx());
    }

    @Test
    void singleTurn_returnsContent() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            onEvent.accept(new LLMEvent.TextDelta("最终答案"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        String result = executor.execute(delegateTool(null), "{}", rootCtx(), List.of());

        assertTrue(result.contains("最终答案"));
    }

    @Test
    void systemPrompt_isPrependedWhenAgentRefProvidesOne() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            String system = request.getMessages().stream()
                    .filter(m -> "system".equals(m.getRole()))
                    .map(m -> String.valueOf(m.getContent()))
                    .reduce("", (a, b) -> a + b);
            assertTrue(system.contains("INSTANCE-SYSTEM-PROMPT"));
            onEvent.accept(new LLMEvent.TextDelta("ok"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        executor.execute(delegateTool("INSTANCE-SYSTEM-PROMPT"), "{}", rootCtx(), List.of());
    }

    @Test
    void recursiveCall_rejected_byAncestry() {
        SubRunExecutionContext parent = rootCtx().child(1, List.of(TOOL_REF), "call-1", null);
        String result = executor.execute(delegateTool(null), "{}", parent, List.of());
        assertTrue(result.contains("recursive call"));
    }

    @Test
    void depthExceeded_rejected() {
        // 当前深度 3，默认最大嵌套 3 → 进入即超限
        SubRunExecutionContext parent = rootCtx().child(3, List.of("agent:other"), null, null);
        String result = executor.execute(delegateTool(null), "{}", parent, List.of());
        assertTrue(result.contains("nested depth"));
    }

    @Test
    void start_writesParentRunIdFromOwner() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        AgentSubAgentRunVO vo = new AgentSubAgentRunVO();
        vo.setId(1000L);
        given(subAgentRunSpi.start(any(), any(), any(), any(), anyString(), anyString(), anyString()))
                .willReturn(vo);
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            onEvent.accept(new LLMEvent.TextDelta("done"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        // 顶层：owner=null → parent_run_id=null
        executor.execute(delegateTool(null), "{}", rootCtx(), List.of());
        ArgumentCaptor<Long> topParent = ArgumentCaptor.forClass(Long.class);
        verify(subAgentRunSpi).start(eq(1L), eq(2L), topParent.capture(), any(), eq("AGENT"), anyString(), anyString());
        assertNull(topParent.getValue(), "顶层子 run 的 parent_run_id 应为 null");

        // 嵌套：owner=99 → parent_run_id=99
        SubRunExecutionContext nested = rootCtx().child(1, List.of("agent:other"), "parent-call", 99L);
        executor.execute(delegateTool(null), "{}", nested, List.of());
        ArgumentCaptor<Long> nestedParent = ArgumentCaptor.forClass(Long.class);
        verify(subAgentRunSpi, org.mockito.Mockito.times(2))
                .start(eq(1L), eq(2L), nestedParent.capture(), any(), eq("AGENT"), anyString(), anyString());
        assertEquals(99L, nestedParent.getValue(), "嵌套子 run 的 parent_run_id 应为父 sub_agent_run id");
    }

    @Test
    void reasoning_eventsCarryAgentMarker() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            onEvent.accept(new LLMEvent.ReasoningDelta("第一步推理"));
            onEvent.accept(new LLMEvent.ReasoningDelta("第二步推理"));
            onEvent.accept(new LLMEvent.TextDelta("最终答案"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        List<RuntimeEventVO> reasoningEvents = new ArrayList<>();
        doAnswer(inv -> {
            reasoningEvents.add(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        executor.execute(delegateTool(null), "{}", rootCtx(), List.of());

        List<RuntimeEventDataVO> withAgentMarker = reasoningEvents.stream()
                .filter(e -> e.getEventType() instanceof FlowEventType f
                        && f == FlowEventType.REASONING_TOKEN)
                .map(RuntimeEventVO::getData)
                .filter(d -> (d.getNodeName() != null && d.getNodeName().startsWith("agent:"))
                        || (d.getResponse() != null && d.getResponse().startsWith("▶ Agent ")))
                .toList();
        assertTrue(!withAgentMarker.isEmpty(), "子 Agent reasoning 事件应带 nodeName=agent: 或哨兵前缀");
        assertEquals(1, withAgentMarker.stream()
                        .filter(d -> d.getResponse() != null && d.getResponse().startsWith("▶ Agent "))
                        .count(), "哨兵前缀应只出现在首帧");
    }

    @Test
    void contactEnvelope_isAppendedAfterRenderedGoal() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            List<String> userMessages = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(m -> String.valueOf(m.getContent()))
                    .toList();
            assertTrue(userMessages.size() >= 2, "应包含 goal 渲染消息 + contact envelope");
            assertTrue(userMessages.get(userMessages.size() - 1).contains("v1-delegate-contact"),
                    "contact envelope 应作为最后一条 user 消息存在");
            onEvent.accept(new LLMEvent.TextDelta("ok"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.DELEGATE_INSTRUCTION, "请分析 {{q}}");
        binding.put(ExecBindingKeys.DELEGATE_CONTACT,
                "{\"schema\":\"v1-delegate-contact\",\"laneKey\":\"k_a\",\"args\":{\"q\":\"1\"},\"upstream\":{\"k_b\":{\"result\":\"ok\"}}}");
        RuntimeTool tool = RuntimeTool.builder()
                .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                .toolRef(TOOL_REF)
                .displayName("测试子Agent")
                .description("测试")
                .parametersSchemaJson("{\"type\":\"object\",\"properties\":{}}")
                .execBinding(binding)
                .build();

        executor.execute(tool, "{}", rootCtx(), List.of());
    }

    private KernelContext kernelCtx() {
        return KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .build();
    }

    private static InstanceVO instance(String name) {
        InstanceVO vo = new InstanceVO();
        vo.setId(1L);
        vo.setName(name);
        vo.setSystemPrompt("You are a helpful assistant.");
        return vo;
    }

    private static ModelRouteFullVO route() {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(1L);
        route.setModelName("deepseek-v4-flash");
        route.setCompany("deepseek");
        route.setBaseUrl("https://api.deepseek.com");
        route.setApiKeyId(1L);
        return route;
    }
}
