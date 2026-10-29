package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * live 消息里的工具结果预算：
 * - 浏览器类只保留**最近一条全文**，更早的替换为占位符（页面状态是时效性的，旧快照无价值）；
 * - 非浏览器结果按单条上限压缩，最近 N 条始终保留，其余在总预算内尽量保留，超出用省略标记；
 * - 只改内存消息，完整 artifact 仍在 DB/timeline，模型可用 getContent 回读。
 */
public final class LiveToolResultPolicy {

    public static final String BROWSER_OMITTED =
            "[Previous browser result omitted to save context; re-read the page with getContent if needed]";

    private LiveToolResultPolicy() {
    }

    public static String browserToolName() {
        return InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
    }

    public static void apply(List<ChatMessageDTO> messages, int maxChars, int budgetChars, int keepRecent) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String browserTool = browserToolName();
        Map<String, String> callNames = new HashMap<>();
        for (ChatMessageDTO m : messages) {
            if (!LlmApiConstant.ROLE_ASSISTANT.equals(m.getRole()) || m.getToolCalls() == null) {
                continue;
            }
            for (ToolCallDTO tc : m.getToolCalls()) {
                if (tc.getId() != null && tc.getFunction() != null) {
                    callNames.put(tc.getId(), tc.getFunction().getName());
                }
            }
        }

        int latestBrowserIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDTO m = messages.get(i);
            if (LlmApiConstant.ROLE_TOOL.equals(m.getRole())
                    && browserTool.equals(callNames.get(m.getToolCallId()))) {
                latestBrowserIndex = i;
            }
        }

        int keptNonBrowser = 0;
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDTO m = messages.get(i);
            if (!LlmApiConstant.ROLE_TOOL.equals(m.getRole())) {
                continue;
            }
            if (!(m.getContent() instanceof String content)) {
                continue;
            }
            String name = callNames.get(m.getToolCallId());
            if (browserTool.equals(name)) {
                if (i != latestBrowserIndex) {
                    m.setContent(BROWSER_OMITTED);
                }
                continue;
            }
            String compressed = ToolResultCompressor.compress(content, maxChars);
            if (keptNonBrowser < keepRecent) {
                m.setContent(compressed);
                keptNonBrowser++;
                used += compressed.length();
            } else if (used + compressed.length() <= budgetChars) {
                m.setContent(compressed);
                used += compressed.length();
            } else {
                m.setContent(RunnerConstants.TOOL_RESULT_BUDGET_OMITTED);
            }
        }
    }
}
