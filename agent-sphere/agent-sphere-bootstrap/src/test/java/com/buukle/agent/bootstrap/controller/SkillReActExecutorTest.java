package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
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
                new AgentRuntimeProperties());
    }

    private RuntimeTool skillTool() {
        return RuntimeTool.builder()
                .capabilityType("skill")
                .capabilityId(5L)
                .llmToolName("skill_5")
                .toolRef("skill:5")
                .displayName("测试技能")
                .description("测试")
                .parametersSchemaJson("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
                .execBinding(Map.of(
                        ExecBindingKeys.SKILL_PROMPT_TEMPLATE, "请分析 {{q}}",
                        ExecBindingKeys.SKILL_ALLOW_TOOLS, List.of()))
                .build();
    }

    private SkillExecutionContext rootCtx() {
        return SkillExecutionContext.root(1L, 2L, null);
    }

    @Test
    void singleShot_returnsModelFinalAnswer() {
        given(apiKeySpi.getApiKeyValue(any())).willReturn("test-key");
        doAnswer(inv -> {
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            Runnable done = inv.getArgument(6);
            onEvent.accept(new LLMEvent.TextDelta("最终答案"));
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any());

        KernelContext ctx = KernelContext.builder()
                .agentInstance(instance("Headhunter"))
                .modelRoute(route())
                .build();
        String result = executor.execute(skillTool(), "{\"q\":\"张三\"}",
                SkillExecutionContext.root(1L, 2L, ctx), List.of());

        assertTrue(result.contains("最终答案"));
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