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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveToolResultPolicyTest {

    private static final String BROWSER = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
    private static final String SKILL = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.SKILL_LIBRARY.getId();
    private static final String SKILL_LIBRARY = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.SKILL_LIBRARY.getId();

    private static ChatMessageDTO assistantCall(String callId, String name) {
        return new ChatMessageDTO().setRole("assistant").setToolCalls(List.of(
                ToolCallDTO.builder()
                        .id(callId)
                        .type("function")
                        .function(FunctionDefinitionDTO.builder().name(name).arguments("{}").build())
                        .build()));
    }

    private static ChatMessageDTO tool(String callId, String content) {
        return new ChatMessageDTO().setRole("tool").setToolCallId(callId).setContent(content);
    }

    @Test
    void projectKeepsCanonicalMessagesUntouchedAndSmallBrowserFull() {
        List<ChatMessageDTO> messages = List.of(
                assistantCall("call-old", BROWSER),
                tool("call-old", "browser-old"),
                assistantCall("call-new", BROWSER),
                tool("call-new", "browser-new"));

        List<ChatMessageDTO> projected = LiveToolResultPolicy.project(
                messages, 100, 1_000, 2, 20_000);

        assertEquals("browser-old", projected.get(1).getContent());
        assertEquals("browser-new", projected.get(3).getContent());
        assertEquals("browser-old", messages.get(1).getContent(), "canonical messages 不得被投影修改");
    }

    @Test
    void projectReplacesOnlyLargeBrowserResultWithCallIdReference() {
        String large = "x".repeat(20_001);
        List<ChatMessageDTO> messages = List.of(
                assistantCall("call-small", BROWSER),
                tool("call-small", "{\"data\":{\"_url\":\"https://example.com/a\",\"matches\":["
                        + "{\"text\":\"news-a\"},{\"text\":\"news-b\"},"
                        + "{\"text\":\"news-c\"},{\"text\":\"news-d\"}]}}"),
                assistantCall("call-large", BROWSER),
                tool("call-large", large));

        List<ChatMessageDTO> projected = LiveToolResultPolicy.project(
                messages, 100, 1_000, 2, 20_000);

        String small = String.valueOf(projected.get(1).getContent());
        String big = String.valueOf(projected.get(3).getContent());
        assertTrue(small.contains("news-d"), "小页面应保留完整原文（数组不折叠）");
        assertTrue(big.contains("read_tool_result"));
        assertTrue(big.contains("call-large"));
        assertEquals(large, messages.get(3).getContent());
    }

    @Test
    void skillLibraryListKeepsAllItems() {
        String raw = "{\"action\":\"list\",\"skills\":["
                + "{\"skillId\":83},{\"skillId\":88},{\"skillId\":89},"
                + "{\"skillId\":90},{\"skillId\":84},{\"skillId\":62},"
                + "{\"skillId\":85},{\"skillId\":150},{\"skillId\":18}],\"total\":9}";
        List<ChatMessageDTO> messages = List.of(
                assistantCall("call-skills", SKILL_LIBRARY),
                tool("call-skills", raw));

        List<ChatMessageDTO> projected = LiveToolResultPolicy.project(
                messages, 4_000, 50_000, 2, 20_000);

        String content = String.valueOf(projected.get(1).getContent());
        assertTrue(content.contains("\"skillId\":150"), content);
        assertTrue(content.contains("\"total\":9"), content);
    }

    @Test
    void keepsRecentNonBrowserAndOmitsOlderOverBudget() {
        List<ChatMessageDTO> messages = List.of(
                assistantCall("call-old", SKILL),
                tool("call-old", "skill-old"),
                assistantCall("call-new", SKILL),
                tool("call-new", "skill-new"));

        List<ChatMessageDTO> projected = LiveToolResultPolicy.project(
                messages, 100, 10, 1, 20_000);

        // 最近 1 条保留；更旧的超出预算 → 带 callId 的省略引用
        assertEquals("skill-new", projected.get(3).getContent());
        String old = String.valueOf(projected.get(1).getContent());
        assertTrue(old.contains("read_tool_result"));
        assertTrue(old.contains("call-old"));
    }
}
