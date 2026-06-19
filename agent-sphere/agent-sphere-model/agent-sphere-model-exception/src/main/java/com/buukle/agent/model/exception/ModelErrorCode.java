package com.buukle.agent.model.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ModelErrorCode implements ErrorCode {
    PROVIDER_NOT_FOUND("A0011", "供应商不存在", "请检查供应商ID"),
    API_KEY_NOT_FOUND("A0012", "API Key不存在", "请检查API Key ID"),
    ROUTE_NOT_FOUND("A0013", "路由不存在", "请检查路由ID"),
    ROUTE_FALLBACK_NOT_FOUND("A0014", "回退路由不存在", "请检查回退路由ID"),
    ROUTE_FALLBACK_CYCLE("A0015", "回退链路存在循环依赖", "请检查回退路由配置"),
    LLM_CALL_FAILED("C0001", "LLM调用失败", "LLM服务暂时不可用，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
