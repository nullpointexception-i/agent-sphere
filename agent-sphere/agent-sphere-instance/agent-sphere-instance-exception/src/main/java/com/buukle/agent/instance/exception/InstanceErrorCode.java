package com.buukle.agent.instance.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InstanceErrorCode implements ErrorCode {
    SESSION_NOT_FOUND("A0001", "会话不存在", "请检查会话ID"),
    SESSION_CLOSED("A0002", "会话已关闭", "请创建新会话"),
    RUN_NOT_FOUND("A0003", "运行记录不存在", "请检查运行ID"),
    INSTANCE_NOT_FOUND("A0007", "Agent实例不存在", "请检查实例ID"),

    // User errors
    USER_NOT_FOUND("A0008", "用户不存在", "请检查用户信息"),
    INVALID_CREDENTIALS("A0009", "用户名或密码错误", "请检查用户名和密码"),
    OLD_PASSWORD_MISMATCH("A0010", "当前密码错误", "请检查当前密码"),
    NOT_AUTHENTICATED("A0011", "未登录", "请先登录"),
    USERNAME_TAKEN("A0012", "用户名已存在", "请更换用户名"),
    REGISTER_FAILED("A0013", "注册失败", "请稍后重试"),
    ROUTE_NO_API_KEY("A0014", "模型路由所属供应商未配置API Key", "请先为该供应商配置API Key"),
    INSTANCE_NO_MODEL_ROUTE("A0015", "Agent实例未设置默认模型且未指定覆盖模型路由", "请为实例设置默认模型或选择覆盖模型路由");

    private final String code;
    private final String message;
    private final String userTip;
}
