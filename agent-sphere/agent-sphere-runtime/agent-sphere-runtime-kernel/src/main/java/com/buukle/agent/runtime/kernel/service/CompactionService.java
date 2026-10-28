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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompactionService {

    public static final String COMPACTED_SENTINEL = "__COMPACTED__";

    private static final double INPUT_RATIO = 0.5;
    private static final double OUTPUT_RATIO = 2.0 / 3;
    /** 估算安全系数：chars/3 对中文/JSON 仍偏低，留 10% 余量避免「估算没到阈值、实际已超上下文」。 */
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

    /**
     * 是否需要压缩。
     *
     * <p><b>必须把工具 schema 计入估算</b>：请求体 = messages + tools + 输出；10 个工具的 JSON 约 1 万
     * token，只算 messages 会让「估算没到阈值、实际已超上下文」——线上 32k 路由（工具 11.7k token）
     * 就是这样在 32,852 实际 token 时报 400 的。
     *
     * <p>再加 10% 安全系数：{@code chars/3} 对中文/JSON 偏低。route 的 {@code max_output_tokens}
     * 不参与请求（请求的 max_tokens 由 sampling config 控制），这里也不从总上下文里扣它；
     * 0.7 的 budgetRatio 本身已为输出预留 30%。
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
        long messageTokens = estimateMessagesTokens(messages);
        long toolTokens = estimateToolTokens(tools);
        long estimated = (long) Math.ceil((messageTokens + toolTokens) * ESTIMATE_SAFETY_RATIO);
        log.debug("Compaction check: routeId={}, messageTokens={}, toolTokens={}, estimated={}, budget={}, maxInput={}",
                routeId, messageTokens, toolTokens, estimated, budget, maxInputTokens);
        return estimated > budget;
    }

    /** 工具定义也是 prompt 的一部分：把 tools JSON 的字符数按同一 divisor 折算成 token。 */
    private long estimateToolTokens(List<ToolDefinitionDTO> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        String json = JsonUtils.toJson(tools);
        return json == null ? 0 : json.length() / RunnerConstants.TOKEN_ESTIMATE_DIVISOR;
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

        eventPublisher.publishEvent(new RuntimeEventVO(CompactionStatus.RUNNING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId)));

        List<RunVO> toCompact = allAfterCursor.subList(0, compactCount);
        Long newCursor = toCompact.get(compactCount - 1).getId();

        long compactionInputLimit = (long) (maxInputTokens * INPUT_RATIO);
        String inputText = buildCompactInput(sessionId, toCompact, compactionInputLimit);

        long summaryMaxLen = Math.min(
                (long) (maxOutputTokens * OUTPUT_RATIO),
                inputText.length() / RunnerConstants.TOKEN_ESTIMATE_DIVISOR * 2 / 3);
        String newSummary = callLLM(
                CompactionPromptConstant.buildPrompt(session.getSummary(), inputText, summaryMaxLen),
                route, sessionId, runId);

        if (newSummary != null && !newSummary.isBlank()) {
            sessionSpi.updateSummary(sessionId, newSummary, LocalDateTime.now().toString(), route.getId());
            AgentCompactRecordVO record = compactRecordSpi.createRecord(sessionId);
            compactRecordSpi.updateCompleted(record.getId(), session.getSummary(), newSummary, null, newCursor);
        }

        eventPublisher.publishEvent(new RuntimeEventVO(CompactionStatus.COMPLETED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId)));
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

            int added = runSb.length() / RunnerConstants.TOKEN_ESTIMATE_DIVISOR;
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
        return len / RunnerConstants.TOKEN_ESTIMATE_DIVISOR;
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
        int i = len / RunnerConstants.TOKEN_ESTIMATE_DIVISOR;
        log.debug("length:{}", i);
        return i;
    }

    private String callLLM(String messagesText, ModelRouteFullVO route, Long sessionId, Long runId) {
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
                            case LLMEvent.TextDelta t -> {
                                content.append(t.text());
                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                        new RuntimeEventDataVO()
                                                .setSessionId(sessionId)
                                                .setRunId(runId)
                                                .setResponse(t.text())
                                                .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                                .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                                .setPublishId(java.util.UUID.randomUUID().toString())));
                            }
                            case LLMEvent.ReasoningDelta r ->
                                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(sessionId)
                                                    .setRunId(runId)
                                                    .setResponse(r.text())
                                                    .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                                    .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                                    .setPublishId(java.util.UUID.randomUUID().toString())));
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
