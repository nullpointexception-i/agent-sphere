package com.buukle.agent.capability.cli.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilityCliErrorCode implements ErrorCode {
    CLI_NOT_FOUND("A0023", "CLI工具不存在", "请检查CLI ID"),
    CLI_EXECUTION_FAILED("B0012", "CLI执行失败", "CLI命令执行异常，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
