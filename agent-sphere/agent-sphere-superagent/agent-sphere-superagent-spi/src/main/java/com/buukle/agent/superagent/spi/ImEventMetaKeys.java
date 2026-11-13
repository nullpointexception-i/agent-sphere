package com.buukle.agent.superagent.spi;

/** JSON field names used by IM event metadata consumed by the router. */
public final class ImEventMetaKeys {

    public static final String AGENT_IDS = "agentIds";
    public static final String MENTIONS = "mentions";
    public static final String CONVERSATION_TYPE = "conversationType";
    public static final String CONVERSATION_TITLE = "conversationTitle";
    public static final String MEMBER_USER_IDS = "memberUserIds";
    public static final String SPEAKER_ID = "speakerId";
    public static final String SPEAKER_NAME = "speakerName";
    public static final String ENTITY_TYPE = "entityType";
    public static final String ENTITY_ID = "entityId";
    public static final String ENTITY_PURPOSE = "entityPurpose";
    /** Pre-resolved, permission-checked entity summary (flat string map). */
    public static final String ENTITY_CONTEXT = "entityContext";
    public static final String LOCALE = "locale";

    private ImEventMetaKeys() {
    }
}
