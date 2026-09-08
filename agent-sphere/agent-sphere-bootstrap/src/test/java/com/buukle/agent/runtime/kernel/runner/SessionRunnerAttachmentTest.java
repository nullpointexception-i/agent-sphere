package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
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
        org.mockito.Mockito.lenient()
                .when(properties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
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

    // ---------- stripImageParts（无图片路由时降级为纯文本） ----------

    @Test
    void stripImageParts_textWithImage_collapsesToTextOnly() {
        List<ChatMessageDTO> messages = new ArrayList<>(List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent(List.of(
                        new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText("看图"),
                        new ChatMessagePartDTO()
                                .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                                .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl("data:image/png;base64,AAAA"))))));

        SessionRunner.stripImageParts(messages);

        assertEquals("看图", messages.get(0).getContent());
    }

    @Test
    void stripImageParts_imageOnly_fallsBackToPlaceholder() {
        List<ChatMessageDTO> messages = new ArrayList<>(List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent(List.of(new ChatMessagePartDTO()
                        .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                        .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl("data:image/png;base64,AAAA"))))));

        SessionRunner.stripImageParts(messages);

        assertEquals("[图片]", messages.get(0).getContent());
    }

    @Test
    void stripImageParts_noUserParts_unchanged() {
        List<ChatMessageDTO> messages = new ArrayList<>(List.of(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_USER)
                .setContent("纯文本")));

        SessionRunner.stripImageParts(messages);

        assertEquals("纯文本", messages.get(0).getContent());
    }

    @Test
    void stripImageParts_lastNotUser_unchanged() {
        List<ChatMessageDTO> messages = new ArrayList<>(List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent(
                        List.of(new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                                .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl("data:image/png;base64,AAAA")))),
                new ChatMessageDTO().setRole("tool").setContent("done")));

        SessionRunner.stripImageParts(messages);

        assertEquals("done", messages.get(1).getContent());
    }

    // ---------- runTurn gating ----------

    @Test
    void runTurn_allRoutesUnsupported_degradesToTextAndContinues() throws Exception {
        given(properties.getRunner()).willReturn(new AgentRuntimeProperties.RunnerConfig());
        List<ModelRouteFullVO> routes = List.of(route(1L, false), route(2L, false));
        given(routeListBuilder.fromContext(any())).willReturn(routes);
        // 不真正走 LLM：仅验证 execute 收到全部路由且未阻断
        org.mockito.Mockito.doReturn(null)
                .when(fallbackRouteExecutor).execute(any(), any());

        List<RuntimeEventVO> events = new ArrayList<>();
        doAnswer(inv -> {
            events.add(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        List<ChatMessageDTO> messages = imagePartsMessages();
        TurnResult result = invokeRunTurn(messages, ctxWithModelRoute(routes));

        assertEquals(TurnOutcome.COMPLETE, result.outcome());
        // 图片被剥离：最后一条 USER 消息降级为纯文本
        ChatMessageDTO last = messages.get(messages.size() - 1);
        assertEquals(LlmApiConstant.ROLE_USER, last.getRole());
        assertEquals("看图", last.getContent());
        // 全部路由仍被执行（未过滤、未报错）
        ArgumentCaptor<List<ModelRouteFullVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(fallbackRouteExecutor).execute(captor.capture(), any());
        assertEquals(2, captor.getValue().size());
        // 发布非阻塞降级提示事件（不再是阻断错误）
        assertTrue(events.stream().anyMatch(e ->
                        e.getEventType() instanceof FlowEventType
                                && e.getEventType() == FlowEventType.REASONING_TOKEN
                                && e.getData().getResponse() != null
                                && e.getData().getResponse().contains("已按纯文本继续处理")),
                "应发布 REASONING_TOKEN 系统事件提示已降级为纯文本");
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

    // ---------- 浏览器截图观察注入 ----------

    @Test
    void injectScreenshotObservation_appendsUserMessageWithImage() throws Exception {
        given(attachmentResolver.toDataUrl("shot-1")).willReturn("data:image/jpeg;base64,BBBB");
        String toolResult = "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\","
                + "\"width\":1280,\"height\":800}}}";

        List<ChatMessageDTO> messages = new ArrayList<>();
        invokeInjectScreenshot(messages, toolResult);

        assertEquals(1, messages.size());
        assertEquals(LlmApiConstant.ROLE_USER, messages.get(0).getRole());
        List<?> parts = (List<?>) messages.get(0).getContent();
        assertEquals(2, parts.size());
        ChatMessagePartDTO text = (ChatMessagePartDTO) parts.get(0);
        assertEquals(ChatMessagePartDTO.TYPE_TEXT, text.getType());
        assertTrue(text.getText().contains("1280x800"));
        ChatMessagePartDTO img = (ChatMessagePartDTO) parts.get(1);
        assertEquals(ChatMessagePartDTO.TYPE_IMAGE_URL, img.getType());
        assertEquals("data:image/jpeg;base64,BBBB", img.getImageUrl().getUrl());
    }

    @Test
    void injectScreenshotObservation_noScreenshotField_skips() throws Exception {
        List<ChatMessageDTO> messages = new ArrayList<>();
        invokeInjectScreenshot(messages, "{\"success\":true,\"data\":{\"fileKey\":\"x\"}}");
        assertTrue(messages.isEmpty(), "无 data.screenshot 时不注入观察消息");
    }

    @Test
    void injectScreenshotObservation_resolvedNull_dropsImagePart() throws Exception {
        given(attachmentResolver.toDataUrl("shot-1")).willReturn(null);
        String toolResult = "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\"}}}";

        List<ChatMessageDTO> messages = new ArrayList<>();
        invokeInjectScreenshot(messages, toolResult);

        assertEquals(1, messages.size());
        List<?> parts = (List<?>) messages.get(0).getContent();
        assertEquals(1, parts.size(), "fileKey 无字节时仅保留文本观察");
    }

    @Test
    void injectScreenshotObservation_capsPerRun() throws Exception {
        given(attachmentResolver.toDataUrl("shot-1")).willReturn("data:image/jpeg;base64,BBBB");
        String toolResult = "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\"}}}";

        // 连灌超过上限
        for (int i = 0; i < 25; i++) {
            List<ChatMessageDTO> messages = new ArrayList<>();
            invokeInjectScreenshot(messages, toolResult);
            if (messages.isEmpty()) {
                // 已达每 run 上限
                List<ChatMessageDTO> probe = new ArrayList<>();
                invokeInjectScreenshot(probe, toolResult);
                assertTrue(probe.isEmpty(), "超过上限后不再注入");
                return;
            }
        }
        assertTrue(false, "应在 20 次后触发上限");
    }

    @Test
    void multiToolBatch_injectsObservationOnlyAfterAllToolMessages() throws Exception {
        // 复现主 Runner 的批次组装顺序：assistant(tool_calls:[c1,c2]) → tool(c1) → tool(c2) → 延后注入 user(截图观察)
        given(attachmentResolver.toDataUrl("shot-1")).willReturn("data:image/jpeg;base64,BBBB");

        List<ToolCallDTO> toolCalls = List.of(
                ToolCallDTO.builder().id("c1").type("function")
                        .function(FunctionDefinitionDTO.builder().name("builtin_5").arguments("{}").build()).build(),
                ToolCallDTO.builder().id("c2").type("function")
                        .function(FunctionDefinitionDTO.builder().name("builtin_2").arguments("{}").build()).build());
        List<ChatMessageDTO> messages = new ArrayList<>(List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_ASSISTANT).setToolCalls(toolCalls),
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_TOOL).setToolCallId("c1").setContent("tool-res-c1"),
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_TOOL).setToolCallId("c2").setContent("tool-res-c2")));

        // 与 Runner 修复后一致的延后注入：全部 tool 消息已追加，观察消息追加在批次末尾
        invokeInjectScreenshot(messages,
                "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\",\"width\":100,\"height\":200}}}");

        assertEquals(4, messages.size());
        ChatMessageDTO last = messages.get(3);
        assertEquals(LlmApiConstant.ROLE_USER, last.getRole());
        assertTrue(last.getContent() instanceof List<?>, "截图观察应以 parts 追加在全部 tool 消息之后");
        // 前三条顺序不变：assistant + 两个连续 tool 消息（无任何 USER 混插）
        assertEquals(LlmApiConstant.ROLE_ASSISTANT, messages.get(0).getRole());
        assertEquals(LlmApiConstant.ROLE_TOOL, messages.get(1).getRole());
        assertEquals(LlmApiConstant.ROLE_TOOL, messages.get(2).getRole());
    }

    private void invokeInjectScreenshot(List<ChatMessageDTO> messages, String toolResult) throws Exception {
        Method m = SessionRunner.class.getDeclaredMethod(
                "injectScreenshotObservation", Long.class, Long.class, List.class, String.class);
        m.setAccessible(true);
        m.invoke(runner, 1L, 2L, messages, toolResult);
    }
}