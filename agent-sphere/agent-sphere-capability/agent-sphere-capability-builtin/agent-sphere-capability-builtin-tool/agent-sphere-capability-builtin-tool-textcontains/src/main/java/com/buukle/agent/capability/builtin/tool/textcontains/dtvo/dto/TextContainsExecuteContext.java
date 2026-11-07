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
    @Schema(description = "Substring A to search for. Must not be empty or whitespace-only - whitespace-only "
            + "collapses to an empty string under TRIMMED mode, and an empty string is contained by every "
            + "string, returning a counter-intuitive true.")
    private String needle;

    @NotNull
    @Schema(description = "Target string B to search in. May be empty - an empty target contains no substring, so the answer is false.")
    private String haystack;

    @Schema(description = "Match mode: EXACT (default, case-sensitive) / IGNORE_CASE / TRIMMED (trim both ends) / "
            + "IGNORE_CASE_TRIMMED. Blank is treated as EXACT.")
    private TextMatchMode mode;
}
