package com.buukle.agent.runtime.kernel.runner.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.prompt.PromptTailWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * End-of-tool-loop policy: refresh the todo slot and force-finalize when the loop cap is hit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoopCapHandler {

    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final RunSpi runSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final PromptTailWriter promptTailWriter;

    /** @return true when the loop must break (forced finalization on the last loop). */
    public boolean handle(Long sessionId, Long currentRunId, RunVO currentRun, TurnResult turn,
                          List<ChatMessageDTO> messages, StringBuilder allContent,
                          boolean isLastLoop, int maxLoopCount) {
        boolean todowriteCalled = turn.toolCalls().stream().anyMatch(tc ->
                tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId()));
        if (todowriteCalled && !messages.isEmpty()) {
            try {
                AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                        sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                String freshTodolist = todoRecord != null && todoRecord.getArtifact() != null
                        ? todoRecord.getArtifact() : null;
                promptTailWriter.applyTodolistSuffix(messages, freshTodolist);
            } catch (Exception e) {
                log.warn("Failed to refresh todolist for session {}", sessionId, e);
            }
        }
        if (!isLastLoop) {
            return false;
        }
        markLoopCapped(currentRun, maxLoopCount);
        String fallback = allContent.length() > 0
                ? allContent.toString()
                : RunnerConstants.FALLBACK_COMPLETE_MSG;
        currentRun.setAssistantReply(fallback);
        currentRun.setStatus(RunStatus.COMPLETED.name());
        runSpi.updateRun(currentRun);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .assistantReply(fallback)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        return true;
    }

    private void markLoopCapped(RunVO run, int maxLoopCount) {
        if (run == null) {
            return;
        }
        run.setLoopCapped(true);
        log.warn("Run {} hit loop cap {} (loopCapped=true, task guard will fail the task)", run.getId(), maxLoopCount);
    }
}
