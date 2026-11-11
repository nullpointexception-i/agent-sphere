package com.buukle.agent.superagent.spi;

/** Creates a local task for a desktop runtime. */
public interface LocalAgentDispatchPort {

    LocalAgentDispatchResult dispatch(LocalAgentDispatchCommand command);
}
