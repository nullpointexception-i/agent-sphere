package com.buukle.agent.runtime.kernel.prompt;

import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
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
        StringBuilder sb = new StringBuilder();
        if (ctx != null && ctx.getAgentInstance() != null) {
            sb.append(ctx.getAgentInstance().getSystemPrompt() != null
                    ? ctx.getAgentInstance().getSystemPrompt() : "");
        }
        List<RuntimeTool> tools = ctx != null ? ctx.getTools() : null;
        // 只列工具名：完整 description/schema 已在请求 `tools` 里各出现一次，
        // 再拼进 system prompt 会让每个工具（尤其浏览器/技能工具）的说明重复发送。
        if (tools != null && !tools.isEmpty()) {
            List<RuntimeTool> sorted = new ArrayList<>(tools);
            sorted.sort(java.util.Comparator.comparing(RuntimeTool::getLlmToolName, String::compareTo));
            sb.append(RunnerConstants.PROMPT_TOOLS_HEADER);
            for (RuntimeTool t : sorted) {
                sb.append("- ").append(t.getLlmToolName()).append("\n");
            }
        }
        sb.append(RunnerConstants.PROMPT_TOOLS_FOOTER);
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
     * 待办列表尾部小段（系统消息），由组装侧追加到消息最末（不晚于 FINAL_TURN），
     * 保证 messages[0] 与历史前缀字节稳定命中缓存。todolist 每次变更只原位替换该槽内容。
     *
     * @return 待办后缀文本；todolistText 为空时返回 null（调用方应移除已有槽位）
     */
    public String buildTodolistSuffix(String todolistText) {
        if (todolistText == null || todolistText.isBlank()) {
            return null;
        }
        return RunnerConstants.PROMPT_TODOLIST_HEADER
                + todolistText
                + RunnerConstants.PROMPT_TODOLIST_INSTRUCTION;
    }

    /** 判断消息是否待办槽（以 header marker 开头，用于原位定位/删除）。 */
    public boolean isTodolistSlot(String content) {
        return content != null && content.startsWith(RunnerConstants.PROMPT_TODOLIST_HEADER);
    }

    /**
     * 每轮 loop 预算预告：告诉模型「当前第几轮 / 还剩几轮（含本轮）」。
     *
     * <p>{@code current} 与 {@code remaining} 均以 1 起算（remaining = 最后一轮时的 1），
     * 所以文案里永远不会出现「剩 0 轮」这种自相矛盾的说法。
     * 剩余轮数逼近上限时追加紧迫提醒 —— 模型对「还剩 100 轮」天然无感，
     * 不提示就会一直不收口，最后被 loop cap 硬截断成半成品。
     *
     * @param current   当前轮序号（1 起算）
     * @param remaining 剩余轮数（1 起算，含本轮）
     * @param total     生效的轮次上限（实例/任务/系统配置覆盖后的真实值）
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

    /** 判断消息是否 loop 预算预告（以 marker 开头，用于跨轮定位并清除上一轮副本）。 */
    public boolean isLoopBudget(String content) {
        return content != null && content.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX);
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
