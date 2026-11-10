package com.buukle.agent.common.constant;

/** Conversation list scopes used by the chat sidebar. */
public final class SessionListScope {

    /** The caller's user conversations (and own router/worker sessions when role is set). */
    public static final String USER = "USER";
    /** The caller's own group chats (GROUP_ROUTER sessions created by them). */
    public static final String GROUP = "GROUP";
    /** All tenant-level group chats; tenant administrators only. */
    public static final String TENANT = "TENANT";

    private SessionListScope() {
    }
}
