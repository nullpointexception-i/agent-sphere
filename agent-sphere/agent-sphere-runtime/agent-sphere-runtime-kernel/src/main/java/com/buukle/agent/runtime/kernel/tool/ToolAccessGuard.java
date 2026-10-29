package com.buukle.agent.runtime.kernel.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/** Enforces run read-only mode against the effective tool access mode. */
public final class ToolAccessGuard {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONTROL_TOOLS = Set.of(
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId(),
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.FINISH.getId(),
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.ASK_CLARIFICATION.getId(),
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.WAIT.getId());
    private static final Set<String> CHROME_READ_ACTIONS = Set.of(
            "getContent", "screenshot", "readInput", "query", "extract",
            "containers", "ready", "navigate", "scroll", "hover");
    private static final String CHROME_TOOL =
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();

    private ToolAccessGuard() {
    }

    /**
     * @return null when allowed; otherwise a model-visible denial message.
     */
    public static String denyReason(RuntimeTool tool, String argumentsJson, String runMode) {
        if (tool == null || !RunMode.from(runMode).readOnly()) {
            return null;
        }
        if (CONTROL_TOOLS.contains(tool.getLlmToolName())) {
            return null;
        }
        ToolAccessMode mode = ToolAccessMode.from(tool.getAccessMode());
        if (mode == ToolAccessMode.READ) {
            return null;
        }
        if (mode == ToolAccessMode.WRITE) {
            return denied(tool, null);
        }
        // READ_WRITE: MCP/CLI are allowed by policy; builtin browser is action-scoped.
        if (CHROME_TOOL.equals(tool.getLlmToolName())) {
            String action = action(argumentsJson);
            if (action == null || !CHROME_READ_ACTIONS.contains(action)) {
                return denied(tool, action);
            }
        }
        return null;
    }

    private static String action(String argumentsJson) {
        try {
            JsonNode node = JSON.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
            String action = node.path("action").asText("");
            return action.isBlank() ? null : action;
        } catch (Exception e) {
            return null;
        }
    }

    private static String denied(RuntimeTool tool, String action) {
        String suffix = action == null ? "" : " action=" + action;
        return "{\"error\":\"run is read-only: tool " + tool.getLlmToolName()
                + " requires write access" + suffix + "\"}";
    }
}
