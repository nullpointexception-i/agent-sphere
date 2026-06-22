package com.buukle.agent.common.chrome;

import lombok.Data;

@Data
public class ChromeCommandDTO {
    private String eventType = "browser_operation";
    private Long sessionId;
    private String commandId;
    private String action;
    private String url;
    private String selector;
    private String text;
    private String code;
    private String mode;
    private Integer tabId;
    private Boolean append;

    public ChromeCommandDTO() {
    }

    public ChromeCommandDTO(Long sessionId, String commandId, String action) {
        this.sessionId = sessionId;
        this.commandId = commandId;
        this.action = action;
    }

    public ChromeCommandDTO withUrl(String url) {
        this.url = url;
        return this;
    }

    public ChromeCommandDTO withSelector(String selector) {
        this.selector = selector;
        return this;
    }

    public ChromeCommandDTO withText(String text) {
        this.text = text;
        return this;
    }

    public ChromeCommandDTO withCode(String code) {
        this.code = code;
        return this;
    }

    public ChromeCommandDTO withMode(String mode) {
        this.mode = mode;
        return this;
    }

    public ChromeCommandDTO withTabId(Integer tabId) {
        this.tabId = tabId;
        return this;
    }

    public ChromeCommandDTO withAppend(Boolean append) {
        this.append = append;
        return this;
    }
}
