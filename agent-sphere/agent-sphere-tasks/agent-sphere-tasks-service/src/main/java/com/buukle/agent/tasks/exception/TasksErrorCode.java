package com.buukle.agent.tasks.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Task-domain business errors exposed through the standard error envelope. */
@Getter
@RequiredArgsConstructor
public enum TasksErrorCode implements ErrorCode {

    /**
     * The browser extension that would execute the task is older than the configured minimum
     * ({@code plugin.min-extension-version}), or did not report a version at all.
     */
    EXTENSION_VERSION_TOO_LOW("T0001",
            "Browser extension version is lower than required",
            "Update the browser extension and retry");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "tasks";
    }
}
