package com.buukle.agent.runtime.kernel.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolAccessGuardTest {

    private static RuntimeTool tool(String name, ToolAccessMode mode) {
        return RuntimeTool.builder()
                .llmToolName(name)
                .toolRef("test:" + name)
                .accessMode(mode.name())
                .build();
    }

    @Test
    void writeModeAllowsEverything() {
        assertNull(ToolAccessGuard.denyReason(tool("x", ToolAccessMode.WRITE), "{}", RunMode.WRITE.name()));
    }

    @Test
    void readOnlyAllowsReadAndBlocksWrite() {
        assertNull(ToolAccessGuard.denyReason(tool("x", ToolAccessMode.READ), "{}", RunMode.READ_ONLY.name()));
        assertNotNull(ToolAccessGuard.denyReason(tool("x", ToolAccessMode.WRITE), "{}", RunMode.READ_ONLY.name()));
    }

    @Test
    void readOnlyAllowsMcpCliMixedTools() {
        assertNull(ToolAccessGuard.denyReason(tool("mcp_1_0", ToolAccessMode.READ_WRITE), "{}",
                RunMode.READ_ONLY.name()));
    }

    @Test
    void chromeReadActionsAllowedAndWriteActionsBlocked() {
        String chrome = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
        RuntimeTool browser = tool(chrome, ToolAccessMode.READ_WRITE);
        for (String action : new String[]{"getContent", "navigate", "scroll", "hover", "screenshot"}) {
            assertNull(ToolAccessGuard.denyReason(browser, "{\"action\":\"" + action + "\"}",
                    RunMode.READ_ONLY.name()), action);
        }
        for (String action : new String[]{"click", "type", "executeJS", "clickAt"}) {
            assertNotNull(ToolAccessGuard.denyReason(browser, "{\"action\":\"" + action + "\"}",
                    RunMode.READ_ONLY.name()), action);
        }
    }

    @Test
    void controlToolsAreAllowedInReadOnly() {
        String todo = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId();
        assertNull(ToolAccessGuard.denyReason(tool(todo, ToolAccessMode.WRITE), "{}",
                RunMode.READ_ONLY.name()));
    }
}
