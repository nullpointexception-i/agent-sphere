package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * After finish finalization, backfill the run's final answer into that run's latest interaction record with an empty {@code reply_content}.
 *
 * <p>The finish answer lives in the tool_calls arguments, so the LLM turn itself has empty content/reply; the timeline ASSISTANT row
 * (AgentTimelineServiceImpl) renders from interaction.reply_content, so it stays blank without this backfill.
 * Only the latest empty record is updated; normal turns (non-empty reply) are never overwritten.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishReplyPersistListener {

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;

    @EventListener
    public void onRunCompleted(RuntimeEventVO event) {
        if (!(event.getEventType() instanceof RunStatus status) || status != RunStatus.COMPLETED) {
            return;
        }
        RuntimeEventDataVO data = event.getData();
        if (data == null || data.getRunId() == null) {
            return;
        }
        String reply = data.getAssistantReply();
        if (reply == null || reply.isBlank()) {
            return;
        }
        try {
            if (interactionRecordSpi.updateLatestBlankReply(data.getRunId(), reply)) {
                log.debug("Finish reply backfilled: runId={}", data.getRunId());
            }
        } catch (Exception e) {
            log.warn("Failed to backfill finish reply for run {}: {}", data.getRunId(), e.getMessage());
        }
    }
}
