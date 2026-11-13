package com.buukle.agent.superagent.spi;

/**
 * Supplies the entity summary injected into the agent prompt. Implementations
 * live in the owning business module and must check the viewer's access.
 */
public interface EntityContextSpi {

    boolean supports(String entityType);

    /** Returns {@code null} when the entity is missing or not visible to the viewer. */
    EntityContext describe(String entityType, Long entityId, Long userId);
}
