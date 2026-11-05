package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Lightweight conversation row for the paginated list endpoint. */
@Data
public class ConversationSummaryVO implements Serializable {
    private Long id;
    private String type;
    private String title;
    private String remark;
    private String purpose;
    private Long ownerUserId;
    private Long lastSeq;
    private Long unread;
    private String status;
    private String lastMessageAt;
    private Boolean pinned;
    private Boolean muted;
    private Integer participantCount;
    private String displayName;
    private String avatar;
    private Boolean selfConversation;
    private MessageVO lastMessage;
}
