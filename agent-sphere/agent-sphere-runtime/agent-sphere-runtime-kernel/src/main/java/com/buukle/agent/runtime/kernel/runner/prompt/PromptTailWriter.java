package com.buukle.agent.runtime.kernel.runner.prompt;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Writes the protected tail system segments of the message list: todo slot, loop budget and
 * silent-turn nudge.
 *
 * <p>Every writer updates in place by marker so the history prefix and the prompt cache stay
 * byte-identical; new tail sections should be added here rather than in the loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptTailWriter {

    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final RunPromptBuilder runPromptBuilder;

    /** Re-inject the todo slot from the DB after compaction reassembly, so todos are not lost to summary / tail selection. */
    public void refreshTodolistSlot(Long sessionId, List<ChatMessageDTO> messages) {
        try {
            AgentToolCallRecordVO todo = toolCallRecordSpi.getLatestBySessionAndToolName(
                    sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
            applyTodolistSuffix(messages, todo != null ? todo.getArtifact() : null);
        } catch (Exception e) {
            log.warn("Failed to refresh todolist after compaction for session {}", sessionId, e);
        }
    }

    /**
     * Maintains the todo slot at the tail in place: replace an existing slot by header, otherwise
     * insert before the notice block or at the end. Empty text removes the slot.
     */
    public void applyTodolistSuffix(List<ChatMessageDTO> messages, String todolistText) {
        if (messages == null) {
            return;
        }
        String suffix = runPromptBuilder.buildTodolistSuffix(todolistText);
        int existingSlot = -1;
        for (int i = 0; i < messages.size(); i++) {
            Object content = messages.get(i).getContent();
            if (content instanceof String s && runPromptBuilder.isTodolistSlot(s)) {
                existingSlot = i;
                break;
            }
        }
        if (suffix == null) {
            if (existingSlot >= 0) {
                messages.remove(existingSlot);
            }
            return;
        }
        if (existingSlot >= 0) {
            messages.get(existingSlot).setContent(suffix);
            return;
        }
        // No existing slot: insert before the tail notice block when possible.
        int insertAt = tailNoticeBlockStart(messages);
        messages.add(insertAt, new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent(suffix));
    }

    /** Rebuilds the loop budget notice and the hard-finalization instruction near the cap. */
    public void applyLoopBudget(List<ChatMessageDTO> messages, int loopCount, int maxLoopCount) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messages.removeIf(m -> {
            Object content = m.getContent();
            if (!(content instanceof String s)) {
                return false;
            }
            return runPromptBuilder.isLoopBudget(s) || RunnerConstants.FINAL_TURN_INSTRUCTION.equals(s);
        });
        int remaining = maxLoopCount - loopCount;
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent(runPromptBuilder.buildLoopBudget(loopCount + 1, remaining, maxLoopCount)));
        if (remaining <= RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmRoles.SYSTEM)
                    .setContent(RunnerConstants.FINAL_TURN_INSTRUCTION));
        }
    }

    /** Silent-turn nudge: replaced every loop by marker instead of accumulating. */
    public void applySilentTurnNudge(List<ChatMessageDTO> messages, int silentTurns) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messages.removeIf(m -> m.getContent() instanceof String t
                && t.startsWith(RunnerConstants.SILENT_TURN_NUDGE_PREFIX));
        if (silentTurns <= 0) {
            return;
        }
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent(RunnerConstants.SILENT_TURN_NUDGE_PREFIX + RunnerConstants.SILENT_TURN_NUDGE_TEXT));
    }

    /** Start index of the tail notice block (loop budget / final-turn finalization); list length when absent. */
    public int tailNoticeBlockStart(List<ChatMessageDTO> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Object content = messages.get(i).getContent();
            if (!(content instanceof String s)) {
                continue;
            }
            if (runPromptBuilder.isLoopBudget(s) || RunnerConstants.FINAL_TURN_INSTRUCTION.equals(s)) {
                return i;
            }
        }
        return messages.size();
    }
}
