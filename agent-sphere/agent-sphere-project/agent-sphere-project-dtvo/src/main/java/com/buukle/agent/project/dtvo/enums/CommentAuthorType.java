package com.buukle.agent.project.dtvo.enums;

/** Author kind of a requirement comment. */
public enum CommentAuthorType {
    USER,
    ASSISTANT,
    AVATAR;

    public String value() {
        return name();
    }
}
