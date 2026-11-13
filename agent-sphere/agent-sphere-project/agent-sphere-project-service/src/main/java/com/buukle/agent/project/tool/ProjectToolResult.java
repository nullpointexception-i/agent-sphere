package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Shared result for the project-assistant tools. */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class ProjectToolResult extends ExecuteResult {

    private boolean ok;
    private String message;
    private Object data;

    public static ProjectToolResult ok(String message, Object data) {
        return new ProjectToolResult(true, message, data);
    }

    public static ProjectToolResult failed(String message) {
        return new ProjectToolResult(false, message, null);
    }
}
