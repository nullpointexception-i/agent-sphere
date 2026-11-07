package com.buukle.agent.capability.builtin.tool.chrome.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ChromeExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(example = "navigate")
    private String action;

    @Schema(example = "https://example.com", description = "Target URL for navigate; required only when action=navigate")
    private String url;

    @Schema(example = "#search-btn")
    private String selector;

    @Schema(example = "Search")
    private String text;

    @Schema(example = "document.title",
            description = "executeJS only. Read-only; must return small JSON. Navigation writes are rejected "
                    + "(js_navigation_blocked). Use click to open/return. NEVER use executeJS for long loops, "
                    + "polling, sleeps, delays, or waiting; call the global wait tool instead. Split many items "
                    + "into small steps and keep each executeJS under ~7 seconds. No setTimeout/setInterval/"
                    + "long await loops.")
    private String code;

    @Schema(example = "snapshot", description = "getContent mode: snapshot/summary/axtree/query/extract/containers/ready.")
    private String mode;

    @Schema(example = "123", description = "Target tab ID for the command; defaults to the current controlled tab when empty")
    private Integer tabId;

    @Schema(example = "false", description = "For type: append to existing content instead of replacing it")
    private Boolean append;

    @Schema(example = "2", description = "1-based index when the selector matches multiple elements (e.g. two identical input[type=number]); applies to click/type/hover only")
    private Integer index;

    @Schema(example = "2", description = "1-based occurrence when text matches multiple places (e.g. several \"OK\" buttons); applies to click/hover only")
    private Integer occurrence;

    @Schema(example = "0", description = "Snapshot ref (0-based); preferred over selector/text.")
    private Integer ref;

    @Schema(example = "3000", description = "Max wait for element readiness (default 3000ms).")
    private Integer waitMs;

    @Schema(example = "500", description = "Deprecated wire-compat field; use the global wait tool.")
    private Integer ms;

    @Schema(example = "10", description = "Command timeout seconds (2-30).")
    private Integer timeout;

    @Schema(example = "Enter", description = "key action: key name (Enter/Tab/Escape/Backspace/ArrowDown etc.); codeKey optionally selects the physical key")
    private String key;

    @Schema(example = "Enter", description = "key action: physical key code")
    private String codeKey;

    @Schema(example = "down", description = "scroll action: direction up/down/left/right, or scroll to the element given by selector/ref")
    private String direction;

    @Schema(example = "600", description = "scroll action: pixels to scroll; defaults to 0.8 x viewport height")
    private Integer amount;

    @Schema(example = "value_1", description = "select action: the <select> value to choose")
    private String value;

    @Schema(example = "Option B", description = "select action: the <select> option label or value to choose")
    private String label;

    @Schema(example = "200", description = "getContent mode=snapshot: element cap (default 200)")
    private Integer max;

    @Schema(example = "resume.pdf", description = "upload action: file name")
    private String fileName;

    @Schema(example = "JVBERi0xLjQK...", description = "upload action: file content as base64")
    private String fileBase64;

    @Schema(example = "application/pdf", description = "upload action: MIME type")
    private String fileType;

    @Schema(example = "0", description = "iframe frameId for the command (locate elements inside a same-origin iframe; defaults to the top frame)")
    private Integer frameId;

    @Schema(example = "[role=\"dialog\"]", description = "Restrict text/selector lookup to the region matched by this selector (for duplicate text such as several \"OK\"/\"Search\" buttons); applies to click/hover/type only")
    private String scope;

    @Schema(example = "true", description = "type action: dispatch Enter after typing to submit (e.g. submit a search box) and return _submitted/changed")
    private Boolean submit;

    @Schema(example = "[\"text\",\".name\",\"@data-id\"]", description = "extract fields: text, .child, @attr, href, value.")
    private List<String> fields;

    @Schema(example = "[\".dropdown-city\",\".degree-condition-ui\"]", description = "containers selectors (cross-frame present/count/frame).")
    private List<String> selectors;

    @Schema(example = "200", description = "extract text truncation length (default 200).")
    private Integer textMax;

    @Schema(example = "320", description = "clickAt: device-pixel X on the last viewport screenshot.")
    private Integer x;

    @Schema(example = "560", description = "clickAt: device-pixel Y on the last viewport screenshot.")
    private Integer y;

    @Schema(example = "jpeg", description = "screenshot action: image format jpeg/png, default jpeg")
    private String format;

    @Schema(example = "60", description = "screenshot action: jpeg quality 0-100, default 60")
    private Integer quality;
}
