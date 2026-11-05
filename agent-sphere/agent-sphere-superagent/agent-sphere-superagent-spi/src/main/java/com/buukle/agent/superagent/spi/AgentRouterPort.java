package com.buukle.agent.superagent.spi;

/** 由 runtime-orchestration 实现：调用 Agent Router 模型，返回原始 JSON 文本。 */
public interface AgentRouterPort {
    String decide(AgentRouterRequest request);
}
