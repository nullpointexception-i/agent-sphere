package com.buukle.agent.runtime.kernel.port.vo;

import com.buukle.agent.util.json.JsonUtils;

/**
 * Tool execution result returned when the user denies an approval request.
 *
 * <p>Serialized through the value object instead of hand-built JSON so field names and escaping
 * stay consistent across the main loop, resume path and sub-agents.
 */
public record ToolDeniedResultVO(String error, String message, String tool) {

    public static final String ERROR_CODE = "USER_DENIED";

    public static ToolDeniedResultVO of(String toolName) {
        return new ToolDeniedResultVO(ERROR_CODE,
                "User denied this tool call. Do not retry this tool in this run.",
                toolName);
    }

    public String toJson() {
        return JsonUtils.toJson(this);
    }

    /** Convenience for call sites that need the serialized tool result. */
    @Override
    public String toString() {
        return toJson();
    }
}
