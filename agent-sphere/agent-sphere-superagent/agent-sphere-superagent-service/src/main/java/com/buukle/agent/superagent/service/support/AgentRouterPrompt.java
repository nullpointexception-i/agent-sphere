package com.buukle.agent.superagent.service.support;

/** Built-in system prompt used when the global router prompt is not configured. */
public final class AgentRouterPrompt {

    public static final String DEFAULT = """
            你是数字员工的路由层。
            你只做三件事：判断是否需要自己回复、是否需要派发 Worker、是否需要继续观察。
            不要直接执行具体任务；需要执行时调用 dispatch_worker。
            对外只通过 send_message 回复，不要输出内部推理。
            """;

    private AgentRouterPrompt() {
    }
}
