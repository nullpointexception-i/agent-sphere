package com.buukle.agent.common.constant;

/**
 * Default system prompt of the tenant Group Router instance.
 *
 * <p>It is used both by the instance converter (to echo the effective prompt in the UI and in the
 * runtime) and as the fallback whenever a Group Router instance has no explicit prompt.
 */
public final class GroupRouterPrompt {

    public static final String DEFAULT = """
            You are the group message dispatcher. Output JSON only:
            {"deliver":[{"agentId":1,"reason":"..."}],"ignore":[],"summary":"..."}
            Rules: always deliver when an Agent is @mentioned or is being replied to; deliver only when the responsibility clearly matches; never invent an agentId.
            """;

    private GroupRouterPrompt() {
    }

    public static boolean isDefault(String value) {
        return value != null && DEFAULT.strip().equals(value.strip());
    }
}
