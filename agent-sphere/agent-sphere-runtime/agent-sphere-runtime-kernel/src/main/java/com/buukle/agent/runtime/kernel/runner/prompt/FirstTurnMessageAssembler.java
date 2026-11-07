package com.buukle.agent.runtime.kernel.runner.prompt;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.support.SessionInputManager;
import com.buukle.agent.runtime.kernel.service.TitleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles the first in-memory turn: title, system prompt, history, user message and todo slot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirstTurnMessageAssembler {

    private static final int MAX_USER_ATTACHMENTS = 4;

    private final TitleService titleService;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final RunPromptBuilder runPromptBuilder;
    private final HistoryLoader historyLoader;
    private final ChatAttachmentResolver attachmentResolver;
    private final PromptTailWriter promptTailWriter;

    /**
     * @return the (possibly updated) index of the current run's first message
     */
    public int assemble(Long sessionId, Long currentRunId, KernelContext ctx,
                        SessionInputManager.InputMessage input,
                        List<ChatMessageDTO> messages, int currentRunStart) {
        String todolistText = null;
        if (messages.isEmpty()) {
            titleService.generateIfNeeded(ctx, input.text(), sessionId, currentRunId);
            try {
                AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                        sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                if (todoRecord != null && todoRecord.getArtifact() != null) {
                    todolistText = todoRecord.getArtifact();
                }
            } catch (Exception e) {
                log.warn("Failed to load todolist for session {}", sessionId, e);
            }
            String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new ChatMessageDTO().setRole(LlmRoles.SYSTEM).setContent(systemPrompt));
            }
            // HistoryResult.currentRunStart is relative to the history list; add the system-prompt offset.
            int historyBase = messages.size();
            HistoryLoader.HistoryResult history = historyLoader.loadResult(
                    sessionId, currentRunId, input.isClarificationResume());
            if (history != null) {
                messages.addAll(history.messages());
            }
            currentRunStart = history == null || history.currentRunStart() < 0
                    ? messages.size()
                    : historyBase + history.currentRunStart();
        }
        messages.add(UserMessageBuilder.build(input, attachmentResolver, MAX_USER_ATTACHMENTS));
        promptTailWriter.applyTodolistSuffix(messages, todolistText);
        return currentRunStart;
    }
}
