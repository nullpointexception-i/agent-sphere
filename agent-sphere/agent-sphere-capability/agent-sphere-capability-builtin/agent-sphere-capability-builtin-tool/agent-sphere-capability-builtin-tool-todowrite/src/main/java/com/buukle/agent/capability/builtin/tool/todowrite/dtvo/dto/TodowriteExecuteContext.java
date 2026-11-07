package com.buukle.agent.capability.builtin.tool.todowrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class TodowriteExecuteContext extends ExecuteContext {
    @NotNull
    @Valid
    private List<TodoItemDTO> todos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TodoItemDTO {
        @NotNull
        private String content;
        @NotNull
        private String status;
        @NotNull
        private String priority;

        @Schema(description = "Source skill id (the skillId returned by skill_library). Fill this only when the item "
                + "comes from a skill step; before executing, use it with stepId to re-read the body, otherwise leave blank.")
        private Long skillId;

        @Schema(description = "Heading identifier of the source skill step, e.g. step1 / step1.1 (the first word of "
                + "the skill outline heading). When executing this item, read that section body via "
                + "skill_library(action=get, skillId=<skillId>, sectionHeading=<stepId>).")
        private String stepId;
    }
}
