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
    /** message_created / message_updated / message_recalled / unread_changed / agent_stream / heartbeat */
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
    /** IM thread carrying the agent session (agent_stream only). */
    private Long threadId;
    /** Router run id (agent_stream only). */
    private Long runId;
    /** Super-Agent id (agent_stream only). */
    private Long agentId;
    /** thinking / reply / status (agent_stream only). */
    private String phase;
    /** Incremental fragment for thinking / reply. */
    private String delta;
    /** started / completed / failed / cancelled (agent_stream only). */
    private String streamStatus;
    private String toolName;
    private String displayName;
    private String errorMessage;
    /** Monotonic fragment counter for de-duplication (agent_stream only). */
    private Long streamSeq;
    /** USER / AGENT / SYSTEM sender of the authoritative message (message_created only). */
    private String senderType;
    private Long senderId;
    private String clientMsgId;
}
