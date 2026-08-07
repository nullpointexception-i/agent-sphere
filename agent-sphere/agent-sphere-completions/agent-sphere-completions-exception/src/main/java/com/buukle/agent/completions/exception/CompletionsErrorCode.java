package com.buukle.agent.completions.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CompletionsErrorCode implements ErrorCode {
    COMPLETIONS_NOT_FOUND("C0001", "Completions 不存在", "请检查 completionsId"),
    COMPLETIONS_DISABLED("C0002", "Completions 已停用", "请启用后重试"),
    PROMPT_NOT_FOUND("C0003", "Prompt 版本不存在", "请检查 promptId"),
    PROMPT_NOT_BELONG("C0004", "Prompt 版本不属于该 Completions", "请检查 promptId"),
    NO_ACTIVE_PROMPT("C0005", "Completions 未配置生效 Prompt 版本", "请先创建并激活 Prompt 版本"),
    NO_MODEL_ROUTE("C0006", "Completions 未绑定模型路由", "请先绑定 modelRouteId"),
    NO_API_KEY("C0007", "模型路由所属供应商未配置 API Key", "请为该供应商配置 API Key"),
    LLM_CALL_FAILED("C0008", "模型调用失败", "请稍后重试或切换模型");

    private final String code;
    private final String message;
    private final String userTip;
}
