package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/**
 * One tool the bound Worker can actually use, reduced to what the Router needs to
 * judge feasibility: a human-readable name and a short description.
 */
public record WorkerToolView(String name, String description) implements Serializable {
}
