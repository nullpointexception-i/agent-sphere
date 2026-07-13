package com.buukle.agent.admin.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {
    PERMISSION_CODE_EXISTS("A0701", "权限标识已存在", "请更换权限标识"),
    ROLE_CODE_EXISTS("A0702", "角色编码已存在", "请更换角色编码"),
    USER_ALREADY_HAS_ROLE("A0703", "用户已拥有该角色", "请勿重复分配"),
    ;

    private final String code;
    private final String message;
    private final String userTip;
}
