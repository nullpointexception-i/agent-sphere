package com.buukle.agent.common.constant;

/**
 * OpenAI-compatible chat message roles.
 *
 * <p>Shared because the provider side (model-service) parses/rewrites them and the consumer
 * side (runtime-kernel) builds the request.
 */
public final class LlmRoles {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";

    private LlmRoles() {
    }
}
