package com.buukle.agent.project.dtvo.enums;

/** Processing state of one comment mention. */
public enum CommentMentionStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    SKIPPED;

    public String value() {
        return name();
    }
}
