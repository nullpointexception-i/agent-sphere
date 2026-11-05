package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Shared result for Super-Agent router tools. */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentRouterToolResult extends ExecuteResult {
    private boolean ok;
    private String message;
    private Long id;

    public static AgentRouterToolResult ok(String message, Long id) {
        return new AgentRouterToolResult(true, message, id);
    }

    public static AgentRouterToolResult failed(String message) {
        return new AgentRouterToolResult(false, message, null);
    }
}
