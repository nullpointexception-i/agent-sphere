package com.buukle.agent.common.chrome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChromeCallbackDTO {
    private String commandId;
    private boolean success;
    private Object data;
    private String error;
    private String errorCategory;
    private String method;

    public ChromeCallbackDTO() {
    }

    public ChromeCallbackDTO(String commandId, boolean success, Object data, String error) {
        this.commandId = commandId;
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static ChromeCallbackDTO ok(String commandId, Object data) {
        return new ChromeCallbackDTO(commandId, true, data, null);
    }

    public static ChromeCallbackDTO fail(String commandId, String error) {
        return new ChromeCallbackDTO(commandId, false, null, error);
    }
}
