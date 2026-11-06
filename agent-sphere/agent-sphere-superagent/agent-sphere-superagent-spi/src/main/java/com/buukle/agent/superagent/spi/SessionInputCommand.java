package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/**
 * A pending Router clarification / tool approval observed from the runtime event
 * stream. The consumer projects it into {@code agent_session_input} and asks the
 * user through an authoritative IM card.
 */
public record SessionInputCommand(
        Long superAgentId,
        Long threadId,
        Long sessionId,
        Long runId,
        String inputType,
        String inputId,
        String title,
        String description,
        String optionsJson,
        Boolean multiple,
        String toolCallsJson
) implements Serializable {
}
