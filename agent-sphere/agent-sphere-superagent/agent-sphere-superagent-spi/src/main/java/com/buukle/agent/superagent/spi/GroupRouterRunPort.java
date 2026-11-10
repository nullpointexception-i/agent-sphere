package com.buukle.agent.superagent.spi;

/**
 * Runs a Group Router batch through the normal agent runtime.
 *
 * <p>The runtime owns the model call, prompt assembly, timeline and LLM interaction records;
 * this port only submits one batch and returns the final assistant text for decision parsing.
 */
public interface GroupRouterRunPort {
    GroupRouterRunResult route(GroupRouterRunCommand command);
}
