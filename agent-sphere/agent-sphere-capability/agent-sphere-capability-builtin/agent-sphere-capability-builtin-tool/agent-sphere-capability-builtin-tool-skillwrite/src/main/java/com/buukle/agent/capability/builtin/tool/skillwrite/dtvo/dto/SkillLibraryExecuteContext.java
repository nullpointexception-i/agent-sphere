package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Input of the skill library (builtin_8): only the fields needed to discover/read skills. */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillLibraryExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(
            description = "Operation type",
            allowableValues = {"list", "count", "search", "search_by_title", "get"}
    )
    private String action;

    @Schema(description = "For get: read by skill id")
    private Long skillId;

    @Schema(description = "For get: read by exact skill name")
    private String name;

    @Schema(description = "For get: return only the promptTemplate outline")
    private Boolean structure;

    @Schema(description = "For get: read one section by heading")
    private String sectionHeading;

    @Schema(description = "For get: start line number")
    private Integer startLine;

    @Schema(description = "For get: end line number")
    private Integer endLine;

    @Schema(description = "Name keyword for list / search")
    private String keyword;

    @Schema(description = "Page number for list / search", defaultValue = "1")
    private int page = 1;

    @Schema(description = "Page size for list / search", defaultValue = "20")
    private int pageSize = 20;
}
