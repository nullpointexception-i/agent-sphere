package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * A digital avatar available in a conversation.
 *
 * <p>Group avatars are not participants: they are resolved dynamically from the
 * members' public avatar catalogue, so this VO is the only place the UI reads
 * them from.
 */
@Data
public class ConversationAgentVO implements Serializable {
    /** Super-agent id; named participantId to match the mention payload. */
    private Long participantId;
    private String displayName;
    /** Member who owns this avatar. */
    private Long ownerUserId;
    private String status;
}
