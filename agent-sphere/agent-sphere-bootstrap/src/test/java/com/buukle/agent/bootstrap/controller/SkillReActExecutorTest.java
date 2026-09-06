package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.SkillExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.skill.SkillReActExecutor;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SkillReActExecutorTest {

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

    SkillReActExecutor executor;

    @BeforeEach
    void setUp() {
        RouteListBuilder routeListBuilder = new RouteListBuilder(routeSpi, instanceSpi, modelProviderSpi);
        KernelLlmService llmService = new KernelLlmService(modelProviderSpi, eventPublisher, new AgentRuntimeProperties());
        FallbackRouteExecutor fallbackRouteExecutor = new FallbackRouteExecutor();
        RSet<Object> set = mock(RSet.class);
        org.mockito.Mockito.lenient().when(set.contains(Boolean.TRUE)).thenReturn(false);
        org.mockito.Mockito.lenient().when(redissonClient.getSet(anyString())).thenReturn(set);
        executor = new SkillReActExecutor(llmService, fallbackRouteExecutor, routeListBuilder, apiKeySpi,
                toolExecutor, new RunPromptBuilder(), eventPublisher, redissonClient,
                new AgentRuntimeProperties(),
                org.mockito.Mockito.mock(com.buukle.agent.instance.spi.AgentSubAgentRunSpi.class));
    }

    private RuntimeTool skillTool() {
        java.util.Map<String, Object> binding = new java.util.HashMap<>();
        binding.put(ExecBindingKeys.SKILL_PROMPT_TEMPLATE, "请分析 {{q}}\n{{input}}");
        binding.put(ExecBindingKeys.SKILL_ALLOW_TOOLS, List.of());
        return RuntimeTool.builder()
                .capabilityType("skill")
                .capabilityId(5L)
                .llmToolName("skill_5")
                .toolRef("skill:5")
                .displayName("测试技能")
                .description("测试")
                .parametersSchemaJson("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
                .execBinding(binding)
                .build();
    }

    private SkillExecutionContext rootCtx() {
        return SkillExecutionContext.root(1L, 2L, null);
    }

    @Test
    void taskPayload_isInjectedWhenArgumentsEmpty() {
        // skill 工具 schema 为空时，LLM 调用 argumentsJson 恒为 {}；
        // 修复后必须把根上下文的本轮用户消息全文注入 {{input}}，子 Agent 才能看到任务。
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            String userContent = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(m -> m.getContent() != null ? m.getContent() : "")
                    .reduce("", (a, b) -> a + b);
            assertTrue(userContent.contains("流程管理"));
            assertTrue(userContent.contains("【任务配置】"));
            assertTrue(!userContent.contains("{}"));
            onEvent.accept(new LLMEvent.TextDelta("最终答案"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        String taskMessage = "请加载相关 skill，执行寻访候选人任务。\n【任务配置】{\"step1\":\"打开渠道A\",\"keywords\":[\"流程管理\",\"IPD\"],\"city\":\"深圳\",\"years\":\"3-5年\",\"degree\":\"本科及以上\",\"salary\":\"≥20K\"}";
        KernelContext ctx = KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .userMessage(taskMessage)
                .build();
        String result = executor.execute(skillTool(), "{}",
                SkillExecutionContext.root(1L, 2L, ctx), List.of());

        assertTrue(result.contains("最终答案"));
    }

    @Test
    void taskPayload_isInjectedWithArgsWhenBothPresent() {
        // 带参调用：{{input}} 注入本轮用户消息全文，自定义入参也保留在 args 通道。
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            String userContent = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(m -> m.getContent() != null ? m.getContent() : "")
                    .reduce("", (a, b) -> a + b);
            assertTrue(userContent.contains("流程管理"));
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        String taskMessage = "请加载相关 skill，执行寻访候选人任务。\n【任务配置】{\"keywords\":[\"流程管理\",\"IPD\"]}";
        KernelContext ctx = KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .userMessage(taskMessage)
                .build();
        executor.execute(skillTool(), "{\"q\":\"张三\"}",
                SkillExecutionContext.root(1L, 2L, ctx), List.of());
    }

    @Test
    void legacyPlaceholder_q_stillResolvesWithTaskInjection() {
        // 兼容：skill 的 {{q}} 占位符在注入 input 后仍能解析（顶层字段保留），不会因合并被破坏。
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            String userContent = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(m -> m.getContent() != null ? m.getContent() : "")
                    .reduce("", (a, b) -> a + b);
            assertTrue(userContent.contains("张三"), "{{q}} 占位符必须解析为 张三");
            assertTrue(userContent.contains("【任务配置】"), "{{input}} 必须注入任务上下文");
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        String taskMessage = "请加载 skill，执行寻访候选人任务。\n【任务配置】{\"keywords\":[\"流程管理\"]}";
        KernelContext ctx = KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .userMessage(taskMessage)
                .build();
        executor.execute(skillTool(), "{\"q\":\"张三\"}",
                SkillExecutionContext.root(1L, 2L, ctx), List.of());
    }

    @Test
    void reasoning_isEchoedBackOnToolCallAssistantMessage() {
        // DeepSeek thinking 模式要求带 tool_calls 的 assistant 消息回传上轮 reasoning_content，
        // 否则 400 "reasoning_content must be passed back"。子 Agent 内第 2+ 轮校验。
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        given(toolExecutor.execute(any(), any(), any())).willReturn("{}");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            ChatCompletionRequestDTO request = inv.getArgument(4);
            List<ChatMessageDTO> msgs = request.getMessages();
            boolean hasToolCallWithReasoning = msgs.stream()
                    .anyMatch(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty()
                            && m.getReasoningContent() != null && !m.getReasoningContent().isBlank());
            if (hasToolCallWithReasoning) {
                // 已满足回传 —— 让子 Agent 结束
                onEvent.accept(new LLMEvent.TextDelta("完成"));
            } else {
                // 第 1 轮：产出 reasoning + tool_call，触发下一轮判断
                onEvent.accept(new LLMEvent.ReasoningDelta("model reasoning"));
                onEvent.accept(new LLMEvent.ToolCall("tc-1", "builtin_5", "{}"));
            }
            Runnable done = inv.getArgument(6);
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        RuntimeTool tool = skillTool();
        tool.getExecBinding().put(ExecBindingKeys.SKILL_ALLOW_TOOLS,
                List.of("builtin:builtin.CapabilityBuiltinToolChrome"));
        KernelContext kctx = KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .build();
        String result = executor.execute(tool, "{}",
                SkillExecutionContext.root(1L, 2L, kctx), List.of(RuntimeTool.builder()
                        .capabilityType("builtin")
                        .capabilityId(5L)
                        .llmToolName("builtin_5")
                        .toolRef("builtin:builtin.CapabilityBuiltinToolChrome")
                        .displayName("Chrome")
                        .parametersSchemaJson("{\"type\":\"object\",\"properties\":{}}")
                        .build()));

        assertTrue(result.contains("完成"));
    }

    @Test
    void skillReasoning_eventCarriesSkillMarker() {
        // 子 Agent thinking 展示事件必须带 skill 标记（nodeName=skill:<id> + 首帧哨兵前缀），
        // 前端据此把 skill 推理内嵌为主气泡内的可折叠块；次帧不得重复前缀。
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
        org.mockito.Mockito.doAnswer(inv -> {
            reasoningEvents.add(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        executor.execute(skillTool(), "{}",
                SkillExecutionContext.root(1L, 2L, kernelCtx()), List.of());

        List<RuntimeEventDataVO> withSkillMarker = reasoningEvents.stream()
                .filter(e -> e.getEventType() instanceof FlowEventType f
                        && f == FlowEventType.REASONING_TOKEN)
                .map(RuntimeEventVO::getData)
                .filter(d -> "skill:".equals(d.getNodeName())
                        || (d.getResponse() != null
                        && d.getResponse().startsWith("▶ Skill ")))
                .toList();
        assertTrue(!withSkillMarker.isEmpty(), "skill reasoning 事件应带 nodeName=skill: 或哨兵前缀");
        assertEquals(1, withSkillMarker.stream()
                        .filter(d -> d.getResponse() != null
                                && d.getResponse().startsWith("▶ Skill "))
                        .count(), "哨兵前缀应只出现在首帧");
        assertTrue(withSkillMarker.stream().anyMatch(d -> "skill:5".equals(d.getNodeName())),
                "nodeName 应为 skill:5");
    }

    private KernelContext kernelCtx() {
        return KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .build();
    }

    @Test
    void recursiveCall_rejected() {
        // skillId = 5；父栈中已含 5 → 递归拒绝
        SkillExecutionContext parent = rootCtx().child(1, List.of(5L), null, null);
        String result = executor.execute(skillTool(), "{}", parent, List.of());
        assertTrue(result.contains("recursive call"));
    }

    @Test
    void depthExceeded_rejected() {
        // 当前深度 3，最大嵌套 3 → 进入即超限
        SkillExecutionContext parent = rootCtx().child(3, List.of(7L), null, null);
        String result = executor.execute(skillTool(), "{}", parent, List.of());
        assertTrue(result.contains("nested depth"));
    }

    @Test
    void missingPromptTemplate_error() {
        RuntimeTool noPrompt = RuntimeTool.builder()
                .capabilityType("skill")
                .capabilityId(5L)
                .llmToolName("skill_5")
                .toolRef("skill:5")
                .displayName("x")
                .execBinding(Map.of())
                .build();
        String result = executor.execute(noPrompt, "{}", rootCtx(), List.of());
        assertTrue(result.contains("promptTemplate"));
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