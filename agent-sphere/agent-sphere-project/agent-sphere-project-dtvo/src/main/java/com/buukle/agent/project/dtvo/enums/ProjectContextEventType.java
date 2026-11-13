package com.buukle.agent.project.dtvo.enums;

/** Stable event types of the shared project context log. */
public enum ProjectContextEventType {

    PROJECT,
    REQUIREMENT,
    COMMENT,
    INSIGHT,
    ASSISTANT,
    MEMBER;

    public String value() {
        return name();
    }
}
