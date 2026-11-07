package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
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
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
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
 * Unit tests for image-attachment route gating:
 * - {@link SessionRunner#hasAttachmentImage} decides whether the last USER message contains image parts;
 * - at runtime runTurn restricts attachment turns to routes with supportsAttachment=true; if all are filtered out it publishes a system event and fails.
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
        org.mockito.Mockito.lenient().when(compactionService.resolveNominalChatMaxOutput(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(16_000);
        org.mockito.Mockito.lenient().when(compactionService.resolveRequestMaxOutput(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(16_000);
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, fallbackRouteExecutor, historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver,
                new com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer(
                        org.mockito.Mockito.mock(com.buukle.agent.common.config.SystemConfigSpi.class)),
                org.mockito.Mockito.mock(com.buukle.agent.instance.spi.ToolApprovalGrantSpi.class),
                org.mockito.Mockito.mock(com.buukle.agent.runtime.kernel.service.ToolApprovalService.class),
                new com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver(new com.buukle.agent.common.config.AgentRuntimeProperties()));
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
        // Attachment reclaimed/invalid: skipped silently during message assembly instead of degrading into an error message
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

    // ---------- stripImageParts (degrade to plain text when no image route exists) ----------

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

        assertEquals("[Image]", messages.get(0).getContent());
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
        // Does not really call the LLM: only verifies execute receives all routes and is not blocked
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
        // Images stripped: the last USER message degrades to plain text
        ChatMessageDTO last = messages.get(messages.size() - 1);
        assertEquals(LlmApiConstant.ROLE_USER, last.getRole());
        assertEquals("看图", last.getContent());
        // All routes are still executed (not filtered, no error)
        ArgumentCaptor<List<ModelRouteFullVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(fallbackRouteExecutor).execute(captor.capture(), any());
        assertEquals(2, captor.getValue().size());
        // Publishes a non-blocking degradation notice event (no longer a blocking error)
        assertTrue(events.stream().anyMatch(e ->
                        e.getEventType() instanceof FlowEventType
                                && e.getEventType() == FlowEventType.REASONING_TOKEN
                                && e.getData().getResponse() != null
                                && e.getData().getResponse().contains("continuing with text only")),
                "Should publish a REASONING_TOKEN system event noting the text-only degradation");
    }

    @Test
    void runTurn_attachmentRound_filtersToCapableRoutesOnly() throws Exception {
        given(properties.getRunner()).willReturn(new AgentRuntimeProperties.RunnerConfig());
        List<ModelRouteFullVO> routes = List.of(route(1L, false), route(2L, true));
        given(routeListBuilder.fromContext(any())).willReturn(routes);
        // Does not really call the LLM: only verifies execute receives the filtered set of attachment-capable routes
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
                KernelContext.class, AtomicReference.class, boolean.class,
                int.class, int.class, int.class, int.class, int.class, int.class);
        m.setAccessible(true);
        return (TurnResult) m.invoke(runner, 1L, 2L, messages, List.of(), List.of(),
                ctx, new AtomicReference<>(), false, 0, 0, 0, -1000, 0, 0);
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

    // ---------- Browser screenshot observation injection ----------

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

        // Keep feeding past the limit
        for (int i = 0; i < 25; i++) {
            List<ChatMessageDTO> messages = new ArrayList<>();
            invokeInjectScreenshot(messages, toolResult);
            if (messages.isEmpty()) {
                // Per-run limit reached
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
        // Reproduce the main Runner's batch assembly order: assistant(tool_calls:[c1,c2]) → tool(c1) → tool(c2) → deferred user(screenshot observation) injection
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

        // Deferred injection matching the fixed Runner: all tool messages are appended first, and the observation is appended at the end of the batch
        invokeInjectScreenshot(messages,
                "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\",\"width\":100,\"height\":200}}}");

        assertEquals(4, messages.size());
        ChatMessageDTO last = messages.get(3);
        assertEquals(LlmApiConstant.ROLE_USER, last.getRole());
        assertTrue(last.getContent() instanceof List<?>, "截图观察应以 parts 追加在全部 tool 消息之后");
        // The first three keep their order: assistant + two consecutive tool messages (no USER interleaved)
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