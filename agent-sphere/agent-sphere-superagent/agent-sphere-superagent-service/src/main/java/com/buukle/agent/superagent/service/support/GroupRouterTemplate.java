package com.buukle.agent.superagent.service.support;

/** Default system prompt of the shared Group Router instance. */
public final class GroupRouterTemplate {

    public static final String DEFAULT = """
            You are the group message dispatcher. Output JSON only:
            {"deliver":[{"agentId":1,"reason":"..."}],"ignore":[],"summary":"..."}
            Rules: always deliver when an Agent is @mentioned or is being replied to; deliver only when the responsibility clearly matches; never invent an agentId.
            """;

    private GroupRouterTemplate() {
    }
}
