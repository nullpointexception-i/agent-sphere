package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveToolResultPolicyTest {

    private static final String BROWSER = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
    private static final String SKILL = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.SKILL_LIBRARY.getId();

    private static ChatMessageDTO assistant(String... names) {
        List<ToolCallDTO> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(ToolCallDTO.builder()
                    .id("call-" + i + "-" + names[i])
                    .type("function")
                    .function(FunctionDefinitionDTO.builder().name(names[i]).arguments("{}").build())
                    .build());
        }
        return new ChatMessageDTO().setRole("assistant").setToolCalls(calls);
    }

    private static ChatMessageDTO tool(String callId, String content) {
        return new ChatMessageDTO().setRole("tool").setToolCallId(callId).setContent(content);
    }

    @Test
    void keepsLatestBrowserFullAndReplacesOlderBrowserResults() {
        List<ChatMessageDTO> messages = List.of(
                assistant(BROWSER),
                tool("call-0-" + BROWSER, "browser-old"),
                assistant(BROWSER),
                tool("call-0-" + BROWSER, "browser-latest"));

        LiveToolResultPolicy.apply(messages, 100, 1_000, 2);

        assertEquals(LiveToolResultPolicy.BROWSER_OMITTED, messages.get(1).getContent());
        assertEquals("browser-latest", messages.get(3).getContent());
    }

    @Test
    void keepsRecentNonBrowserAndOmitsOlderOverBudget() {
        List<ChatMessageDTO> messages = List.of(
                assistant(SKILL),
                tool("call-0-" + SKILL, "skill-old"),
                assistant(SKILL),
                tool("call-0-" + SKILL, "skill-new"));

        LiveToolResultPolicy.apply(messages, 100, 10, 1);

        // 最近 1 条保留；更旧的超出预算 → 省略
        assertEquals("skill-new", messages.get(3).getContent());
        assertEquals("[Tool result omitted due to context limit]", messages.get(1).getContent());
    }
}
