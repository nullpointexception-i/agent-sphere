package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryLoader {


    private final RunSpi runSpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;

    public List<ChatMessageDTO> load(Long sessionId, Long excludeRunId) {
        List<ChatMessageDTO> messages = new ArrayList<>();

        AgentCompactRecordVO latestCompact = compactRecordSpi.getLatestCompleted(sessionId);
        if (latestCompact != null && latestCompact.getSummaryAfter() != null
                && !latestCompact.getSummaryAfter().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent(RunnerConstants.HISTORY_SUMMARY_PREFIX + latestCompact.getSummaryAfter()));
        }

        Long afterRunId = latestCompact != null && latestCompact.getCompactedUptoRunId() != null
                ? latestCompact.getCompactedUptoRunId() : 0L;

        List<RunVO> runs = runSpi.listRunsBySessionAfterId(sessionId, afterRunId);

        // Detect if any previous run was cancelled — inject a context hint
        boolean hasCancelledRun = runs.stream().anyMatch(
                r -> RunStatus.CANCELLED.name().equals(r.getStatus())
                        && (excludeRunId == null || !excludeRunId.equals(r.getId())));
        if (hasCancelledRun) {
            String originalUserMessage = runs.stream()
                    .filter(r -> RunStatus.CANCELLED.name().equals(r.getStatus())
                            && (excludeRunId == null || !excludeRunId.equals(r.getId())))
                    .map(RunVO::getUserMessage)
                    .filter(msg -> msg != null && !msg.isBlank())
                    .findFirst().orElse("");
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent("[The previous run was cancelled. "
                            + (originalUserMessage.isBlank()
                                ? "The user may be continuing their original request."
                                : "The user's original request was: \"" + originalUserMessage + "\". Read history carefully and continue.")
                            + " Do NOT ask 'what do you want to do' — execute the task.]"));
        }

        // Collect all tool call records per run
        Map<Long, List<AgentToolCallRecordVO>> callsByRun = new LinkedHashMap<>();
        for (RunVO run : runs) {
            if (excludeRunId != null && excludeRunId.equals(run.getId())) continue;
            List<AgentToolCallRecordVO> calls = new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, run.getId()));
            Collections.reverse(calls);
            if (!calls.isEmpty()) callsByRun.put(run.getId(), calls);
        }

        // Budget: accumulate from newest to oldest, mark over-budget entries
        Set<Long> overBudget = new HashSet<>();
        int budgetUsed = 0;
        List<Long> runIds = runs.stream().map(RunVO::getId).toList();
        for (int i = runIds.size() - 1; i >= 0; i--) {
            List<AgentToolCallRecordVO> calls = callsByRun.get(runIds.get(i));
            if (calls == null) continue;
            for (int j = calls.size() - 1; j >= 0; j--) {
                String raw = calls.get(j).getCompressedArtifact();
                int chars = raw != null ? raw.length() : 0;
                if (budgetUsed + chars > RunnerConstants.TOOL_RESULT_HISTORY_CHARS_BUDGET) {
                    overBudget.add(calls.get(j).getId());
                } else {
                    budgetUsed += chars;
                }
            }
        }

        // Build messages chronologically
        for (RunVO run : runs) {
            if (excludeRunId != null && excludeRunId.equals(run.getId())) continue;

            if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_USER)
                        .setContent(run.getUserMessage()));
            }

            List<AgentToolCallRecordVO> calls = callsByRun.get(run.getId());
            if (calls != null) {
                for (AgentToolCallRecordVO c : calls) {
                    String callId = c.getCallId() != null ? c.getCallId() : RunnerConstants.FALLBACK_CALL_ID_PREFIX + c.getStepId();

                    messages.add(new ChatMessageDTO()
                            .setRole(LlmApiConstant.ROLE_ASSISTANT)
                            .setToolCalls(List.of(ToolCallDTO.builder()
                                    .id(callId)
                                    .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                                    .function(FunctionDefinitionDTO.builder()
                                            .name(c.getToolName())
                                            .arguments(c.getArgumentsJson())
                                            .build())
                                    .build())));

                    String result;
                    if (overBudget.contains(c.getId())) {
                        result = RunnerConstants.TOOL_RESULT_BUDGET_OMITTED;
                    } else if (ToolCallRecordStatus.SUCCEEDED.name().equals(c.getStatus())) {
                        result = c.getCompressedArtifact() != null ? c.getCompressedArtifact() : "";
                    } else {
                        result = c.getErrorMessage() != null
                                ? "{\"error\":\"" + c.getErrorMessage() + "\"}"
                                : "{\"error\":\"Tool execution failed\"}";
                    }
                    messages.add(new ChatMessageDTO()
                            .setRole(LlmApiConstant.ROLE_TOOL)
                            .setToolCallId(callId)
                            .setContent(result));
                }
            }

            if (run.getAssistantReply() != null && !run.getAssistantReply().isBlank()) {
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_ASSISTANT)
                        .setContent(run.getAssistantReply()));
            }
        }

        return messages;
    }
}
