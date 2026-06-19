package com.buukle.agent.runtime.kernel.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.instance.dtvo.dto.TodowriteResultDTO;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CapabilityMcpSpi> mcpSpis;
    private final CapabilityBuiltinSpi builtinSpi;
    private final CliExecutorService cliExecutorService;
    private final SessionTodoSpi sessionTodoSpi;

    public String execute(TurnToolCall tc, Long sessionId, Long runId, List<RuntimeTool> tools) {
        RuntimeTool tool = tools.stream()
            .filter(t -> tc.name().equals(t.getLlmToolName()))
            .findFirst().orElse(null);

        if (tool == null) {
            return "{\"error\":\"Unknown tool: " + tc.name() + "\"}";
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
                return cliExecutorService.execute(binding, args);
            }
            if (CAPABILITY_TYPE_SKILL.equals(type)) {
                return "{\"info\":\"Skill tool delegated to next turn\"}";
            }
            return "{\"error\":\"Unsupported capability type: " + type + "\"}";
        } catch (Exception e) {
            log.warn("Tool execution failed: tool={}", tc.name(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
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
