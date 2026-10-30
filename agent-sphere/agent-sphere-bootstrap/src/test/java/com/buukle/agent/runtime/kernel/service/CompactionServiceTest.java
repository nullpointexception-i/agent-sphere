package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.AgentRuntimeProperties.RunnerConfig.CompactionTier;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 压缩阈值必须计入工具 schema：线上 32k 路由（max_input=32000、10 个工具约 11.7k token）在
 * 实际请求 32,852 token 时报 400，而 messages 估算只有 19,953 —— 低于 0.7×32000=22,400，
 * 压缩从未触发。本测试用失败请求的真实字符数锁定「messages + tools × 1.1」。
 */
class CompactionServiceTest {

    private CompactionService service;
    private AgentRuntimeProperties props;

    @BeforeEach
    void setUp() {
        props = new AgentRuntimeProperties();
        props.getRunner().getCompaction().setBudgetRatio(0.7);
        service = new CompactionService(props,
                mock(RunSpi.class), mock(SessionSpi.class), mock(KernelLlmService.class),
                mock(ApiKeySpi.class), mock(AgentCompactRecordSpi.class),
                mock(AgentToolCallRecordSpi.class), mock(ApplicationEventPublisher.class),
                mock(RouteListBuilder.class), mock(FallbackRouteExecutor.class),
                mock(LlmRequestConfigurer.class));
    }

    private static ModelRouteFullVO route(long id, Long maxInput) {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(id);
        route.setMaxInputTokens(maxInput);
        return route;
    }

    /** 复现失败请求：messages 58,564 chars + tool args 1,295 chars；10 个工具的 JSON 约 35k chars。 */
    private static List<ChatMessageDTO> failingMessages() {
        ToolCallDTO call = ToolCallDTO.builder()
                .id("call-1").type("function")
                .function(FunctionDefinitionDTO.builder()
                        .name("builtin_5").arguments("y".repeat(1295)).build())
                .build();
        return List.of(new ChatMessageDTO()
                .setRole("assistant")
                .setContent("x".repeat(58_564))
                .setToolCalls(List.of(call)));
    }

    private static List<ToolDefinitionDTO> toolsWithJsonChars(int descriptionChars) {
        return List.of(ToolDefinitionDTO.builder()
                .type("function")
                .function(FunctionDefinitionDTO.builder()
                        .name("builtin_5")
                        .description("d".repeat(descriptionChars))
                        .parameters(Map.of())
                        .build())
                .build());
    }

    @Test
    void messagesAloneStaysUnderBudget_butToolsPushItOver() {
        ModelRouteFullVO route = route(101L, 32_000L);
        // 18,000 chars → 10,000 tokens；×1.1=11,000 < 22,400
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("u".repeat(18_000)));
        List<ToolDefinitionDTO> tools = toolsWithJsonChars(40_000);

        assertFalse(service.shouldCompact(messages, route, List.of(), null),
                "只算 messages 时估算低于预算");

