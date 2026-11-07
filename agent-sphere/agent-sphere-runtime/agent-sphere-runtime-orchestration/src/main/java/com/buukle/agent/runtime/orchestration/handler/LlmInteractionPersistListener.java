package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.complete.LlmStreamStats;
import com.buukle.agent.model.dtvo.dto.TokenUsage;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionEvent;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmInteractionPersistListener {

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;
    private final RunSpi runSpi;
    private final TimelineRecorder timelineRecorder;
    private final CompactionService compactionService;

    @EventListener
    public void handle(LlmInteractionEvent event) {
        LlmInteractionMeta meta = event.getMeta();
        if (meta == null || meta.getInteractionType() == null) return;

        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setRunId(meta.getRunId());
        vo.setSessionId(meta.getSessionId());
        vo.setSuperAgentId(meta.getSuperAgentId());
        vo.setSessionType(meta.getSessionType());
        vo.setThreadId(meta.getThreadId());
        vo.setInteractionType(meta.getInteractionType().name());
        vo.setModelName(event.getModelName());
        vo.setRequestBody(event.getRequestBody());
        vo.setResponseBody(event.getResponseBody());
        vo.setReasoning(event.getReasoning());
        vo.setReplyContent(event.getReply());
        vo.setSubAgentRunId(event.getSubAgentRunId());
        vo.setDurationMs((int) event.getDurationMs());
        vo.setSuccess(event.isSuccess());
        vo.setErrorMessage(event.getErrorMessage());
        vo.setHttpStatus(meta.getHttpStatus());
        vo.setFinishReason(event.getFinishReason());
        LlmStreamStats streamStats = event.getStreamStats();
        if (streamStats != null) {
            vo.setStreamAttempts(streamStats.attempts());
            vo.setStreamRetryCount(streamStats.retryCount());
            if (streamStats.lastErrorType() != null || streamStats.lastErrorMessage() != null) {
                String error = (streamStats.lastErrorType() == null ? "stream_error"
                        : streamStats.lastErrorType())
                        + (streamStats.lastErrorMessage() == null ? ""
                        : ": " + streamStats.lastErrorMessage());
                vo.setStreamError(error.length() <= 512 ? error : error.substring(0, 512));
            }
        }
        TokenUsage usage = event.getUsage();
        if (usage != null) {
            vo.setUsage(JsonUtils.toJson(usage));
            vo.setPromptTokens(usage.getPromptTokens());
            vo.setCompletionTokens(usage.getCompletionTokens());
            vo.setTotalTokens(usage.getTotalTokens());
            vo.setCacheHitTokens(usage.getCacheHitTokens());
            vo.setCacheMissTokens(usage.getCacheMissTokens());
            if (event.isSuccess() && meta.getInteractionType() == LlmInteractionType.CHAT_REPLY
                    && meta.getSubAgentRunId() == null
                    && meta.getModelRouteId() != null
                    && meta.getEstimatedPromptTokens() != null
                    && usage.getPromptTokens() != null) {
                compactionService.recordCalibration(meta.getModelRouteId(),
                        meta.getEstimatedPromptTokens(), usage.getPromptTokens());
            }
        }

        try {
            RunVO run = runSpi.getRun(meta.getRunId());
            if (run != null && run.getCreatedBy() != null) {
                vo.setCreatedBy(run.getCreatedBy());
            }
        } catch (Exception ignored) {
        }

        Long recordId;
        if (meta.getRecordId() != null) {
            // Main Agent turn: the record was pre-created as RUNNING before the call; fill in the result here
            interactionRecordSpi.complete(meta.getRecordId(), vo);
            recordId = meta.getRecordId();
        } else {
            recordId = interactionRecordSpi.createRecord(vo);
        }
        afterPersist(meta, recordId, event.isSuccess());
        log.debug("LLM interaction recorded: runId={}, type={}, duration={}ms, success={}, "
                        + "promptTokens={}, completionTokens={}, totalTokens={}",
                meta.getRunId(), meta.getInteractionType().name(), event.getDurationMs(), event.isSuccess(),
                vo.getPromptTokens(), vo.getCompletionTokens(), vo.getTotalTokens());
    }

    /**
     * Main Agent Q&A turn: once the interaction is persisted, close the matching Timeline assistant row and attach the reference (LLM call end = boundary of that turn's content row).
     * Filter: sub-agent (subAgentRunId) and non-visible turns such as title/compaction/contract-repair (not CHAT_REPLY) produce no main assistant row.
     */
    private void afterPersist(LlmInteractionMeta meta, Long recordId, boolean success) {
        if (recordId == null || meta.getRunId() == null || meta.getSubAgentRunId() != null
                || meta.getInteractionType() != LlmInteractionType.CHAT_REPLY) {
            return;
        }
        String state = success ? TimelineState.COMPLETED.getCode() : TimelineState.FAILED.getCode();
        timelineRecorder.closeAssistantTurn(meta.getSessionId(), meta.getRunId(), state, recordId);
    }
}
