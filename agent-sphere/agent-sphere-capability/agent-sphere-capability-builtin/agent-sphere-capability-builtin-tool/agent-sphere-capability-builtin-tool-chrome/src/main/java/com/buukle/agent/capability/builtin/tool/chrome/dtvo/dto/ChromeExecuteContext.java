package com.buukle.agent.capability.builtin.tool.chrome.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
}
