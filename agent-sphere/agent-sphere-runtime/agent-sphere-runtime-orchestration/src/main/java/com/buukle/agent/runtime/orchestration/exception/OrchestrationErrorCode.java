package com.buukle.agent.runtime.orchestration.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrchestrationErrorCode implements ErrorCode {
    SESSION_NOT_FOUND("A0101", "会话不存在", "请检查会话ID"),
    INSTANCE_NOT_FOUND("A0102", "Agent实例不存在", "请检查实例ID"),
    MODEL_ROUTE_NOT_FOUND("A0103", "模型路由未配置", "请为该实例配置模型路由"),
    MODEL_ROUTE_NO_API_KEY("A0104", "供应商未配置API密钥", "请先在API密钥管理中设置当前使用的密钥");

    private final String code;
    private final String message;
    private final String userTip;
}
