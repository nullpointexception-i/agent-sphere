package com.buukle.agent.runtime.kernel.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.common.skill.ToolRefs;
import com.buukle.agent.instance.dtvo.dto.TodowriteResultDTO;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.contract.CliExecutionBinding;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.SkillExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.skill.SkillReActExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CLARIFICATION_ID_LENGTH = 8;
    private static final String JSON_ERROR_UNKNOWN_TOOL = "{\"error\":\"Unknown tool: ";
    private static final String JSON_ERROR_CLARIFICATION = "{\"error\":\"Failed to process clarification\"}";
    private static final String JSON_ERROR_UNSUPPORTED_TYPE = "{\"error\":\"Unsupported capability type: ";
    private static final String JSON_ERROR_EXECUTION = "{\"error\":\"";
    private static final String STATUS_AWAITING_USER = "awaiting_user";

    private final List<CapabilityMcpSpi> mcpSpis;
    private final CapabilityBuiltinSpi builtinSpi;
    private final CliExecutorService cliExecutorService;
    private final SessionTodoSpi sessionTodoSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final ClarificationSpi clarificationSpi;
    private final org.springframework.beans.factory.ObjectProvider<SkillReActExecutor> skillReActExecutorProvider;

    /** 主循环工具入口：以根上下文执行（主 Agent 无父级限制）。 */
    public String execute(TurnToolCall tc, Long sessionId, Long runId, List<RuntimeTool> tools) {
        return execute(tc, SkillExecutionContext.root(sessionId, runId, null), tools);
    }

    /** 带嵌套上下文执行：Skill 子循环内层工具使用子上下文（深度/栈/白名单）。 */
    public String execute(TurnToolCall tc, SkillExecutionContext ctx, List<RuntimeTool> tools) {
        Long sessionId = ctx.getSessionId();
        Long runId = ctx.getRunId();
        RuntimeTool tool = tools.stream()
                .filter(t -> tc.name().equals(t.getLlmToolName()))
                .findFirst().orElse(null);

        if (tool == null) {
            return JSON_ERROR_UNKNOWN_TOOL + tc.name() + "\"}";
        }

        try {
            String type = tool.getCapabilityType();
            Map<String, Object> binding = tool.getExecBinding();
            String args = tc.arguments() != null ? tc.arguments() : RunnerConstants.EMPTY_JSON_ARGS;

            if (CAPABILITY_TYPE_MCP.equals(type) && !mcpSpis.isEmpty()) {
                return mcpSpis.get(0).executeTool(
                        (String) binding.get(ExecBindingKeys.MCP_SERVER_URL),
                        (String) binding.get(ExecBindingKeys.MCP_NATIVE_TOOL_NAME), args);
            }
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && ChatClarification.CLARIFICATION_TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
                    String clarifyType = argsNode.has("type") ? argsNode.get("type").asText() : ChatClarification.CLARIFYING_DEFAULT_TYPE;
                    String optionsJson = argsNode.has("options") ? argsNode.get("options").toString() : null;
                    String clarificationId = java.util.UUID.randomUUID().toString().substring(0, CLARIFICATION_ID_LENGTH);
                    eventPublisher.publishEvent(new RuntimeEventVO(
                            ClarificationStatus.PENDING,
                            new RuntimeEventDataVO()
                                    .setRunId(runId)
                                    .setSessionId(sessionId)
                                    .setPrompt(title)
                                    .setType(clarifyType)
                                    .setArgumentsJson(optionsJson)
                                    .setClarificationId(clarificationId)));
                    clarificationSpi.createPending(sessionId, runId, runId, title, clarifyType, optionsJson, clarificationId);
                    return JSON.writeValueAsString(Map.of(
                            ChatClarification.CLARIFYING_JSON_STATUS, ChatClarification.CLARIFYING_STATUS_AWAITING_USER,
                            ChatClarification.CLARIFYING_JSON_CLARIFICATION_ID, clarificationId
                    ));
                } catch (Exception e) {
                    log.warn("Failed to process ask_clarification", e);
                    return JSON_ERROR_CLARIFICATION;
                }
            }

            if (CAPABILITY_TYPE_BUILTIN.equals(type)) {
                String result = builtinSpi.executeBuiltinTool(
                        (String) binding.get(ExecBindingKeys.BUILTIN_INTERNAL_NAME), args, sessionId, runId);
                String todowriteName = LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId();
                if (tc.name().equals(todowriteName)) {
                    persistTodosFromResult(sessionId, runId, result);
                }
                return result;
            }
            if (CAPABILITY_TYPE_CLI.equals(type)) {
                CliExecutionBinding cliBinding = new CliExecutionBinding(
                        (String) binding.get(ExecBindingKeys.CLI_COMMAND_TEMPLATE),
                        (String) binding.get(ExecBindingKeys.CLI_WORKING_DIR));
                return cliExecutorService.execute(cliBinding, args);
            }
            if (CAPABILITY_TYPE_SKILL.equals(type)) {
                SkillReActExecutor skillExecutor = skillReActExecutorProvider.getIfAvailable();
                if (skillExecutor == null) {
                    return "{\"error\":\"Skill executor unavailable\"}";
                }
                // 携带触发本 skill 的工具调用 id 作为 parentToolCallId（sub_agent_run.parent_tool_call_id 关联回溯）
                SkillExecutionContext skillCtx = ctx.child(
                        ctx.getSkillDepth() + 1, ctx.getSkillStack(), ctx.getInheritedAllowedToolRefs(), tc.id());
                return skillExecutor.execute(tool, args, skillCtx, tools);
            }
            return JSON_ERROR_UNSUPPORTED_TYPE + type + "\"}";
        } catch (Exception e) {
            log.warn("Tool execution failed: tool={}", tc.name(), e);
            return JSON_ERROR_EXECUTION + e.getMessage() + "\"}";
        }
    }

    public String resolveDisplayName(String toolName, List<RuntimeTool> tools) {
        if (tools == null || tools.isEmpty()) return toolName;
        return tools.stream()
                .filter(t -> toolName.equals(t.getLlmToolName()))
                .map(RuntimeTool::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(toolName);
    }

    public String resolveDisplayNameEn(String toolName, List<RuntimeTool> tools) {
        if (tools == null || tools.isEmpty()) return toolName;
        return tools.stream()
                .filter(t -> toolName.equals(t.getLlmToolName()))
                .map(RuntimeTool::getDisplayNameEn)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(toolName);
    }

    /** 判断某个工具调用是否为 skill 工具（toolRef 形如 "skill:<id>"），用于放宽外层 fiber 超时。 */
    public boolean isSkillTool(String toolName, List<RuntimeTool> tools) {
        if (toolName == null || tools == null || tools.isEmpty()) return false;
        String prefix = ToolRefs.TYPE_SKILL + ToolRefs.SEPARATOR;
        return tools.stream().anyMatch(t ->
                toolName.equals(t.getLlmToolName())
                        && t.getToolRef() != null
                        && t.getToolRef().startsWith(prefix));
    }

    private void persistTodosFromResult(Long sessionId, Long runId, String resultJson) {
        try {
            TodowriteResultDTO result = JSON.readValue(resultJson, TodowriteResultDTO.class);
            var items = result.getTodos();
            if (items == null || items.isEmpty()) {
                sessionTodoSpi.replaceAll(sessionId, runId, List.of());
                return;
            }
            List<SessionTodoVO> todos = items.stream()
                    .map(item -> new SessionTodoVO(item.getContent(), item.getStatus(), item.getPriority()))
                    .toList();
            sessionTodoSpi.replaceAll(sessionId, runId, todos);
        } catch (Exception e) {
            log.warn("Failed to persist todowrite result for session {}", sessionId, e);
        }
    }
}
