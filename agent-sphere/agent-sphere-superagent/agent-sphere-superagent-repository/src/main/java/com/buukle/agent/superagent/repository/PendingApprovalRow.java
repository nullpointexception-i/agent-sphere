package com.buukle.agent.superagent.repository;

import lombok.Data;

import java.time.LocalDateTime;

/** Read model for the workspace approval inbox. */
@Data
public class PendingApprovalRow {
    private Long id;
    private String approvalId;
    private Long sessionId;
    private Long runId;
    private String toolCallsJson;
    private String status;
    private String sessionType;
    private Long superAgentId;
    private Long workItemId;
    private String sessionTitle;
    private String agentName;
    private Long ownerUserId;
    private LocalDateTime createdAt;
}
