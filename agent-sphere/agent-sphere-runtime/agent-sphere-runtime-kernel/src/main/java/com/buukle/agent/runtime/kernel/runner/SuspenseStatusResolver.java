package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether a tool call requires suspending the current run.
 *
 * <p>Historically this was {@code toolResult.contains("\"status\":\"awaiting_user\"")} — depending on the serialized
 * field order and literal, changing the encoding once (say, adding a space to the JSON) silently broke it: the suspension would not trigger,
 * and the run would keep idling on an "awaiting your answer" result — harder to diagnose than an error. Now it is parsed structurally.
 *
 * <p>Any parse failure returns {@code null} (no suspension): better to run an extra loop than to silently park the run.
 */
@Slf4j
public final class SuspenseStatusResolver {

    private SuspenseStatusResolver() {
    }

    /**
     * @return the corresponding {@link RunStatus} ({@code AWAITING_USER} / {@code WAITING}); {@code null} when not suspending
     */
    public static RunStatus resolve(String toolName, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        boolean candidate = ChatClarification.CLARIFICATION_TOOL_NAME.equals(toolName)
                || WaitConstants.TOOL_NAME.equals(toolName);
        if (!candidate) {
            return null;
        }
        try {
            String status = JsonUtils.getMapper().readTree(toolResult)
                    .path(ChatClarification.CLARIFYING_JSON_STATUS).asText(null);
            if (ChatClarification.CLARIFYING_STATUS_AWAITING_USER.equals(status)) {
                return RunStatus.AWAITING_USER;
            }
            if (WaitConstants.STATUS_WAITING.equals(status)) {
                return RunStatus.WAITING;
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse suspend status from tool result: {}", toolResult);
            return null;
        }
    }
}