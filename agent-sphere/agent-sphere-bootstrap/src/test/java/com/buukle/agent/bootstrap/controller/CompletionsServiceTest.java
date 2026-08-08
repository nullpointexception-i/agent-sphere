package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.completions.domain.AgentCompletions;
import com.buukle.agent.completions.domain.AgentCompletionsCall;
import com.buukle.agent.completions.domain.AgentCompletionsPrompt;
import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.completions.exception.CompletionsErrorCode;
import com.buukle.agent.completions.repository.CompletionsMapper;
import com.buukle.agent.completions.repository.CompletionsPromptMapper;
import com.buukle.agent.completions.service.CompletionsCallService;
import com.buukle.agent.completions.service.CompletionsPromptService;
import com.buukle.agent.completions.service.impl.CompletionsServiceImpl;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.sso.spi.ResolvedIdentityVO;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CompletionsServiceTest {

    private static final CallerAuth AUTH = CallerAuth.of("bole", "elvin", "sourcing");

    @Mock
    CompletionsMapper completionsMapper;
    @Mock
    CompletionsPromptMapper promptMapper;
    @Mock
    CompletionsPromptService completionsPromptService;
    @Mock
    CompletionsCallService completionsCallService;
    @Mock
    RouteListBuilder routeListBuilder;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    KernelLlmService llmService;
    @Mock
    SsoIdentitySpi ssoIdentitySpi;

    @InjectMocks
    CompletionsServiceImpl completionsService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(completionsService, "callTimeout", Duration.ofSeconds(60));
        given(ssoIdentitySpi.resolveByCodeSubject("bole", "elvin"))
                .willReturn(ResolvedIdentityVO.of(1L, "elvin", "Elvin"));
    }

    private void stubCompletions(AgentCompletions c) {
        c.setBusinessType("sourcing");
        given(completionsMapper.selectOne(any())).willReturn(c);
    }

    private ModelRouteFullVO route() {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(10L);
        route.setApiKeyId(99L);
        route.setCompany("openai");
        route.setBaseUrl("https://api.openai.com/v1");
        route.setModelName("gpt-4o");
        given(routeListBuilder.fromRouteId(10L)).willReturn(List.of(route));
        given(apiKeySpi.getApiKeyValue(99L)).willReturn("sk-test");
        return route;
    }

    @Test
    void execute_success_shouldReturnContentAndRecordCall() {
        AgentCompletions c = new AgentCompletions();
        c.setId(1L);
        c.setStatus("ACTIVE");
        c.setModelRouteId(10L);
        c.setActivePromptId(20L);
        stubCompletions(c);

        AgentCompletionsPrompt prompt = new AgentCompletionsPrompt();
        prompt.setId(20L);
        prompt.setPromptSystem("你是助手");
        prompt.setPromptUser("你好 {{name}}，任务：{{input}}");
        given(promptMapper.selectById(20L)).willReturn(prompt);

        route();
        KernelLlmService.InvokeResult result = new KernelLlmService.InvokeResult();
        result.setSuccess(true);
        result.setContent("好的，已处理");
        result.setUsage(Map.of("total_tokens", 42));
        given(llmService.invokeSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(LlmInteractionMeta.class), anyLong())).willReturn(result);

        ChatCompletionsResp resp = completionsService.execute(AUTH, CompletionsInput.of(Map.of("name", "张三", "input", "订单 123")));

        assertNotNull(resp);
        assertEquals("好的，已处理", resp.getContent());
        assertEquals("gpt-4o", resp.getModel());
        assertEquals(Map.of("total_tokens", 42), resp.getUsage());

        ArgumentCaptor<AgentCompletionsCall> callCaptor = ArgumentCaptor.forClass(AgentCompletionsCall.class);
        verify(completionsCallService).record(callCaptor.capture());
        AgentCompletionsCall call = callCaptor.getValue();
        assertEquals(1L, call.getCompletionsId());
        assertEquals("好的，已处理", call.getOutput());
        assertEquals("SUCCESS", call.getStatus());
        assertEquals("elvin", call.getCaller());
    }

    @Test
    void execute_noCompletionsForBusinessType_shouldThrow() {
        given(completionsMapper.selectOne(any())).willReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> completionsService.execute(AUTH, CompletionsInput.of(Map.of())));
        assertEquals(CompletionsErrorCode.COMPLETIONS_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void execute_invalidIdentity_shouldThrow() {
        given(ssoIdentitySpi.resolveByCodeSubject("bole", "elvin")).willReturn(null);

        assertThrows(BizException.class,
                () -> completionsService.execute(AUTH, CompletionsInput.of(Map.of())));
    }

    @Test
    void execute_routeFailThenFallback_shouldSucceedWithSecondRoute() {
        AgentCompletions c = new AgentCompletions();
        c.setId(1L);
        c.setStatus("ACTIVE");
        c.setModelRouteId(10L);
        c.setActivePromptId(20L);
        stubCompletions(c);

        AgentCompletionsPrompt prompt = new AgentCompletionsPrompt();
        prompt.setId(20L);
        prompt.setPromptUser("{{input}}");
        given(promptMapper.selectById(20L)).willReturn(prompt);

        ModelRouteFullVO routeA = new ModelRouteFullVO();
        routeA.setId(10L);
        routeA.setApiKeyId(99L);
        routeA.setCompany("openai");
        routeA.setBaseUrl("https://a.example");
        routeA.setModelName("gpt-4o");
        ModelRouteFullVO routeB = new ModelRouteFullVO();
        routeB.setId(11L);
        routeB.setApiKeyId(99L);
        routeB.setCompany("anthropic");
        routeB.setBaseUrl("https://b.example");
        routeB.setModelName("claude-3-5");
        given(routeListBuilder.fromRouteId(10L)).willReturn(List.of(routeA, routeB));
        given(apiKeySpi.getApiKeyValue(99L)).willReturn("sk-test");

        KernelLlmService.InvokeResult failed = new KernelLlmService.InvokeResult();
        failed.setSuccess(false);
        failed.setError("rate limited");
        KernelLlmService.InvokeResult ok = new KernelLlmService.InvokeResult();
        ok.setSuccess(true);
        ok.setContent("fallback 成功");
        ok.setUsage(Map.of("total_tokens", 10));
        given(llmService.invokeSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(LlmInteractionMeta.class), anyLong())).willReturn(failed, ok);

        ChatCompletionsResp resp = completionsService.execute(AUTH, CompletionsInput.of(Map.of()));

        assertEquals("fallback 成功", resp.getContent());
        assertEquals("claude-3-5", resp.getModel());
    }

    @Test
    void execute_invokeSyncThrows_shouldFallbackToNextRoute() {
        AgentCompletions c = new AgentCompletions();
        c.setId(1L);
        c.setStatus("ACTIVE");
        c.setModelRouteId(10L);
        c.setActivePromptId(20L);
        stubCompletions(c);

        AgentCompletionsPrompt prompt = new AgentCompletionsPrompt();
        prompt.setId(20L);
        prompt.setPromptUser("{{input}}");
        given(promptMapper.selectById(20L)).willReturn(prompt);

        ModelRouteFullVO routeA = new ModelRouteFullVO();
        routeA.setId(10L);
        routeA.setApiKeyId(99L);
        routeA.setCompany("openai");
        routeA.setBaseUrl("https://a.example");
        routeA.setModelName("gpt-4o");
        ModelRouteFullVO routeB = new ModelRouteFullVO();
        routeB.setId(11L);
        routeB.setApiKeyId(99L);
        routeB.setCompany("anthropic");
        routeB.setBaseUrl("https://b.example");
        routeB.setModelName("claude-3-5");
        given(routeListBuilder.fromRouteId(10L)).willReturn(List.of(routeA, routeB));
        given(apiKeySpi.getApiKeyValue(99L)).willReturn("sk-test");

        KernelLlmService.InvokeResult ok = new KernelLlmService.InvokeResult();
        ok.setSuccess(true);
        ok.setContent("second route ok");
        given(llmService.invokeSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(LlmInteractionMeta.class), anyLong()))
                .willThrow(new RuntimeException("connection refused"))
                .willReturn(ok);

        ChatCompletionsResp resp = completionsService.execute(AUTH, CompletionsInput.of(Map.of()));

        assertEquals("second route ok", resp.getContent());
        assertEquals("claude-3-5", resp.getModel());
    }

    @Test
    void execute_configShouldMapToRequestParams() {
        AgentCompletions c = new AgentCompletions();
        c.setId(1L);
        c.setStatus("ACTIVE");
        c.setModelRouteId(10L);
        c.setActivePromptId(20L);
        c.setConfig("{\"temperature\":0.3,\"thinking\":false,\"max_tokens\":1024}");
        stubCompletions(c);

        AgentCompletionsPrompt prompt = new AgentCompletionsPrompt();
        prompt.setId(20L);
        prompt.setPromptUser("{{input}}");
        given(promptMapper.selectById(20L)).willReturn(prompt);

        route();
        KernelLlmService.InvokeResult result = new KernelLlmService.InvokeResult();
        result.setSuccess(true);
        result.setContent("ok");
        given(llmService.invokeSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(LlmInteractionMeta.class), anyLong())).willReturn(result);

        completionsService.execute(AUTH, CompletionsInput.of(Map.of()));

        ArgumentCaptor<ChatCompletionRequestDTO> reqCaptor = ArgumentCaptor.forClass(ChatCompletionRequestDTO.class);
        verify(llmService).invokeSync(anyString(), anyString(), anyString(), anyString(),
                reqCaptor.capture(), any(LlmInteractionMeta.class), anyLong());
        ChatCompletionRequestDTO req = reqCaptor.getValue();
        assertEquals(0.3, req.getTemperature());
        assertEquals(1024, req.getMaxTokens());
        assertEquals("disabled", req.getThinking().getType());
    }
}
