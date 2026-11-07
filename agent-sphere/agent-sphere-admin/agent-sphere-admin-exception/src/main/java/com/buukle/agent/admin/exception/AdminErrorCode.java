package com.buukle.agent.admin.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {
    PERMISSION_CODE_EXISTS("A0701", "Permission code already exists", "Choose a different permission code"),
    ROLE_CODE_EXISTS("A0702", "Role code already exists", "Choose a different role code"),
    USER_ALREADY_HAS_ROLE("A0703", "User already has this role", "Do not assign it twice"),
    ;

    private final String code;
    private final String message;
    private final String userTip;
}
