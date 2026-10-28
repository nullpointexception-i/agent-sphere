package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
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

import java.util.List;
import java.util.Map;

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

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
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
        List<ChatMessageDTO> messages = failingMessages();
        List<ToolDefinitionDTO> tools = toolsWithJsonChars(35_000);

        // 只算 messages：(58564+1295)/3 ≈ 19953，×1.1 ≈ 21949 < 22400 → 旧实现不压缩（正是线上现状）
        assertFalse(service.shouldCompact(messages, route, List.of()),
                "只算 messages 时估算低于预算，复现线上未触发压缩");

        // 计入 10 个工具（这里用 1 个 35k chars 的 schema 模拟 ≈11.7k token）→ 34k+ > 22400
        assertTrue(service.shouldCompact(messages, route, tools),
                "工具 schema 必须计入阈值，否则实际 prompt 超 32k 才报错");
    }

    @Test
    void safetyFactorCatchesBorderlineCase() {
        ModelRouteFullVO route = route(101L, 32_000L);
        // messages 45,000 chars → 15,000 tokens；tools JSON ≈20,000 chars → ≈6,667；合计 ≈21,667
        List<ChatMessageDTO> messages = List.of(
                new ChatMessageDTO().setRole("user").setContent("u".repeat(45_000)));
        List<ToolDefinitionDTO> tools = toolsWithJsonChars(20_000);

        // 21,667 < 22,400（无安全系数会漏），×1.1 ≈23,834 > 22,400 → 必须触发
        assertTrue(service.shouldCompact(messages, route, tools),
                "10% 安全系数应覆盖 chars/3 对中文/JSON 的偏低估算");
    }

    @Test
    void missingMaxInputFallsBackWithoutThrowing() {
        ModelRouteFullVO route = route(999L, null);
        // 默认 1M → 预算 700k，这几条消息远低于阈值；只验证不抛异常
        assertFalse(service.shouldCompact(failingMessages(), route, toolsWithJsonChars(100)));
    }
}
