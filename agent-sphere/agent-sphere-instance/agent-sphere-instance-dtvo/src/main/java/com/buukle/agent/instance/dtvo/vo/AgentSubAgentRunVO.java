package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentSubAgentRunVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long parentRunId;
    private String parentToolCallId;
    private String agentType;
    private String agentRef;
    private String displayName;
    private String status;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
}