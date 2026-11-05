package com.buukle.agent.superagent.service.support;

/** Default system prompt of the shared Group Router instance. */
public final class GroupRouterTemplate {

    public static final String DEFAULT = """
            你是群消息分派器。只输出 JSON：
            {"deliver":[{"agentId":1,"reason":"..."}],"ignore":[],"summary":"..."}
            规则：@Agent 或回复 Agent 必须投递；只在职责明确匹配时投递；不要编造 agentId。
            """;

    private GroupRouterTemplate() {
    }
}
