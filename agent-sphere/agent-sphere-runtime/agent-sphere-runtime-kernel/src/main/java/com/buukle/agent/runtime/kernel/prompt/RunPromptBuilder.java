package com.buukle.agent.runtime.kernel.prompt;

import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
import com.buukle.agent.util.json.ToolCallReason;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RunPromptBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** Whether to inject `_reason` into tool schemas; when disabled, historical data is still displayed. */
    @Value("${buukle.agent.tool.call-reason-enabled:true}")
    private boolean callReasonEnabled = true;

    public String buildSystemPrompt(KernelContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null && ctx.getAgentInstance() != null) {
            sb.append(ctx.getAgentInstance().getSystemPrompt() != null
                    ? ctx.getAgentInstance().getSystemPrompt() : "");
        }
        List<RuntimeTool> tools = ctx != null ? ctx.getTools() : null;
        // List tool names only: the full description/schema already appears once each in the request's `tools`,
        // and concatenating them into the system prompt again would resend every tool's description (especially browser/skill tools).
        if (tools != null && !tools.isEmpty()) {
            List<RuntimeTool> sorted = new ArrayList<>(tools);
            sorted.sort(java.util.Comparator.comparing(RuntimeTool::getLlmToolName, String::compareTo));
            sb.append(RunnerConstants.PROMPT_TOOLS_HEADER);
            for (RuntimeTool t : sorted) {
                sb.append("- ").append(t.getLlmToolName()).append("\n");
            }
        }
        sb.append(RunnerConstants.PROMPT_TOOLS_FOOTER);
        if (callReasonEnabled && tools != null && !tools.isEmpty()) {
            sb.append(RunnerConstants.PROMPT_CALL_REASON_RULES);
        }
        boolean hasTools = tools != null && !tools.isEmpty();
        boolean canAskUser = hasTools && tools.stream()
                .anyMatch(t -> ChatClarification.CLARIFICATION_TOOL_NAME.equals(t.getLlmToolName()));
        if (canAskUser) {
            sb.append(RunnerConstants.PROMPT_CLARIFICATION_RULES);
        } else if (hasTools) {
            sb.append(RunnerConstants.PROMPT_NO_CLARIFICATION_RULE);
        }
        if (tools != null && tools.stream()
                .anyMatch(t -> SubAgentConstants.DELEGATE_TOOL.equals(t.getLlmToolName()))) {
            sb.append(RunnerConstants.PROMPT_SUBAGENT_WORKFLOW);
        }
        return sb.toString();
    }

    /**
     * Small todo-list tail segment (system message), appended by the assembly side to the very end of messages (no later than FINAL_TURN),
     * keeping messages[0] and the history prefix byte-stable for cache hits. Each todolist change only replaces the content of this slot in place.
     *
     * @return the todo suffix text; null when todolistText is empty (the caller should remove the existing slot)
     */
    public String buildTodolistSuffix(String todolistText) {
        if (todolistText == null || todolistText.isBlank()) {
            return null;
        }
        return RunnerConstants.PROMPT_TODOLIST_HEADER
                + todolistText
                + RunnerConstants.PROMPT_TODOLIST_INSTRUCTION;
    }

    /** Decides whether a message is the todo slot (starts with the header marker; used for in-place location/removal). */
    public boolean isTodolistSlot(String content) {
        return content != null && content.startsWith(RunnerConstants.PROMPT_TODOLIST_HEADER);
    }

    /**
     * Per-loop budget notice: tells the model "which loop this is / how many loops remain (including this one)".
     *
     * <p>Both {@code current} and {@code remaining} are 1-based (remaining = 1 on the last loop),
     * so the text can never say something self-contradictory like "0 loops left".
     * When remaining loops approach the limit an urgency reminder is appended — models are naturally indifferent to "100 loops left",
     * and without the nudge they never finalize and end up hard-truncated by the loop cap into a half-finished result.
     *
     * @param current   current loop number (1-based)
     * @param remaining remaining loops (1-based, including this one)
     * @param total     effective loop limit (the real value after instance/task/system config overrides)
     */
    public String buildLoopBudget(int current, int remaining, int total) {
        String text = RunnerConstants.LOOP_BUDGET_PREFIX
                + "Loop " + current + "/" + total + ". "
                + remaining + " turn(s) remaining (including this one).";
        if (remaining <= RunnerConstants.LOOP_BUDGET_LOW_THRESHOLD) {
            text += " You are close to the hard loop limit — converge now: finish only"
                    + " the work that is actually required and stop doing anything optional.";
        }
        return text;
    }

    /** Decides whether a message is the loop budget notice (starts with the marker; used to locate and clear the previous loop's copy across turns). */
    public boolean isLoopBudget(String content) {
        return content != null && content.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX);
    }

    public List<ToolDefinitionDTO> buildToolDefinitions(List<RuntimeTool> tools) {
        if (tools == null) tools = List.of();
        // Stable ordering: the tools array participates in the request prefix cache, so sorting by name avoids cache misses caused by capability iteration order changes
        List<RuntimeTool> sorted = new ArrayList<>(tools);
        sorted.sort(java.util.Comparator.comparing(RuntimeTool::getLlmToolName, String::compareTo));
        List<ToolDefinitionDTO> defs = new ArrayList<>();
        for (RuntimeTool tool : sorted) {
            try {
                Map<String, Object> params = JSON.readValue(tool.getParametersSchemaJson(), MAP_TYPE);
                if (callReasonEnabled && !FinishConstants.TOOL_NAME.equals(tool.getLlmToolName())) {
                    injectCallReason(params);
                }
                defs.add(ToolDefinitionDTO.builder()
                        .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                        .function(FunctionDefinitionDTO.builder()
                                .name(tool.getLlmToolName())
                                .description(tool.getDescription())
                                .parameters(params)
                                .build())
                        .build());
            } catch (Exception e) {
                log.warn("Failed to parse tool schema for {}", tool.getLlmToolName());
            }
        }
        return defs;
    }

    /** Injects an optional `_reason` into tool schemas without overwriting an existing field of the same name. */
    @SuppressWarnings("unchecked")
    private static void injectCallReason(Map<String, Object> params) {
        if (params == null) {
            return;
        }
        Object rawProperties = params.get("properties");
        Map<String, Object> properties;
        if (rawProperties instanceof Map<?, ?>) {
            properties = (Map<String, Object>) rawProperties;
        } else {
            properties = new LinkedHashMap<>();
            params.put("properties", properties);
        }
        if (properties.containsKey(ToolCallReason.REASON_KEY)) {
            return;
        }
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", ToolCallReason.SCHEMA_DESCRIPTION);
        reason.put("maxLength", ToolCallReason.MAX_REASON_CHARS);
        properties.put(ToolCallReason.REASON_KEY, reason);
    }
}
