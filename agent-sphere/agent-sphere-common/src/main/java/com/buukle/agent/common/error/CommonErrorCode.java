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
    /**
     * 当前来源端（主站 / widget）不允许对该实例发起或继续会话 —— 实例级开关所限。
     *
     * <p>与 {@link #FORBIDDEN} 区分：那条是「这个用户没有权限」，本条是「这个入口没有开放」。
     */
    SESSION_SURFACE_DISABLED("A0301", "该来源不允许发起会话", "请使用其他入口，或联系管理员开通"),
    RATE_LIMITED("A0400", "请求频率限制", "请稍后重试"),
    RESOURCE_NOT_FOUND("A0404", "资源不存在", "请求的资源不存在"),
    INTERNAL_ERROR("B0500", "系统内部错误", "请稍后重试"),
    SERVICE_UNAVAILABLE("B0503", "服务不可用", "服务暂时不可用，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
