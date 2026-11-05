package com.buukle.agent.im.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** IM user-scoped SSE event. Only a lightweight snapshot; REST remains the authority. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImStreamEvent implements Serializable {
    /** message_created / message_updated / message_recalled / unread_changed / ... / heartbeat */
    private String type;
    private Long conversationId;
    private Long messageId;
    private Long seq;
    private Long unread;
    private Long timestamp;
    private String previewText;
    private String createdAt;
    private String title;
    private Boolean pinned;
    private Boolean muted;
    private String remark;
}
