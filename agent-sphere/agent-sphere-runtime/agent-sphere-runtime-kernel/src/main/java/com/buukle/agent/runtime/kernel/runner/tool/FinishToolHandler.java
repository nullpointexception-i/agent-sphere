package com.buukle.agent.runtime.kernel.runner.tool;

import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.runner.support.ToolJsonSupport;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handles a turn containing the {@code finish} tool.
 *
 * <p>Other tool calls in the same turn are not executed: their PENDING records must be closed
 * with FAILED so the timeline does not stay pending. Runs before loop-cap marking, so
 * "finish on the last loop" is a clean completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishToolHandler {

    private final ApplicationEventPublisher eventPublisher;
    private final RunSpi runSpi;
    private final ToolExecutor toolExecutor;

    /**
     * @return true when the run was completed and the loop must break
     */
    public boolean handle(Long sessionId, Long currentRunId, RunVO currentRun, TurnResult turn,
                          List<RuntimeTool> tools, StringBuilder allContent) {
        TurnToolCall finishCall = turn.toolCalls().stream()
                .filter(tc -> FinishConstants.TOOL_NAME.equals(tc.name()))
                .findFirst().orElse(null);
        if (finishCall == null) {
            return false;
        }
        for (TurnToolCall other : turn.toolCalls()) {
            if (other == finishCall) {
                continue;
            }
            log.warn("Discarding tool call {} from a finish turn (run {})", other.name(), currentRunId);
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .toolName(other.name())
                            .displayNameCn(toolExecutor.resolveDisplayName(other.name(), tools))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(other.name(), tools))
                            .errorMessage("skipped: finish was called in the same turn")
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + other.id()).build()));
        }
        // Close finish's own PENDING record.
        eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .toolName(finishCall.name())
                        .displayNameCn(FinishConstants.DISPLAY_NAME_CN)
                        .displayNameEn(FinishConstants.DISPLAY_NAME)
                        .argumentsJson(finishCall.arguments())
                        .artifact(finishCall.arguments())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + finishCall.id()).build()));
        String declaredSummary = ToolJsonSupport.field(finishCall.arguments(), FinishConstants.JSON_SUMMARY);
        String declaredStatus = ToolJsonSupport.field(finishCall.arguments(), FinishConstants.JSON_STATUS);
        String reply = declaredSummary != null && !declaredSummary.isBlank()
                ? declaredSummary
                : allContent.toString();
        if (reply != null && !reply.isBlank()) {
            currentRun.setAssistantReply(reply);
        }
        currentRun.setFinishStatus(declaredStatus);
        currentRun.setStatus(RunStatus.COMPLETED.name());
        runSpi.updateRun(currentRun);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .assistantReply(currentRun.getAssistantReply())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        log.info("Run {} finished by finish tool: declaredStatus={}", currentRunId, declaredStatus);
        return true;
    }
}
