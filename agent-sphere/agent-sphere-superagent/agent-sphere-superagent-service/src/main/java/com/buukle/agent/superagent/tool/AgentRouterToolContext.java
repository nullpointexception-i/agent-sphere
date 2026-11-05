package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Shared input for Super-Agent router tools. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRouterToolContext extends ExecuteContext {
    private String goal;
    private Integer priority;
    private String text;
    private String format;
    private Long eventId;
    private String mode;
    private String reason;
    private String cardType;
    private String cardTitle;
    private String cardStatus;
    private String cardSummary;
    private Long workItemId;
}
