package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionEvent;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
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

    @EventListener
    public void handle(LlmInteractionEvent event) {
        LlmInteractionMeta meta = event.getMeta();
        if (meta == null || meta.getInteractionType() == null) return;

        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setRunId(meta.getRunId());
        vo.setSessionId(meta.getSessionId());
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

        try {
            RunVO run = runSpi.getRun(meta.getRunId());
            if (run != null && run.getCreatedBy() != null) {
                vo.setCreatedBy(run.getCreatedBy());
            }
        } catch (Exception ignored) {
        }

        Long recordId = interactionRecordSpi.createRecord(vo);
        afterPersist(meta, recordId, event.isSuccess());
        log.debug("LLM interaction recorded: runId={}, type={}, duration={}ms, success={}",
                meta.getRunId(), meta.getInteractionType().name(), event.getDurationMs(), event.isSuccess());
    }

    /**
     * 主 Agent 问答轮次：interaction 落库即封口对应 Timeline 助手行并补引用（LLM 调用结束 = 该轮正文行边界）。
     * 过滤：子 Agent（subAgentRunId）与标题/压合/契约修复等非可见轮次（非 CHAT_REPLY）不产生主助手行。
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
