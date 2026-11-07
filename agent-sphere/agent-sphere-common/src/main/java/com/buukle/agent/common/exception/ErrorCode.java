package com.buukle.agent.common.exception;

public interface ErrorCode {
    String getCode();

    String getMessage();

    String getUserTip();

    /**
     * Stable namespace used by the front-end to build an i18n key:
     * {@code errors.<namespace>.<code>}.
     */
    default String namespace() {
        return "common";
    }
}
