package com.buukle.agent.superagent.spi;

import java.util.List;

/**
 * Resolves digital avatars bound to a business entity (application, scene,
 * project or requirement). Implementations must verify that the caller can see
 * the entity before returning agents.
 */
public interface EntityAgentDirectorySpi {

    List<Long> listBoundAgentIds(String entityType, Long entityId, Long userId);

    boolean canMention(String entityType, Long entityId, Long agentId, Long userId);
}