        // tools 40,000 chars → ≈11,429 tokens（JSON 用 3.5 除数）；合计 ≈23,571 > 22,400
        assertTrue(service.shouldCompact(messages, route, tools, null),
                "工具 schema 必须计入阈值，否则实际 prompt 超 32k 才报错");
    }

    @Test
    void safetyFactorCatchesBorderlineCase() {
        ModelRouteFullVO route = route(101L, 32_000L);
        // 38,000 chars → 21,111 tokens（无安全系数 < 22,400）；×1.1 ≈23,222 > 22,400
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("u".repeat(38_000)));

        assertTrue(service.shouldCompact(messages, route, List.of(), null),
                "10% 安全系数应覆盖字符密度的偏低估算");
    }

    @Test
    void chineseDensityTriggersCompactionWhereOldDivisorDidNot() {
        ModelRouteFullVO route = route(101L, 32_000L);
        // 40,000 chars：旧除数 3 → 13,333×1.1≈14,667（不触发）；1.8 → 22,222×1.1≈24,444（触发）
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("中".repeat(40_000)));

        assertTrue(service.shouldCompact(messages, route, List.of(), null),
                "1.8 字/token 才能让中文对话在溢出前触发压缩");
    }

    @Test
    void infeasibleCompactionIsSkipped_whenFixedPartExceedsMaxInput() {
        // 线上 route 4：max_input=20000；主 system（30k chars≈16.7k）+ 工具（35k chars≈19.4k）已超硬上限。
        ModelRouteFullVO route = route(4L, 20_000L);
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole("system").setContent("s".repeat(30_000)));
        messages.add(new ChatMessageDTO().setRole("user").setContent("hi"));
        List<ToolDefinitionDTO> tools = toolsWithJsonChars(35_000);

        assertFalse(service.shouldCompact(messages, route, tools, null),
                "固定开销已超 max_input，压缩不可能成功，必须跳过");
        assertFalse(service.shouldCompact(messages, route, tools, null),
                "重复判断仍应跳过（每路由只 WARN 一次）");
    }

    @Test
    void reducibleHistoryStillCompacts_whenFixedPartFitsBudget() {
        ModelRouteFullVO route = route(4L, 20_000L);
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole("system").setContent("s".repeat(6_000)));
        messages.add(new ChatMessageDTO().setRole("user").setContent("u".repeat(60_000)));
        // 固定部分 ≈ 3,334+3,334=6,668 < max_input 20,000；历史仍可压 → 必须触发。
        List<ToolDefinitionDTO> tools = toolsWithJsonChars(6_000);

        assertTrue(service.shouldCompact(messages, route, tools, null),
                "固定部分没超硬上限时，历史压缩仍然有效");
    }

    @Test
    void imagePartsCountTowardsEstimate() {
        ModelRouteFullVO route = route(101L, 20_000L);
        List<ChatMessagePartDTO> parts = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            parts.add(new ChatMessagePartDTO()
                    .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                    .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl("data:image/png;base64,xxx")));
        }
        List<ChatMessageDTO> messages = List.of(new ChatMessageDTO()
                .setRole("user")
                .setContent(parts));

        // 15 张图按每张 1,000 token 估；预算 14,000，×1.1 后 16,500 → 必须触发
        assertTrue(service.shouldCompact(messages, route, List.of(), null),
                "图片不能漏算，否则多模态请求会晚于真实溢出才压缩");
    }

    @Test
    void outputTokenCapShrinksBudget() {
        ModelRouteFullVO route = route(101L, 32_000L);
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("u".repeat(21_000)));

        // 无输出额度：预算 0.7×32000=22,400；21,000/1.8×1.1≈12,834 → 不触发
        assertFalse(service.shouldCompact(messages, route, List.of(), null));
        // 输出额度 25,000：预算上限被压到 floor=8,000（25%），12,834 → 触发
        assertTrue(service.shouldCompact(messages, route, List.of(), 25_000),
                "预算必须给实际输出额度让位，而不是固定 0.7");
    }

    @Test
    void cursorStopsAtLastActuallyIncludedRun() {
        RunVO first = run(11L, "a".repeat(10_000));
        RunVO second = run(12L, "b".repeat(10_000));
        // 单条 run 的正文先按 2,000 字符截断（≈1,112 token）；1,500 上限只够第一条
        CompactionService.CompactInput input = service.buildCompactInput(
                1L, List.of(first, second), 1_500);

        assertEquals(11L, input.lastRunId(), "游标只能推进到实际写入摘要的最后一个 run");
        assertEquals(1, input.includedRuns());
        assertFalse(input.truncated());
    }

    @Test
    void oversizedFirstRunIsTruncatedAndIncluded_notSkipped() {
        RunVO huge = run(21L, "x".repeat(100_000));
        RunVO newer = run(22L, "y".repeat(1_000));
        CompactionService.CompactInput input = service.buildCompactInput(
                1L, List.of(huge, newer), 1_000);

        assertEquals(21L, input.lastRunId(),
                "超大 run 必须截断后纳入（游标才能前进），不能跳过它去压缩更新的 run");
        assertEquals(1, input.includedRuns());
        assertTrue(input.truncated());
        assertTrue(input.text().contains("[truncated"), "截断必须显式标记");
    }

    @Test
    void onlyFinishedRunsAreEligibleForCompaction() {
        assertTrue(CompactionService.isCompactionEligible("COMPLETED"));
        assertTrue(CompactionService.isCompactionEligible("FAILED"));
        assertTrue(CompactionService.isCompactionEligible("CANCELLED"));
        assertFalse(CompactionService.isCompactionEligible("RUNNING"));
        assertFalse(CompactionService.isCompactionEligible("WAITING"));
        assertFalse(CompactionService.isCompactionEligible("AWAITING_USER"));
    }

    private static RunVO run(Long id, String userMessage) {
        RunVO run = new RunVO();
        run.setId(id);
        run.setSessionId(1L);
        run.setUserMessage(userMessage);
        return run;
    }

    @Test
    void tierSelectionInterpolatesAndUsesTierOutputCap() {
        props.getRunner().getCompaction().setTiers(List.of(
                tier(64_000L, 50_000L, 20_000L, 8_192L),
                tier(128_000L, 100_000L, 40_000L, 16_000L)));

        CompactionTier at64 = service.resolveTier(64_000L);
        assertEquals(50_000L, at64.getTriggerTokens());
        assertEquals(20_000L, at64.getTargetTokens());
        assertEquals(8_192L, at64.getChatMaxOutputTokens());

        CompactionTier at96 = service.resolveTier(96_000L);
        assertEquals(75_000L, at96.getTriggerTokens());
        assertEquals(30_000L, at96.getTargetTokens());
        assertEquals(16_000L, at96.getChatMaxOutputTokens());
    }

    @Test
    void tierTargetReservesSummaryAndTailInsideAbsoluteTarget() {
        props.getRunner().getCompaction().setTiers(List.of(
                tier(64_000L, 50_000L, 20_000L, 8_192L)));
        CompactionService.CompactionTarget target = service.resolveTierTarget(
                64_000L, 50_000L, 14_000L, 1_000L, 7_680L,
                props.getRunner().getCompaction(), service.resolveTier(64_000L));

        assertTrue(target.feasible());
        assertEquals(20_000L, target.target());
        assertEquals(18_000L, target.fixedTokens(), "14k prefix + 1k tools + 3k summary");
        assertEquals(2_000L, target.tailFloor());
    }

    @Test
    void chatOutputCapUsesTierAndRespectsLowerInstanceCap() {
        props.getRunner().getCompaction().setTiers(List.of(
                tier(64_000L, 50_000L, 20_000L, 8_192L)));

        assertEquals(8_192, service.resolveChatMaxOutputTokens(64_000L, null));
        assertEquals(8_192, service.resolveChatMaxOutputTokens(64_000L, 16_000));
        assertEquals(2_000, service.resolveChatMaxOutputTokens(64_000L, 2_000));
    }

    @Test
    void calibrationCanPreventFalsePositiveCompaction() {
        props.getRunner().getCompaction().setTiers(List.of(
                tier(64_000L, 50_000L, 20_000L, 8_192L)));
        ModelRouteFullVO route = route(201L, 64_000L);
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("x".repeat(100_000)));

        assertTrue(service.shouldCompact(messages, route, List.of(), null),
                "无校准：100k 字符估算约 55.5k，超过 50k 触发线");

        service.recordCalibration(201L, 10_000, 8_000); // ratio 0.8
        assertFalse(service.shouldCompact(messages, route, List.of(), null),
                "校准后估算约 46.7k，不应误触发");
    }

    @Test
    void calibrationIgnoresAbnormalRatio() {
        props.getRunner().getCompaction().setTiers(List.of(
                tier(64_000L, 50_000L, 20_000L, 8_192L)));
        ModelRouteFullVO route = route(202L, 64_000L);
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("x".repeat(100_000)));

        service.recordCalibration(202L, 10_000, 30_000); // ratio 3.0，异常
        assertTrue(service.shouldCompact(messages, route, List.of(), null),
                "异常比例必须丢弃，不能把估算放大到离谱");
    }

    private static CompactionTier tier(long maxInput, long trigger, long target, long output) {
        CompactionTier tier = new CompactionTier();
        tier.setMaxInputTokens(maxInput);
        tier.setTriggerTokens(trigger);
        tier.setTargetTokens(target);
        tier.setChatMaxOutputTokens(output);
        return tier;
    }

    @Test
    void missingMaxInputFallsBackWithoutThrowing() {
        ModelRouteFullVO route = route(999L, null);
        // 默认 1M → 预算 700k，这几条消息远低于阈值；只验证不抛异常
        assertFalse(service.shouldCompact(failingMessages(), route, toolsWithJsonChars(100), null));
    }
}
