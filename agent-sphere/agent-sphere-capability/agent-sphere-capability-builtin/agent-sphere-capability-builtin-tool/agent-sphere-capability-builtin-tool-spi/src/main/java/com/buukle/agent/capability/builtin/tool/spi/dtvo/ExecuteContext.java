package com.buukle.agent.capability.builtin.tool.spi.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public abstract class ExecuteContext implements Serializable {
    private Long sessionId;
    private Long runId;
    /** Model-side tool call id (used to correlate the callback and locate the tool record). */
    private String callId;
}
