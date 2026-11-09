package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/**
 * Creates the IM thread (and, for groups, the group-router session) when a
 * conversation is created. Lazy creation at message ingest time is gone.
 */
public interface GroupChatBootstrapSpi {

    GroupChatBootstrapResult bootstrap(GroupChatBootstrapCommand command);

    record GroupChatBootstrapCommand(
            Long conversationId,
            String conversationType,
            String title,
            String purpose,
            Long creatorUserId,
            String creatorUsername,
            Long tenantId
    ) implements Serializable {
    }

    record GroupChatBootstrapResult(
            Long threadId,
            Long groupRouterSessionId
    ) implements Serializable {
    }
}
