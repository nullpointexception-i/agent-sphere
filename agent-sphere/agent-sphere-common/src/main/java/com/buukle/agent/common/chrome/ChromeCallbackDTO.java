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
    private String resultType;
    private String warning;
    /** Device/source that executed the command (reported back by the extension; empty for old extensions). */
    private String deviceId;
    private String deviceName;
    private String source;
    private String extensionVersion;
    /** run / tool-call id carried in the command, used to persist the device info. */
    private Long runId;
    private String callId;

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
