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
    private Integer index;
    private Integer occurrence;
    private Integer ref;
    private Integer waitMs;
    private Integer ms;
    private Integer timeout;
    private String key;
    private String codeKey;
    private String direction;
    private Integer amount;
    private String value;
    private String label;
    private Integer max;
    private String fileName;
    private String fileBase64;
    private String fileType;
    private Integer frameId;
    private String scope;
    private Boolean submit;
    private java.util.List<String> fields;
    private Integer textMax;

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

    public ChromeCommandDTO withIndex(Integer index) {
        this.index = index;
        return this;
    }

    public ChromeCommandDTO withOccurrence(Integer occurrence) {
        this.occurrence = occurrence;
        return this;
    }

    public ChromeCommandDTO withRef(Integer ref) {
        this.ref = ref;
        return this;
    }

    public ChromeCommandDTO withWaitMs(Integer waitMs) {
        this.waitMs = waitMs;
        return this;
    }

    public ChromeCommandDTO withMs(Integer ms) {
        this.ms = ms;
        return this;
    }

    public ChromeCommandDTO withTimeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    public ChromeCommandDTO withKey(String key) {
        this.key = key;
        return this;
    }

    public ChromeCommandDTO withCodeKey(String codeKey) {
        this.codeKey = codeKey;
        return this;
    }

    public ChromeCommandDTO withDirection(String direction) {
        this.direction = direction;
        return this;
    }

    public ChromeCommandDTO withAmount(Integer amount) {
        this.amount = amount;
        return this;
    }

    public ChromeCommandDTO withValue(String value) {
        this.value = value;
        return this;
    }

    public ChromeCommandDTO withLabel(String label) {
        this.label = label;
        return this;
    }

    public ChromeCommandDTO withMax(Integer max) {
        this.max = max;
        return this;
    }

    public ChromeCommandDTO withFileName(String fileName) {
        this.fileName = fileName;
        return this;
    }

    public ChromeCommandDTO withFileBase64(String fileBase64) {
        this.fileBase64 = fileBase64;
        return this;
    }

    public ChromeCommandDTO withFileType(String fileType) {
        this.fileType = fileType;
        return this;
    }

    public ChromeCommandDTO withFrameId(Integer frameId) {
        this.frameId = frameId;
        return this;
    }

    public ChromeCommandDTO withScope(String scope) {
        this.scope = scope;
        return this;
    }

    public ChromeCommandDTO withSubmit(Boolean submit) {
        this.submit = submit;
        return this;
    }

    public ChromeCommandDTO withFields(java.util.List<String> fields) {
        this.fields = fields;
        return this;
    }

    public ChromeCommandDTO withTextMax(Integer textMax) {
        this.textMax = textMax;
        return this;
    }
}
