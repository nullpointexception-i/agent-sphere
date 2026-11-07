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
    ROLE_SCOPE_DENIED("A0704", "Role is outside your tenant scope",
            "请选择当前租户或下辖租户的角色"),
    ROLE_TENANT_NOT_ASSIGNED("A0705", "Role tenant is not assigned to the user",
            "请先为用户分配该角色所属租户"),
    ROLE_NOT_FOUND("A0706", "Role does not exist", "角色不存在或已被删除"),
    TENANT_SUBTREE_REQUIRED("A0707", "Descendant visibility is required to create a child tenant",
            "请先将当前租户的可见级别改为“可见当前及下辖”后再创建下级租户"),
    ;

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "admin";
    }
}
