package com.buukle.agent.superagent.spi;

/** Resolves Super-Agents that the current user is allowed to chat with. */
public interface SuperAgentDirectorySpi {

    /**
     * Resolve an agent for a direct chat.
     *
     * @throws com.buukle.agent.common.exception.BizException when the agent is missing
     *         or not owned by the user (super admins may access any agent)
     */
    ChatAgentIdentity resolveForChat(Long agentId, Long userId);
}
