package com.buukle.agent.superagent.spi;

/** 由 runtime-orchestration 实现：调用 Group Router 模型，返回原始 JSON 文本。 */
public interface GroupRouterPort {
    String classify(GroupRouterRequest request);
}
