package com.buukle.agent.instance.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InstanceErrorCode implements ErrorCode {
    SESSION_NOT_FOUND("A0001", "Session not found", "Check the session ID"),
    SESSION_CLOSED("A0002", "Session is closed", "Create a new session"),
    RUN_NOT_FOUND("A0003", "Run not found", "Check the run ID"),
    INSTANCE_NOT_FOUND("A0007", "Agent instance not found", "Check the instance ID"),

    // User errors
    USER_NOT_FOUND("A0008", "User not found", "Check the user information"),
    INVALID_CREDENTIALS("A0009", "Incorrect username or password", "Check the username and password"),
    OLD_PASSWORD_MISMATCH("A0010", "Current password is incorrect", "Check the current password"),
    NOT_AUTHENTICATED("A0011", "Not authenticated", "Log in first"),
    USERNAME_TAKEN("A0012", "Username already exists", "Choose a different username"),
    REGISTER_FAILED("A0013", "Registration failed", "Retry later"),
    ROUTE_NO_API_KEY("A0014", "The route provider has no API key", "Configure an API key for the provider first"),
    INSTANCE_NO_MODEL_ROUTE("A0015", "The agent instance has no default model and no override route", "Set a default model or choose an override route");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "instance";
    }
}
