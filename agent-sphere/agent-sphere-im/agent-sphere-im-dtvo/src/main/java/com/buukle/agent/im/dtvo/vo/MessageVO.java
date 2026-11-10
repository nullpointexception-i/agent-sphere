package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class MessageVO implements Serializable {
    private Long id;
    private Long conversationId;
    private Long seq;
    private String senderType;
    private Long senderId;
    private String clientMsgId;
    private String type;
    private Object content;
    private Long replyToId;
    private Object mentions;
    private String status;
    private String createdAt;
    /** Resolved sender display name (agent name or member label). */
    private String senderName;
    /** For AGENT senders: the member this avatar belongs to. */
    private String senderOwnerName;
    /** For AGENT senders: true when the avatar belongs to the current viewer. */
    private Boolean ownAgent;
}
