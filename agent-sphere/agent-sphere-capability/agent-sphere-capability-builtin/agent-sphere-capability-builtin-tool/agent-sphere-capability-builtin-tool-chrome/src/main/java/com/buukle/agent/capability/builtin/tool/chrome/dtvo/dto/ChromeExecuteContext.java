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

    @Schema(example = "document.title")
    private String code;

    @Schema(example = "summary", description = "getContent的扫描模式：summary（结构化摘要，含inputs/buttons/forms/navLinks/sections/dialogs）或留空（完整DOM）")
    private String mode;

    @Schema(example = "123", description = "指定操作的标签页ID，为空时自动使用当前受控标签页")
    private Integer tabId;

    @Schema(example = "false", description = "type操作时是否追加到已有内容末尾（不替换）")
    private Boolean append;

    @Schema(example = "2", description = "selector命中多个元素时定位第N个（1起），如两个相同的input[type=number]；仅click/type/hover生效")
    private Integer index;

    @Schema(example = "2", description = "text匹配多处文本时定位第N处（1起），如多个\"确定\"按钮；仅click/hover生效")
    private Integer occurrence;

    @Schema(example = "0", description = "快照(getContent mode:snapshot)返回的稳定元素引用（0起），用于click/type/hover/select/upload直接定位，免去selector；优先于selector/text")
    private Integer ref;

    @Schema(example = "3000", description = "元素出现/可交互前的最大等待毫秒（默认3000），click/type/hover/select/upload生效")
    private Integer waitMs;

    @Schema(example = "500", description = "wait动作：固定等待毫秒")
    private Integer ms;

    @Schema(example = "8000", description = "wait动作：等待selector/text/ref出现的最长毫秒（默认8000）")
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

    @Schema(example = "[\"text\",\".name\",\"@data-id\"]", description = "getContent mode:extract 的字段：text=元素文本；.xxx=相对子选择器取首个子元素文本；@attr=取属性；href/value 为内置字段")
    private List<String> fields;

    @Schema(example = "[\".dropdown-city\",\".degree-condition-ui\"]", description = "getContent mode:containers 的容器选择器列表（跨同源 iframe 探测 present/count/frame），也可用逗号分隔的单个 selector")
    private List<String> selectors;

    @Schema(example = "200", description = "getContent mode:extract / textMax：单字段文本截断长度（默认200）")
    private Integer textMax;
}
