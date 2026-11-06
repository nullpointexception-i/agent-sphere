package com.buukle.agent.superagent.spi;

import java.util.List;

/** Resolves the tools the bound Worker instance would receive for a run. */
public interface WorkerCapabilityPort {

    /** @return name + description of each Worker tool; empty when no Worker is configured. */
    List<WorkerToolView> describe(Long workerInstanceId);
}
