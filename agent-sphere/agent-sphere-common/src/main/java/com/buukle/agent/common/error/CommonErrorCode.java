package com.buukle.agent.common.error;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    PARAM_INVALID("A0100", "参数校验失败", "请检查输入参数"),
    UNAUTHORIZED("A0200", "未授权", "请先登录"),
    FORBIDDEN("A0300", "无权限", "您没有权限执行此操作"),
    RATE_LIMITED("A0400", "请求频率限制", "请稍后重试"),
    RESOURCE_NOT_FOUND("A0404", "资源不存在", "请求的资源不存在"),
    INTERNAL_ERROR("B0500", "系统内部错误", "请稍后重试"),
    SERVICE_UNAVAILABLE("B0503", "服务不可用", "服务暂时不可用，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
