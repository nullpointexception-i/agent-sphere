package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * finish 收口后，把 run 的最终答复回填到该 run 最新一条空 {@code reply_content} 的交互记录。
 *
 * <p>finish 的答案在 tool_calls 参数里，LLM 轮本身 content/reply 为空；时间线 ASSISTANT 行
 * （AgentTimelineServiceImpl）按 interaction.reply_content 渲染，不回填就会一直空白。
 * 只更新「最新一条且为空」的记录，正常轮次（reply 非空）不会被覆盖。
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
