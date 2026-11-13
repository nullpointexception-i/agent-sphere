package com.buukle.agent.project.dtvo.enums;

/** Target kind of an @ mention in a requirement comment. */
public enum CommentMentionType {
    USER,
    AVATAR;

    public String value() {
        return name();
    }
}
