package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** One pending L2 tool approval shown in the workspace approval inbox. */
@Data
public class PendingApprovalVO implements Serializable {
    private String approvalId;
    private Long sessionId;
    private Long runId;
    private String sessionType;
    private Long superAgentId;
    private String agentName;
    private Long workItemId;
    private String sessionTitle;
    private String toolCallsJson;
    private String createdAt;
}
