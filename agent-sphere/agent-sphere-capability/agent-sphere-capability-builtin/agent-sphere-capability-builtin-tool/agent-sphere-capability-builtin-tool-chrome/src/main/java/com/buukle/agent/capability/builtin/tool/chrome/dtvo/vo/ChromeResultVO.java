package com.buukle.agent.capability.builtin.tool.chrome.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ChromeResultVO extends ExecuteResult {

    @Schema(example = "true")
    private boolean success;

    private Object data;

    @Schema(example = "")
    private String errorMessage;

    @Schema(example = "not_found", description = "Failure category: not_found / csp_blocked / detached / inject_failed / timeout / no_tab / unknown")
    private String errorCategory;

    @Schema(example = "debugger", description = "Execution path used: isolated / inject-bridge / scripting-main / debugger / navigate / click / type / ...")
    private String method;

    @Schema(example = "string", description = "executeJS结果的JS类型（void/string/number/object等）")
    private String resultType;

    @Schema(example = "code did not return a value; may not have executed", description = "executeJS执行的可疑提示（如可能被CSP拦截、值不可序列化），agent收到后不应盲目重试")
    private String warning;

    @Schema(example = "0", description = "命令尝试的目标 frameId（0=主框架；null=全部广播）")
    private Integer attemptedFrameId;

    public static ChromeResultVO ok(Object data) {
        ChromeResultVO r = new ChromeResultVO();
        r.success = true;
        r.data = data;
        return r;
    }

    public static ChromeResultVO fail(String error) {
        ChromeResultVO r = new ChromeResultVO();
        r.success = false;
        r.errorMessage = error;
        return r;
    }
}
