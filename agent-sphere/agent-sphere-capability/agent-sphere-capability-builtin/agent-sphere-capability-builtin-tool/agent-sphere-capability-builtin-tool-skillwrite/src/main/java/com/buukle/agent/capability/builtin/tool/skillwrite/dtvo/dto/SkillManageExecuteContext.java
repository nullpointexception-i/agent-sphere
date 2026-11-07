package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Input of skill management (builtin_9): only the fields needed to create/append/update. */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillManageExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(
            description = "Operation type",
            allowableValues = {"create", "append", "update"}
    )
    private String action;

    @Schema(description = "Target skill id for append / update")
    private Long skillId;

    @Schema(description = "Skill name for create")
    private String name;

    @Schema(description = "Skill description")
    private String description;

    @Schema(description = "definition JSON submitted by create / update(full)")
    private String definition;

    @Schema(
            description = "Markdown operation type for update(patch)",
            allowableValues = {"replace_section", "insert_after", "replace_text"}
    )
    private String operation;

    @Schema(description = "Heading used to locate the target when patching")
    private String headingSearch;

    @Schema(description = "Text to replace for replace_text")
    private String searchText;

    @Schema(description = "Replacement text for replace_text")
    private String replaceText;

    @Schema(description = "Content to append for append / replacement content for patch")
    private String content;
}
