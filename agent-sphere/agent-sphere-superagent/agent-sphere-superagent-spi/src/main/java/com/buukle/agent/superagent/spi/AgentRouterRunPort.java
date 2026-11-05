package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Start a run on an existing Agent Router session. */
public interface AgentRouterRunPort {
    AgentRouterRunResult start(AgentRouterRunCommand command);
}
