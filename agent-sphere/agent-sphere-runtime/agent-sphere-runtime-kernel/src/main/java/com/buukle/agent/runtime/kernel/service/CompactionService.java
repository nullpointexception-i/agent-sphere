package com.buukle.agent.runtime.kernel.service;

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
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.*;
import com.buukle.agent.runtime.kernel.prompt.CompactionPromptConstant;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompactionService {

    public static final String COMPACTED_SENTINEL = "__COMPACTED__";

    private static final double INPUT_RATIO = 0.5;
    private static final double OUTPUT_RATIO = 2.0 / 3;

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

    public boolean shouldCompact(List<ChatMessageDTO> messages, ModelRouteFullVO route) {
        if (route == null) return false;
        long maxInputTokens = route.getMaxInputTokens() != null ? route.getMaxInputTokens()
            : RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
        long budget = (long) (maxInputTokens * agentRuntimeProperties.getRunner().getCompaction().getBudgetRatio());
        return estimateMessagesTokens(messages) > budget;
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
            (long) (inputText.length() / RunnerConstants.TOKEN_ESTIMATE_DIVISOR * 2 / 3));
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
            if (msg.getContent() != null) len += msg.getContent().length();
            if (msg.getToolCalls() != null) {
                for (ToolCallDTO tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                        len += tc.getFunction().getArguments().length();
                    }
                }
            }
        }
      int i = len / RunnerConstants.TOKEN_ESTIMATE_DIVISOR;
      log.debug("length:{}",i);
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
                        default -> {}
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
