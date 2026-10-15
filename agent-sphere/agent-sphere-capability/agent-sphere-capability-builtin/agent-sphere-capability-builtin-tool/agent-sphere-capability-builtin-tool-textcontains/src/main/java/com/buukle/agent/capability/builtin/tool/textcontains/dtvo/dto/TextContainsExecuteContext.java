package com.buukle.agent.capability.builtin.tool.textcontains.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.enums.TextMatchMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextContainsExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(description = "要找的子串 A。不能为空或纯空白 —— 纯空白在 TRIMMED 模式下会退化成空串，"
            + "而空串被任何字符串包含，会返回反直觉的 true。")
    private String needle;

    @NotNull
    @Schema(description = "被检索的目标字符串 B。允许为空串 —— 空目标串不含任何子串，答案是 false。")
    private String haystack;

    @Schema(description = "匹配模式：EXACT（默认，区分大小写）/ IGNORE_CASE / TRIMMED（两端去空白）/ "
            + "IGNORE_CASE_TRIMMED。留空按 EXACT 处理。")
    private TextMatchMode mode;
}
