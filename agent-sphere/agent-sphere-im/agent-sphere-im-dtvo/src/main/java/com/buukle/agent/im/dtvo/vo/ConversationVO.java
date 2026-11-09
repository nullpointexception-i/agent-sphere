package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ConversationVO implements Serializable {
    private Long id;
    private String type;
    private String title;
    private String purpose;
    private Long ownerUserId;
    private Long threadId;
    private Long lastSeq;
    private Long unread;
    private String status;
    private String createdAt;
    private String lastMessageAt;
    private Boolean pinned;
    private Boolean muted;
    private String remark;
    private String displayName;
    private String avatar;
    private Boolean selfConversation;
    /** Viewer's role in this conversation (OWNER / ADMIN / MEMBER / BOT). */
    private String myRole;
    /** Whether ordinary members may invite users. */
    private Boolean allowMemberInvite;
    private MessageVO lastMessage;
    private MessageVO pinnedMessage;
    private List<ParticipantVO> participants;
}
