package com.buukle.agent.im.dtvo.enums;

/** Event names delivered over the user-scoped IM SSE stream. */
public final class ImStreamEventType {

    public static final String MESSAGE_CREATED = "message_created";
    public static final String MESSAGE_UPDATED = "message_updated";
    public static final String MESSAGE_RECALLED = "message_recalled";
    public static final String MESSAGE_PINNED = "message_pinned";
    public static final String MESSAGE_UNPINNED = "message_unpinned";
    public static final String UNREAD_CHANGED = "unread_changed";
    public static final String CONVERSATION_CREATED = "conversation_created";
    public static final String CONVERSATION_UPDATED = "conversation_updated";
    public static final String CONVERSATION_SETTING_CHANGED = "conversation_setting_changed";
    public static final String CONVERSATION_HIDDEN = "conversation_hidden";
    public static final String PARTICIPANT_CHANGED = "participant_changed";
    /** Ephemeral Agent streaming fragments (thinking / reply / status); authority stays in REST. */
    public static final String AGENT_STREAM = "agent_stream";
    public static final String HEARTBEAT = "heartbeat";

    private ImStreamEventType() {
    }
}
