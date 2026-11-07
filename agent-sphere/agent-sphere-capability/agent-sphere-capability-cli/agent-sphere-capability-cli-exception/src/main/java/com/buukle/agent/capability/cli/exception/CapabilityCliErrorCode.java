package com.buukle.agent.capability.cli.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilityCliErrorCode implements ErrorCode {
    CLI_NOT_FOUND("A0023", "CLI tool not found", "Check the CLI ID"),
    CLI_EXECUTION_FAILED("B0012", "CLI execution failed", "The CLI command failed; retry later");

    private final String code;
    private final String message;
    private final String userTip;
}
