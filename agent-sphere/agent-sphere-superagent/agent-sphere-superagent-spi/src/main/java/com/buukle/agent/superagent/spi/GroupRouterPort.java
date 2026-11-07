package com.buukle.agent.superagent.spi;

/** Implemented by runtime-orchestration: calls the Group Router model and returns the raw JSON text. */
public interface GroupRouterPort {
    String classify(GroupRouterRequest request);
}
