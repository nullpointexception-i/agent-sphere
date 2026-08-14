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
