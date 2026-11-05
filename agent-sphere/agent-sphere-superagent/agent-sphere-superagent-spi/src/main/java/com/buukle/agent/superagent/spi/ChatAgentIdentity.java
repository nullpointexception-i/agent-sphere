package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Minimal identity of a Super-Agent as an IM chat participant. */
public record ChatAgentIdentity(
        Long agentId,
        String name,
        Long ownerUserId,
        String status
) implements Serializable {
}
