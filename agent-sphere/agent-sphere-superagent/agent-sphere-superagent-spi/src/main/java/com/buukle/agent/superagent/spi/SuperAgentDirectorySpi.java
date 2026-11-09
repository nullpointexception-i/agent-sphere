package com.buukle.agent.superagent.spi;

import java.util.Collection;
import java.util.List;

/** Resolves Super-Agents that the current user is allowed to chat with. */
public interface SuperAgentDirectorySpi {

    /**
     * Resolve an agent for a direct chat.
     *
     * @throws com.buukle.agent.common.exception.BizException when the agent is missing
     *         or not owned by the user (super admins may access any agent)
     */
    ChatAgentIdentity resolveForChat(Long agentId, Long userId);

    /**
     * Resolve an agent that may be added to a group chat.
     *
     * <p>Allowed when the caller owns the agent, is a super admin, or the agent is
     * ACTIVE and explicitly public. Paused/archived agents are never joinable.
     */
    ChatAgentIdentity resolveForGroupChat(Long agentId, Long userId);

    /**
     * All ACTIVE, public avatars owned by the given users. Used to auto-populate
     * group chats with members' public avatars.
     */
    List<ChatAgentIdentity> listPublicByOwners(Collection<Long> ownerUserIds);
}
