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

    @Schema(example = "https://example.com", description = "Navigate的目标URL，仅action=navigate时必填")
    private String url;

    @Schema(example = "#search-btn")
    private String selector;

    @Schema(example = "北京")
    private String text;

    @Schema(example = "document.title",
            description = "executeJS only. Read-only; must return small JSON. Navigation writes are rejected "
                    + "(js_navigation_blocked). Use click to open/return.")
    private String code;

    @Schema(example = "snapshot", description = "getContent mode: snapshot/summary/axtree/query/extract/containers/ready.")
    private String mode;

    @Schema(example = "123", description = "指定操作的标签页ID，为空时自动使用当前受控标签页")
    private Integer tabId;

    @Schema(example = "false", description = "type操作时是否追加到已有内容末尾（不替换）")
    private Boolean append;

    @Schema(example = "2", description = "selector命中多个元素时定位第N个（1起），如两个相同的input[type=number]；仅click/type/hover生效")
    private Integer index;

    @Schema(example = "2", description = "text匹配多处文本时定位第N处（1起），如多个\"确定\"按钮；仅click/hover生效")
    private Integer occurrence;

    @Schema(example = "0", description = "Snapshot ref (0-based); preferred over selector/text.")
    private Integer ref;

    @Schema(example = "3000", description = "Max wait for element readiness (default 3000ms).")
    private Integer waitMs;

    @Schema(example = "500", description = "Deprecated wire-compat field; use the global wait tool.")
    private Integer ms;

    @Schema(example = "10", description = "Command timeout seconds (2-30).")
    private Integer timeout;

    @Schema(example = "Enter", description = "key动作：按键名（Enter/Tab/Escape/Backspace/ArrowDown等），可选codeKey指定物理键")
    private String key;

    @Schema(example = "Enter", description = "key动作：物理键code")
    private String codeKey;

    @Schema(example = "down", description = "scroll动作：方向 up/down/left/right，或给定selector/ref滚动到该元素")
    private String direction;

    @Schema(example = "600", description = "scroll动作：滚动像素，缺省为视口高度0.8")
    private Integer amount;

    @Schema(example = "value_1", description = "select动作：<select>要选中的value")
    private String value;

    @Schema(example = "选项B", description = "select动作：<select>要选中的选项文本或value")
    private String label;

    @Schema(example = "200", description = "getContent mode:snapshot 的元素上限（默认200）")
    private Integer max;

    @Schema(example = "resume.pdf", description = "upload动作：文件名")
    private String fileName;

    @Schema(example = "JVBERi0xLjQK...", description = "upload动作：文件内容base64")
    private String fileBase64;

    @Schema(example = "application/pdf", description = "upload动作：MIME类型")
    private String fileType;

    @Schema(example = "0", description = "指定操作的iframe frameId（同源iframe内元素定位；缺省顶层）")
    private Integer frameId;

    @Schema(example = "[role=\"dialog\"]", description = "把 text/selector 查找限定在该选择器对应的区域内（解决重复文本如多个\"确定\"/\"北京\"），仅 click/hover/type 生效")
    private String scope;

    @Schema(example = "true", description = "type 动作：输入后派发 Enter 提交（如搜索框回车触发搜索），并回传 _submitted/changed")
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

    @Schema(example = "jpeg", description = "screenshot动作：图片格式 jpeg/png，默认 jpeg")
    private String format;

    @Schema(example = "60", description = "screenshot动作：jpeg 质量 0-100，默认 60")
    private Integer quality;
}
