package com.buukle.agent.capability.builtin.tool.todowrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    }
}
