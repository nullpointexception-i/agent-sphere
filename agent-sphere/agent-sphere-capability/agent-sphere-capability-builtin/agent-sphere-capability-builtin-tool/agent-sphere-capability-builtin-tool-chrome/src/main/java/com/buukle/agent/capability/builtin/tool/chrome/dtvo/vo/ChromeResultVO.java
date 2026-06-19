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
