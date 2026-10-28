package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.CompactionStatus;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.CompactionPromptConstant;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompactionService {

    public static final String COMPACTED_SENTINEL = "__COMPACTED__";

    private static final double INPUT_RATIO = 0.5;
    private static final double OUTPUT_RATIO = 2.0 / 3;
    /** 估算安全系数：中文/JSON 的字符密度波动大，留 10% 余量避免「估算没到阈值、实际已超上下文」。 */
    private static final double ESTIMATE_SAFETY_RATIO = 1.1;

    private final AgentRuntimeProperties agentRuntimeProperties;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final KernelLlmService kernelLlmService;
    private final ApiKeySpi apiKeySpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final RouteListBuilder routeListBuilder;
    private final FallbackRouteExecutor fallbackRouteExecutor;
    private final LlmRequestConfigurer llmRequestConfigurer;
    /** 只对每个缺 max_input_tokens 的路由告警一次，避免每轮刷日志。 */
    private final Set<Long> warnedMissingMaxInput = ConcurrentHashMap.newKeySet();
    /** 只对每个「压缩不可能把上下文压进预算」的路由告警一次，避免每轮刷日志。 */
    private final Set<Long> warnedInfeasibleCompaction = ConcurrentHashMap.newKeySet();

    /**
     * 是否需要压缩。
     *
     * <p><b>必须把工具 schema 计入估算</b>：请求体 = messages + tools + 输出；10 个工具的 JSON 约 1 万
     * token，只算 messages 会让「估算没到阈值、实际已超上下文」——线上 32k 路由（工具 11.7k token）
     * 就是这样在 32,852 实际 token 时报 400 的。
     *
     * <p>再加 10% 安全系数（字符/1.8 ×1.1）。route 的 {@code max_output_tokens}
     * 不参与请求（请求的 max_tokens 由 sampling config 控制），这里也不从总上下文里扣它；
     * 0.7 的 budgetRatio 本身已为输出预留 30%。
     *
     * <p><b>固定开销本身就塞不进模型时才跳过压缩</b>：主 system prompt 与工具 schema 是每轮都带的，
     * 压缩只能压历史。若 {@code leading system + tools} 已超过 {@code max_input_tokens}（硬上限，不再乘
     * 安全系数），压缩就是不可能的，只会每轮白烧 3 次压缩调用（线上 route 4 = 20k 的测试路由）。
     * 此时返回 false，让请求带着超上下文直接发：模型接受就继续，拒绝则走 400 终态失败。
     * 注意门槛必须是硬上限而不是 0.7×budget —— 后者会让「工具占大头但真实仍可容纳」的路由
     * （如 32k 路由）被永久关闭压缩，历史继续涨到真溢出。
     */
    public boolean shouldCompact(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                 List<ToolDefinitionDTO> tools) {
        if (route == null) return false;
        Long routeId = route.getId();
        long maxInputTokens;
        if (route.getMaxInputTokens() != null) {
            maxInputTokens = route.getMaxInputTokens();
        } else {
            maxInputTokens = RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
            if (routeId == null || warnedMissingMaxInput.add(routeId)) {
                log.warn("Route {} has no max_input_tokens; compaction falls back to {} tokens and may never trigger",
                        routeId, maxInputTokens);
            }
        }
        long budget = (long) (maxInputTokens * agentRuntimeProperties.getRunner().getCompaction().getBudgetRatio());
        long toolTokens = estimateToolTokens(tools);
        long fixedTokens = estimateFixedTokens(messages, toolTokens);
        if (fixedTokens >= maxInputTokens) {
            if (routeId == null || warnedInfeasibleCompaction.add(routeId)) {
                log.warn("Route {} cannot compact: fixed(leading system + tools)={} >= maxInput={}; "
                        + "sending oversized context instead", routeId, fixedTokens, maxInputTokens);
            }
            return false;
        }
        long messageTokens = estimateMessagesTokens(messages);
        long estimated = (long) Math.ceil((messageTokens + toolTokens) * ESTIMATE_SAFETY_RATIO);
        log.debug("Compaction check: routeId={}, messageTokens={}, toolTokens={}, fixed={}, estimated={}, budget={}, maxInput={}",
                routeId, messageTokens, toolTokens, fixedTokens, estimated, budget, maxInputTokens);
        return estimated > budget;
    }

    /**
     * 压缩无法削减的部分：开头的连续 system 消息（主提示词 + 历史层注入的摘要/取消提示）+ 工具 schema。
     * 只有 leading system 算固定开销 —— 尾部 system（时间/待办/预算）每轮重建，同样压不掉，但体量很小，
     * 这里不额外计入以免误伤边界场景。
     */
    private long estimateFixedTokens(List<ChatMessageDTO> messages, long toolTokens) {
        long systemTokens = 0;
        if (messages != null) {
            List<ChatMessageDTO> leadingSystem = new ArrayList<>();
            for (ChatMessageDTO msg : messages) {
                if (!LlmApiConstant.ROLE_SYSTEM.equals(msg.getRole())) {
                    break;
                }
                leadingSystem.add(msg);
            }
            systemTokens = estimateMessagesTokens(leadingSystem);
        }
        return systemTokens + toolTokens;
    }

    /** 字符数 → token（向上取整；除数见 {@link RunnerConstants#TOKEN_ESTIMATE_DIVISOR}）。 */
    private static long charsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
    }

    /** 工具定义也是 prompt 的一部分：把 tools JSON 的字符数按同一 divisor 折算成 token。 */
    private long estimateToolTokens(List<ToolDefinitionDTO> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        String json = JsonUtils.toJson(tools);
        return json == null ? 0 : charsToTokens(json.length());
    }

    public void compact(Long sessionId, Long runId, KernelContext ctx) {
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session == null) return;
            List<ModelRouteFullVO> routes = routeListBuilder.fromInstance(session.getAgentInstanceId());
            fallbackRouteExecutor.execute(routes, (i, route) -> {
                doCompact(sessionId, session, route, runId);
                return null;
            });
        } catch (Exception e) {
            log.warn("Compaction failed for session {}", sessionId, e);
        }
    }

    private void doCompact(Long sessionId, SessionVO session, ModelRouteFullVO route, Long runId) {
        AgentCompactRecordVO latest = compactRecordSpi.getLatestCompleted(sessionId);
        Long cursor = latest != null && latest.getCompactedUptoRunId() != null
                ? latest.getCompactedUptoRunId() : 0L;

        List<RunVO> allAfterCursor = runSpi.listRunsBySessionAfterId(sessionId, cursor);
        if (allAfterCursor.isEmpty()) return;

        long maxInputTokens = route.getMaxInputTokens() != null ? route.getMaxInputTokens()
                : RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
        long maxOutputTokens = route.getMaxOutputTokens() != null ? route.getMaxOutputTokens()
                : RunnerConstants.DEFAULT_MAX_OUTPUT_TOKENS;

        long budget = (long) (maxInputTokens * agentRuntimeProperties.getRunner().getCompaction().getBudgetRatio());
        long accumulated = 0;
        int compactCount = allAfterCursor.size();
        for (int i = allAfterCursor.size() - 1; i >= 0; i--) {
            long est = estimateRunTokens(sessionId, allAfterCursor.get(i));
            if (accumulated + est > budget) {
                compactCount = i + 1;
                break;
            }
            accumulated += est;
        }
        if (compactCount <= 0) return;

        List<RunVO> toCompact = allAfterCursor.subList(0, compactCount);
        Long newCursor = toCompact.get(compactCount - 1).getId();
        String detail = "合并 " + toCompact.size() + " 轮历史";
        // publishId 必须每次压缩唯一：同一 session 的多次压缩要各自成行（timeline 按它复用/封口）。
        String publishId = RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId + ":" + UUID.randomUUID();
        // 先建记录：失败也有审计行，timeline 也能立刻挂上 ref（刷新后正文从 summary_after 解析）。
        AgentCompactRecordVO record = compactRecordSpi.createRecord(sessionId);
        publishCompaction(CompactionStatus.RUNNING, sessionId, runId, publishId, record.getId(), detail);

        try {
            long compactionInputLimit = (long) (maxInputTokens * INPUT_RATIO);
            String inputText = buildCompactInput(sessionId, toCompact, compactionInputLimit);

            long summaryMaxLen = Math.min(
                    (long) (maxOutputTokens * OUTPUT_RATIO),
                    charsToTokens(inputText.length()) * 2 / 3);
            String newSummary = callLLM(
                    CompactionPromptConstant.buildPrompt(session.getSummary(), inputText, summaryMaxLen),
                    route, sessionId, runId, publishId);

            if (newSummary == null || newSummary.isBlank()) {
                String reason = "压缩模型未返回内容";
                compactRecordSpi.updateFailed(record.getId(), reason);
                publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
                return;
            }
            sessionSpi.updateSummary(sessionId, newSummary, LocalDateTime.now().toString(), route.getId());
            compactRecordSpi.updateCompleted(record.getId(), session.getSummary(), newSummary, null, newCursor);
            publishCompaction(CompactionStatus.COMPLETED, sessionId, runId, publishId, record.getId(), detail);
        } catch (Exception e) {
            log.warn("Compaction failed for session {}", sessionId, e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            compactRecordSpi.updateFailed(record.getId(), reason);
            publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
        }
    }

    private void publishCompaction(CompactionStatus status, Long sessionId, Long runId,
                                   String publishId, Long compactRecordId, String detail) {
        eventPublisher.publishEvent(new RuntimeEventVO(status,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setPublishId(publishId)
                        .setCompactRecordId(compactRecordId)
                        .setAssistantReply(detail)));
    }

    private String buildCompactInput(Long sessionId, List<RunVO> runs, long tokenLimit) {
        StringBuilder sb = new StringBuilder();
        int estimatedTokens = 0;
        int limit = (int) Math.min(tokenLimit, Integer.MAX_VALUE);
        for (int i = runs.size() - 1; i >= 0; i--) {
            RunVO r = runs.get(i);
            StringBuilder runSb = new StringBuilder();

            String userText = r.getUserMessage() != null
                    ? RunnerConstants.COMPACTION_USER_PREFIX + r.getUserMessage() + RunnerConstants.COMPACTION_NEWLINE : "";
            runSb.append(userText);

            List<AgentToolCallRecordVO> calls = new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, r.getId()));
            Collections.reverse(calls);
            for (AgentToolCallRecordVO c : calls) {
                boolean succeeded = ToolCallRecordStatus.SUCCEEDED.name().equals(c.getStatus());
                String result = succeeded
                        ? ToolResultCompressor.compress(c.getArtifact(), RunnerConstants.COMPACTION_TOOL_RESULT_MAX_CHARS)
                        : "error: " + (c.getErrorMessage() != null ? c.getErrorMessage() : "unknown");
                String artifact = result != null ? result : "";
                runSb.append("Tool[").append(c.getToolName()).append("]: ").append(artifact).append("\n");
            }

            String asstText = r.getAssistantReply() != null
                    ? RunnerConstants.COMPACTION_ASSISTANT_PREFIX + r.getAssistantReply() + RunnerConstants.COMPACTION_NEWLINE : "";
            runSb.append(asstText);

            int added = (int) charsToTokens(runSb.length());
            if (estimatedTokens + added > limit) break;
            sb.insert(0, runSb);
            estimatedTokens += added;
        }
        return sb.toString();
    }

    private long estimateRunTokens(Long sessionId, RunVO r) {
        int len = 0;
        if (r.getUserMessage() != null) len += r.getUserMessage().length();
        if (r.getAssistantReply() != null) len += r.getAssistantReply().length();
        var calls = toolCallRecordSpi.listBySessionId(sessionId, r.getId());
        for (var c : calls) {
            if (c.getArgumentsJson() != null) len += c.getArgumentsJson().length();
            if (c.getArtifact() != null) len += Math.min(c.getArtifact().length(), 500);
        }
        return charsToTokens(len);
    }

    private long estimateMessagesTokens(List<ChatMessageDTO> messages) {
        int len = 0;
        for (ChatMessageDTO msg : messages) {
            if (msg.getContent() instanceof String text) {
                len += text.length();
            } else if (msg.getContent() instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof ChatMessagePartDTO p && p.getText() != null) {
                        len += p.getText().length();
                    }
                }
            }
            if (msg.getToolCalls() != null) {
                for (ToolCallDTO tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                        len += tc.getFunction().getArguments().length();
                    }
                }
            }
        }
        int i = (int) charsToTokens(len);
        log.debug("length:{}", i);
        return i;
    }

    private String callLLM(String messagesText, ModelRouteFullVO route, Long sessionId, Long runId,
                           String publishId) {
        try {
            ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                    .setModel(route.getModelName())
                    .setStream(true)
                    .setMessages(List.of(
                            new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM)
                                    .setContent(CompactionPromptConstant.getSystemPrompt()),
                            new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent(messagesText)));
            llmRequestConfigurer.applyGlobal(request);
            String apiKeyValue = resolveApiKey(route);
            StringBuilder content = new StringBuilder();
            var future = kernelLlmService.stream(
                    route.getCompany(), route.getBaseUrl(), apiKeyValue, route.getModelName(),
                    request,
                    event -> {
                        switch (event) {
                            // 压缩输出实时打到前端对话框（分割线下的正文）：
                            // 用 compaction_output 子类型，避免被当成主 Agent 的 assistant/reasoning 行。
                            case LLMEvent.TextDelta t -> {
                                content.append(t.text());
                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                        new RuntimeEventDataVO()
                                                .setSessionId(sessionId)
                                                .setRunId(runId)
                                                .setResponse(t.text())
                                                .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                                .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_OUTPUT)
                                                .setPublishId(publishId)));
                            }
                            // 摘要模型的思考不展示（避免和压缩正文混在一起）。
                            case LLMEvent.ReasoningDelta r -> {
                            }
                            default -> {
                            }
                        }
                    },
                    new LlmInteractionMeta().setRunId(runId).setSessionId(sessionId)
                            .setInteractionType(LlmInteractionType.COMPACTION));
            future.get(agentRuntimeProperties.getLlm().getStreamTimeout().getSeconds(), TimeUnit.SECONDS);
            return content.toString();
        } catch (Exception e) {
            log.warn("LLM compaction call failed", e);
        }
        return "";
    }

    private String resolveApiKey(ModelRouteFullVO route) {
        if (route.getApiKeyId() == null) return "";
        try {
            return apiKeySpi.getApiKeyValue(route.getApiKeyId());
        } catch (Exception e) {
            log.warn("Failed to resolve API key", e);
            return "";
        }
    }
}
