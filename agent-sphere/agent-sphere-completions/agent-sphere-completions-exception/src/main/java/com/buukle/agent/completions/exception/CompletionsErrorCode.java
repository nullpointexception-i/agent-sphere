package com.buukle.agent.completions.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CompletionsErrorCode implements ErrorCode {
    COMPLETIONS_NOT_FOUND("C0001", "Completions configuration not found", "Check completionsId"),
    COMPLETIONS_DISABLED("C0002", "Completions configuration is disabled", "Enable it and retry"),
    PROMPT_NOT_FOUND("C0003", "Prompt version not found", "Check promptId"),
    PROMPT_NOT_BELONG("C0004", "Prompt version does not belong to this completions configuration", "Check promptId"),
    NO_ACTIVE_PROMPT("C0005", "No active prompt version", "Create and activate a prompt version first"),
    NO_MODEL_ROUTE("C0006", "No model route bound", "Bind modelRouteId first"),
    NO_API_KEY("C0007", "The route provider has no API key", "Configure an API key for the provider"),
    LLM_CALL_FAILED("C0008", "Model call failed", "Retry later or switch models");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "completions";
    }
}
