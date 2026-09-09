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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public String buildSystemPrompt(KernelContext ctx) {
        return buildSystemPrompt(ctx, null);
    }

    public String buildSystemPrompt(KernelContext ctx, String todolistText) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null && ctx.getAgentInstance() != null) {
            sb.append(ctx.getAgentInstance().getSystemPrompt() != null
                    ? ctx.getAgentInstance().getSystemPrompt() : "");
        }
        List<RuntimeTool> tools = ctx != null ? ctx.getTools() : null;
        if (tools != null && !tools.isEmpty()) {
            List<RuntimeTool> sorted = new ArrayList<>(tools);
            sorted.sort(java.util.Comparator.comparing(RuntimeTool::getLlmToolName, String::compareTo));
            sb.append(RunnerConstants.PROMPT_TOOLS_HEADER);
            for (RuntimeTool t : sorted) {
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

    /**
     * 当前服务器时间（独立小段文本，供组装侧追加到消息尾部）。
     * 放在 system prompt 头部会破坏前缀缓存，故由调用方追加为最末一条 system 消息，
     * 保证 messages[0] 及历史前缀字节稳定。
     */
    public String currentTimeText() {
        return RunnerConstants.PROMPT_CURRENT_TIME
                + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public List<ToolDefinitionDTO> buildToolDefinitions(List<RuntimeTool> tools) {
        if (tools == null) tools = List.of();
        // 稳定排序：tools 数组参与请求前缀缓存，名称排序可避免能力遍历顺序变动导致的缓存失效
        List<RuntimeTool> sorted = new ArrayList<>(tools);
        sorted.sort(java.util.Comparator.comparing(RuntimeTool::getLlmToolName, String::compareTo));
        List<ToolDefinitionDTO> defs = new ArrayList<>();
        for (RuntimeTool tool : sorted) {
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
