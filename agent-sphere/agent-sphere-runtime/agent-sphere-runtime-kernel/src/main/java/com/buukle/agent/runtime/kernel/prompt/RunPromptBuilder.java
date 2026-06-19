package com.buukle.agent.runtime.kernel.prompt;

import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RunPromptBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public String buildSystemPrompt(KernelContext ctx) {
        return buildSystemPrompt(ctx, null);
    }

    public String buildSystemPrompt(KernelContext ctx, String todolistText) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null && ctx.getAgentInstance() != null) {
            sb.append(ctx.getAgentInstance().getSystemPrompt() != null
                ? ctx.getAgentInstance().getSystemPrompt() : "");
        }
        sb.append(RunnerConstants.PROMPT_CURRENT_TIME)
            .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        List<RuntimeTool> tools = ctx != null ? ctx.getTools() : null;
        if (tools != null && !tools.isEmpty()) {
            sb.append(RunnerConstants.PROMPT_TOOLS_HEADER);
            for (RuntimeTool t : tools) {
                sb.append("- ").append(t.getLlmToolName());
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    sb.append(": ").append(t.getDescription());
                }
                sb.append("\n");
            }
        }
        sb.append(RunnerConstants.PROMPT_TOOLS_FOOTER);
        if (todolistText != null && !todolistText.isBlank()) {
            sb.append("\n\n## 当前待办列表\n")
              .append(todolistText)
              .append("\n\n**请根据任务状态变更及时更新此列表，不要等待用户提醒。**");
        }
        return sb.toString();
    }

    public List<ToolDefinitionDTO> buildToolDefinitions(List<RuntimeTool> tools) {
        if (tools == null) tools = List.of();
        List<ToolDefinitionDTO> defs = new ArrayList<>();
        for (RuntimeTool tool : tools) {
            try {
                Map<String, Object> params = JSON.readValue(tool.getParametersSchemaJson(), MAP_TYPE);
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
}
