package com.buukle.agent.runtime.kernel.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KernelErrorCode implements ErrorCode {
    // K0001-K0099: Resource and Configuration Errors
    TOOL_NOT_FOUND("K0001", "Tool not found", "Please check the tool name or capability configuration"),
    CAPABILITY_NOT_AVAILABLE("K0002", "Capability not available", "Please check capability configuration and connection status"),
    UNSUPPORTED_CAPABILITY_TYPE("K0003", "Unsupported capability type", "Capability type configuration error"),
    INVALID_TOOL_PARAMS("K0004", "Invalid tool parameters", "Please check input parameter format"),

    // K0100-K0199: Execution Failure Errors
    TOOL_EXECUTION_FAILED("K0101", "Tool execution failed", "Tool execution error, please check detailed logs"),
    PLAN_GENERATION_FAILED("K0102", "Plan generation failed", "LLM plan generation failed, please retry"),
    GRAPH_BUILD_FAILED("K0103", "State graph build failed", "System initialization error"),
    DAG_BUILD_FAILED("K0104", "DAG build failed", "Step dependency relationship configuration error"),

    // K0200-K0299: Timeout and Limit Errors
    STEP_EXECUTION_TIMEOUT("K0201", "Step execution timeout", "Step execution time exceeded limit"),
    TASK_EXECUTION_TIMEOUT("K0202", "Task execution timeout", "Task execution time exceeded limit"),
    MAX_RETRIES_EXCEEDED("K0203", "Max retries exceeded", "Step execution failed too many times"),
    CIRCUIT_BREAKER_OPEN("K0204", "Circuit breaker opened", "Too many failed steps, task automatically stopped"),

    // K0300-K0399: State and Process Errors
    INVALID_STATE_TRANSITION("K0301", "Invalid state transition", "Current state does not allow this operation"),
    PRECONDITION_NOT_MET("K0302", "Precondition not met", "Step precondition check failed"),
    ADMISSION_CHECK_FAILED("K0303", "Admission check failed", "Step admission condition not met"),

    // K0400-K0499: LLM Call Errors
    LLM_CALL_TIMEOUT("K0401", "LLM call timeout", "LLM service response timeout"),
    LLM_RESPONSE_PARSE_FAILED("K0402", "LLM response parse failed", "LLM returned incorrect format"),

    // K0500-K0599: System Internal Errors
    KERNEL_INTERNAL_ERROR("K0501", "Kernel internal error", "System internal error, please contact administrator");

    private final String code;
    private final String message;
    private final String userTip;
}
