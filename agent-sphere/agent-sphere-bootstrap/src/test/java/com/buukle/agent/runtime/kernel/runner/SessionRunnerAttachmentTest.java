package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.contract.TurnOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 图片附件路由 gating 单测：
 * - {@link SessionRunner#hasAttachmentImage} 判定最后一条 USER 消息是否含图片 parts；
 * - runTurn 运行时把附件轮限制为 supportsAttachment=true 的路由，全滤空则发系统事件并报错。
 */
@ExtendWith(MockitoExtension.class)
class SessionRunnerAttachmentTest {

    @Mock
    AgentRuntimeProperties properties;
    @Mock
    RunSpi runSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    KernelLlmService kernelLlmService;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    RouteListBuilder routeListBuilder;
    @Mock
    FallbackRouteExecutor fallbackRouteExecutor;
    @Mock
    HistoryLoader historyLoader;
    @Mock
    CompactionService compactionService;
    @Mock
    SessionInputManager inputManager;
    @Mock
    RunPromptBuilder runPromptBuilder;
    @Mock
    ToolExecutor toolExecutor;
    @Mock
    TitleService titleService;
    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    ChatAttachmentResolver attachmentResolver;

    SessionRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, fallbackRouteExecutor, historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver);
    }

    // ---------- hasAttachmentImage ----------

    @Test
    void hasAttachmentImage_nullOrEmpty_false() {
        assertFalse(SessionRunner.hasAttachmentImage(null));
        assertFalse(SessionRunner.hasAttachmentImage(List.of()));
    }

    @Test
    void hasAttachmentImage_stringContent_false() {
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("你好"));
        assertFalse(SessionRunner.hasAttachmentImage(messages));
    }

    @Test
    void hasAttachmentImage_lastNotUser_false() {
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("你好"),
                new ChatMessageDTO().setRole("assistant").setContent("好的"));
        assertFalse(SessionRunner.hasAttachmentImage(messages));
    }

    @Test
    void hasAttachmentImage_textPartsOnly_false() {
        List<ChatMessageDTO> messages = List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent(List.of(new ChatMessagePartDTO()
                        .setType(ChatMessagePartDTO.TYPE_TEXT).setText("你好"))));
        assertFalse(SessionRunner.hasAttachmentImage(messages));
    }

    @Test
    void hasAttachmentImage_withImagePart_true() {
        List<ChatMessageDTO> messages = List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent(List.of(
                        new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText("看图"),
                        new ChatMessagePartDTO()
                                .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                                .setImageUrl(new ChatMessagePartDTO.ImageUrl()
                                        .setUrl("data:image/png;base64,AAAA")))));
        assertTrue(SessionRunner.hasAttachmentImage(messages));
    }

    // ---------- buildUserMessage ----------

    @Test
    void buildUserMessage_textOnly_plainStringContent() {
        SessionInputManager.InputMessage input = new SessionInputManager.InputMessage(
                "你好", null, 0L, false, List.of());

        ChatMessageDTO msg = SessionRunner.buildUserMessage(input, attachmentResolver);

        assertEquals("你好", msg.getContent());
    }

    @Test
    void buildUserMessage_withAttachment_resolvesDataUrlLazily() {
        given(attachmentResolver.toDataUrl("k1")).willReturn("data:image/png;base64,AAAA");
        SessionInputManager.InputMessage input = new SessionInputManager.InputMessage(
                "看图", null, 0L, false,
                List.of(new PreparedAttachment("k1", "image/png")));

        ChatMessageDTO msg = SessionRunner.buildUserMessage(input, attachmentResolver);

        List<?> parts = (List<?>) msg.getContent();
        assertEquals(2, parts.size());
        ChatMessagePartDTO image = (ChatMessagePartDTO) parts.get(1);
        assertEquals(ChatMessagePartDTO.TYPE_IMAGE_URL, image.getType());
        assertEquals("data:image/png;base64,AAAA", image.getImageUrl().getUrl());
    }

    @Test
    void buildUserMessage_resolverReturnsNull_skipsAttachment() {
        // 附件被回收/无效：组消息时静默跳过，不退化为错误消息
        given(attachmentResolver.toDataUrl("k1")).willReturn(null);
        SessionInputManager.InputMessage input = new SessionInputManager.InputMessage(
                "看图", null, 0L, false,
                List.of(new PreparedAttachment("k1", "image/png")));

        ChatMessageDTO msg = SessionRunner.buildUserMessage(input, attachmentResolver);

        List<?> parts = (List<?>) msg.getContent();
        assertEquals(1, parts.size(), "仅剩文本 part，图片 part 被跳过");
        ChatMessagePartDTO text = (ChatMessagePartDTO) parts.get(0);
        assertEquals(ChatMessagePartDTO.TYPE_TEXT, text.getType());
        assertEquals("看图", text.getText());
    }

    @Test
    void buildUserMessage_capsAtFourPartsInjected() {
        for (int i = 1; i <= 4; i++) {
            given(attachmentResolver.toDataUrl("k" + i)).willReturn("data:image/png;base64,AA" + i);
        }
        List<PreparedAttachment> attachments = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            attachments.add(new PreparedAttachment("k" + i, "image/png"));
        }
        SessionInputManager.InputMessage input = new SessionInputManager.InputMessage(
                "看图", null, 0L, false, attachments);

        ChatMessageDTO msg = SessionRunner.buildUserMessage(input, attachmentResolver);

        List<?> parts = (List<?>) msg.getContent();
        long images = parts.stream()
                .filter(p -> p instanceof ChatMessagePartDTO part
                        && ChatMessagePartDTO.TYPE_IMAGE_URL.equals(part.getType()))
                .count();
        assertEquals(4, images, "最多注入 4 个图片 parts");
    }

    // ---------- runTurn gating ----------

    @Test
    void runTurn_allRoutesUnsupported_publishesSystemEventAndErrors() throws Exception {
        given(properties.getRunner()).willReturn(new AgentRuntimeProperties.RunnerConfig());
        List<ModelRouteFullVO> routes = List.of(route(1L, false), route(2L, false));
        given(routeListBuilder.fromContext(any())).willReturn(routes);

        List<RuntimeEventVO> events = new ArrayList<>();
        doAnswer(inv -> {
            events.add(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        TurnResult result = invokeRunTurn(imagePartsMessages(), ctxWithModelRoute(routes));

        assertEquals(TurnOutcome.ERROR, result.outcome());
        assertEquals("当前路由均不支持附件输入", result.errorMessage());
        verify(fallbackRouteExecutor, never()).execute(any(), any());
        assertTrue(events.stream().anyMatch(e ->
                        e.getEventType() instanceof FlowEventType
                                && e.getEventType() == FlowEventType.REASONING_TOKEN
                                && e.getData().getResponse() != null
                                && e.getData().getResponse().contains("当前路由均未开启附件输入")),
                "应发布 REASONING_TOKEN 系统事件提示开启「支持附件」");
    }

    @Test
    void runTurn_attachmentRound_filtersToCapableRoutesOnly() throws Exception {
        given(properties.getRunner()).willReturn(new AgentRuntimeProperties.RunnerConfig());
        List<ModelRouteFullVO> routes = List.of(route(1L, false), route(2L, true));
        given(routeListBuilder.fromContext(any())).willReturn(routes);
        // 不真正走 LLM：仅验证 execute 收到的是过滤后的可附件路由集合
        org.mockito.Mockito.doReturn(null)
                .when(fallbackRouteExecutor).execute(any(), any());

        TurnResult result = invokeRunTurn(imagePartsMessages(), ctxWithModelRoute(routes));

        assertEquals(TurnOutcome.COMPLETE, result.outcome());
        ArgumentCaptor<List<ModelRouteFullVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(fallbackRouteExecutor).execute(captor.capture(), any());
        List<ModelRouteFullVO> passed = captor.getValue();
        assertEquals(1, passed.size());
        assertEquals(2L, passed.get(0).getId());
        assertTrue(passed.get(0).getSupportsAttachment());
    }

    @Test
    void runTurn_textMessage_noFilterApplied() throws Exception {
        given(properties.getRunner()).willReturn(new AgentRuntimeProperties.RunnerConfig());
        List<ModelRouteFullVO> routes = List.of(route(1L, false));
        given(routeListBuilder.fromContext(any())).willReturn(routes);
        org.mockito.Mockito.doReturn(null)
                .when(fallbackRouteExecutor).execute(any(), any());

        List<ChatMessageDTO> textMessages = List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("你好"));
        invokeRunTurn(textMessages, ctxWithModelRoute(routes));

        ArgumentCaptor<List<ModelRouteFullVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(fallbackRouteExecutor).execute(captor.capture(), any());
        assertEquals(1, captor.getValue().size());
        assertTrue(captor.getValue().get(0).getSupportsAttachment() == Boolean.FALSE);
    }

    private TurnResult invokeRunTurn(List<ChatMessageDTO> messages, KernelContext ctx) throws Exception {
        Method m = SessionRunner.class.getDeclaredMethod(
                "runTurn", Long.class, Long.class, List.class, List.class, List.class,
                KernelContext.class, AtomicReference.class);
        m.setAccessible(true);
        return (TurnResult) m.invoke(runner, 1L, 2L, messages, List.of(), List.of(),
                ctx, new AtomicReference<>());
    }

    private static List<ChatMessageDTO> imagePartsMessages() {
        return List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent(List.of(
                        new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText("看图"),
                        new ChatMessagePartDTO()
                                .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                                .setImageUrl(new ChatMessagePartDTO.ImageUrl()
                                        .setUrl("data:image/png;base64,AAAA")))));
    }

    private static KernelContext ctxWithModelRoute(List<ModelRouteFullVO> routes) {
        return KernelContext.builder()
                .modelRoute(routes.get(0))
                .fallbackRoutes(routes.subList(1, routes.size()))
                .build();
    }

    private static ModelRouteFullVO route(Long id, boolean supportsAttachment) {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(id);
        route.setModelName("gpt-4o-" + id);
        route.setCompany("openai");
        route.setBaseUrl("https://api.openai.com/v1");
        route.setApiKeyId(1L);
        route.setSupportsAttachment(supportsAttachment);
        return route;
    }
}