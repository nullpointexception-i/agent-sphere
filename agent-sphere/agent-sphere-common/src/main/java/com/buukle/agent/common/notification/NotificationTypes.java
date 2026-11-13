package com.buukle.agent.common.notification;

/** Stable notification type values shared by writers and the UI. */
public final class NotificationTypes {

    public static final String MENTION = "MENTION";
    public static final String APPLICATION_RUN = "APPLICATION_RUN";
    /** A project assistant is waiting for this user's answer. */
    public static final String PROJECT_ASSISTANT_QUESTION = "PROJECT_ASSISTANT_QUESTION";
    /** A targeted project question expired unanswered. */
    public static final String PROJECT_ASSISTANT_EXPIRED = "PROJECT_ASSISTANT_EXPIRED";

    private NotificationTypes() {
    }
}
